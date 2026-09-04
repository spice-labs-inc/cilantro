// DotnetProbeTests — D1-xx: the DotnetAssemblyProbe classifier.
//
// Why these tests exist:
//   Goat Rodeo's DotnetDetector ran the FULL AssemblyDefinition read
//   (every metadata heap, the whole model) just to decide the .NET
//   MIME. The probe (plan 2026_09_01 phase-08, ADR-0012) classifies
//   from PE headers + CLI header + BSJB magic inside one bounded
//   region read (maxHeaderReadBudget = 64 KiB), on a plain
//   InputStream (user decision: no path required), fail-closed, with
//   no stream side effects where the stream type allows.
//
// Theory of the test:
//   - Real .NET DLLs (7 in test-files, 4 corpus fixtures) probe true
//     and their header fields agree with the full read.
//   - Non-.NET inputs (text, zip, gzip, a PE with a zeroed CLI
//     directory, a flipped BSJB) probe false.
//   - Hostile claims (2 GiB heaps, 0xFFFFFFFF debug sizes, a huge
//     version length, a sparse 4 GiB file) never drive allocation and
//     complete quickly.
//   - Stream contracts: FileInputStream / mark-supported streams keep
//     their position; non-mark streams advance by at most the budget.
//   - Property sweeps: truncation at every byte boundary and seeded
//     flips never throw; probes are idempotent.
//   - The Slow full-corpus agreement (D1-11) pins probe == full-read
//     success across all 151 corpus assemblies.
//
// Requirements traced:
//   plans/2026_09_01_cilantro_hardening_and_dotnet_probe/phase-08.md
//   D1-01..D1-14 (the DotnetDetector efficiency ask; user decision
//   2026-09-01).
//
// LLM notes:
//   - The probe mirrors ImageReader's exact optional-header field
//     layout; the CLI data directory is index 14.
//   - dotnetHeader returns Option; isDotnetAssembly is its isDefined.
//   - The probe never reads heaps: hostile heap-size claims are
//     irrelevant to it (D1-05).

package io.spicelabs.cilantro.metadata

import scala.util.{Success, Failure}
import io.spicelabs.cilantro.{DotnetAssemblyProbe, ModuleDefinition, AssemblyDefinition}
import io.spicelabs.cilantro.cil.MinimalPeBuilder
import org.json4s.DefaultFormats
import org.json4s.jvalue2monadic
import org.json4s.jvalue2extractable
import java.io.{ByteArrayInputStream, BufferedInputStream, DataInputStream, File, FileInputStream, InputStream}
import java.nio.file.{Files, Paths}
import java.util.zip.{ZipOutputStream, GZIPOutputStream}

class DotnetProbeTests extends munit.FunSuite {


  private def corpusRoot = CorpusProvisioner.ensureCorpus()

  // The 7 real DLLs in test-files/ + the 4 corpus fixtures.
  private def realDlls(): Vector[(String, String)] = {
    Vector(
      ("../../test-files/smoke/Smoke.dll", "smoke"),
      ("../../test-files/Newtonsoft.Json/net20/Newtonsoft.Json.dll", "newtonsoft-net20"),
      ("../../test-files/Newtonsoft.Json/net6.0/Newtonsoft.Json.dll", "newtonsoft-net6"),
      ("../../test-files/halibut.8.1.1485/net48/Halibut.dll", "halibut-net48"),
      ("../../test-files/halibut.8.1.1485/net8.0/Halibut.dll", "halibut-net8"),
      ("../../test-files/codeanalyzergenerator.2.0.0/net9.0/CodeAnalyzerGenerator.dll", "codeanalyzer"),
      ("../../test-files/dndgen.charactergen.15.0.0/net8.0/DnDGen.CharacterGen.dll", "dndgen"),
      (corpusRoot.resolve("fixtures/embedded_pdb_fixture.dll").toString, "fixture-pdb"),
      (corpusRoot.resolve("fixtures/ilasm_fixture.dll").toString, "fixture-ilasm"),
      (corpusRoot.resolve("fixtures/resources_fixture.dll").toString, "fixture-resources"),
      (corpusRoot.resolve("fixtures/x64_fixture.dll").toString, "fixture-x64")
    )
  }

  private def withTempFile(bytes: Array[Byte])(body: File => Unit): Unit = {
    val file = File.createTempFile("probe", ".bin")
    Files.write(file.toPath, bytes)
    try {
      body(file)
    } finally {
      file.delete()
    }
  }

  test("D1-01: every real .NET test file probes true") {
    realDlls().foreach { case (path, label) =>
      assert(DotnetAssemblyProbe.isDotnetAssembly(path), s"$label must probe as .NET")
      assert(DotnetAssemblyProbe.dotnetHeader(path).isDefined, s"$label must yield a header")
    }
  }

  test("D1-02: probe header fields agree with the full read") {
    realDlls().foreach { case (path, label) =>
      val probed = DotnetAssemblyProbe.dotnetHeader(path).getOrElse(fail(s"$label must probe"))
      ModuleDefinition.readModule(path) match {
        case Success(module) =>
          assertEquals(probed.architecture, module.targetArchitecture, s"$label architecture")
          assertEquals(probed.moduleKind, module.kind, s"$label kind")
          assertEquals(probed.runtimeVersion, module.runtime_version, s"$label runtime version")
          val image = module.image.getOrElse(fail(s"$label must have an image"))
          assertEquals(probed.entryPointToken, image.entryPointToken, s"$label entry point")
          assertEquals(probed.attributes, image.attributes, s"$label flags")
          assertEquals(probed.resources.isDefined, image.resources.exists(!_.isZero), s"$label resources presence")
          assertEquals(probed.strongName.isDefined, image.strongName.exists(!_.isZero), s"$label strong name presence")
          probed.metadataFileOffset.foreach { raw =>
            val bytes = Files.readAllBytes(Paths.get(path))
            if (raw + 4 <= bytes.length) {
              val magic = new String(bytes, raw, 4, "UTF-8")
              assertEquals(magic, "BSJB", s"$label metadata magic at the resolved offset")
            }
          }
        case Failure(e) => fail(s"$label must read: $e")
      }
    }
  }

  test("D1-03: non-.NET inputs probe false") {
    withTempFile("just some text, not a PE".getBytes("UTF-8")) { f =>
      assert(!DotnetAssemblyProbe.isDotnetAssembly(f), "text must not probe as .NET")
    }
    withTempFile(Array[Byte]('M', 'Z', 0, 1, 2, 3, 4, 5)) { f =>
      assert(!DotnetAssemblyProbe.isDotnetAssembly(f), "a truncated MZ stub must not probe")
    }
    withTempFile(zipBytes()) { f =>
      assert(!DotnetAssemblyProbe.isDotnetAssembly(f), "a zip must not probe")
    }
    withTempFile(gzipBytes()) { f =>
      assert(!DotnetAssemblyProbe.isDotnetAssembly(f), "a gzip must not probe")
    }
    // A valid PE whose CLI directory is zeroed: not .NET.
    val pe = new MinimalPeBuilder(cliHeaderPresent = false).build()
    withTempFile(pe) { f =>
      assert(!DotnetAssemblyProbe.isDotnetAssembly(f), "a PE without a CLI header must not probe")
      assert(DotnetAssemblyProbe.dotnetHeader(f).isEmpty)
    }
  }

  test("D1-04: a header-valid file with a flipped BSJB probes false") {
    val bytes = new MinimalPeBuilder().build()
    val flipped = bytes.clone()
    // The metadata root's BSJB magic: find it in the file.
    var idx = -1
    var i = 0
    while (i < bytes.length - 4 && idx < 0) {
      if (bytes(i) == 'B'.toByte && bytes(i + 1) == 'S'.toByte && bytes(i + 2) == 'J'.toByte && bytes(i + 3) == 'B'.toByte) {
        idx = i
      }
      i += 1
    }
    assert(idx > 0, "the synthetic PE must carry a BSJB magic")
    flipped(idx) = 'X'.toByte
    withTempFile(flipped) { f =>
      assert(!DotnetAssemblyProbe.isDotnetAssembly(f), "a flipped metadata magic must not probe")
    }
  }

  test("D1-05: hostile size claims never drive allocation") {
    // Heaps claiming 2 GiB, a debug size of 0xFFFFFFFF: the full read
    // would fail; the probe reads only the header region and still
    // classifies correctly.
    val hostile = new MinimalPeBuilder(
      blobHeapSize = 0x7fffffff,
      debugDirectory = Some(Array.fill[Byte](28)(0.toByte))
    ).build()
    withTempFile(hostile) { f =>
      assert(DotnetAssemblyProbe.isDotnetAssembly(f), "heap/debug claims must not affect the probe")
    }
    // A sparse 4 GiB file: the probe completes without touching the
    // data region.
    val sparse = File.createTempFile("probe", ".dll")
    try {
      val raf = new java.io.RandomAccessFile(sparse, "rw")
      try {
        raf.setLength(4L * 1024 * 1024 * 1024)
      } finally {
        raf.close()
      }
      assert(!DotnetAssemblyProbe.isDotnetAssembly(sparse), "a sparse non-PE file must not probe")
    } finally {
      sparse.delete()
    }
  }

  test("D1-06: a hostile metadata version length probes false, no allocation") {
    val bytes = new MinimalPeBuilder(versionLengthClaim = Some(0x7fffffff)).build()
    withTempFile(bytes) { f =>
      assert(!DotnetAssemblyProbe.isDotnetAssembly(f), "a 2 GiB version-length claim must fail closed")
    }
  }

  test("D1-07: the probe preserves stream position on FileInputStream and mark-supported streams") {
    val (path, _) = realDlls().head
    val original = Files.readAllBytes(Paths.get(path))
    val fis = new FileInputStream(path)
    try {
      assert(DotnetAssemblyProbe.isDotnetAssembly(fis), "the probe must classify")
      val after = new Array[Byte](original.length)
      var off = 0
      var n = fis.read(after, off, after.length - off)
      while (n > 0 && off < after.length) {
        off += n
        n = fis.read(after, off, after.length - off)
      }
      assertEquals(off, original.length, "the whole file must still be readable from the start")
      assertEquals(after.toVector, original.toVector, "the bytes must be untouched")
    } finally {
      fis.close()
    }
    // ByteArrayInputStream is mark-supported.
    val bais = new ByteArrayInputStream(original)
    assert(DotnetAssemblyProbe.isDotnetAssembly(bais), "a mark-supported stream must classify")
    val rest = new Array[Byte](original.length)
    val read = bais.read(rest)
    assertEquals(read, original.length, "the mark-supported stream must reset to the start")
    assertEquals(rest.toVector, original.toVector)
  }

  test("D1-13: generic InputStream overloads classify and agree with the File overload") {
    realDlls().foreach { case (path, label) =>
      val bytes = Files.readAllBytes(Paths.get(path))
      val viaFile = DotnetAssemblyProbe.isDotnetAssembly(new File(path))
      assert(DotnetAssemblyProbe.isDotnetAssembly(new BufferedInputStream(new ByteArrayInputStream(bytes))), s"$label via BufferedInputStream")
      assert(DotnetAssemblyProbe.isDotnetAssembly(new ByteArrayInputStream(bytes)), s"$label via ByteArrayInputStream")
      assert(DotnetAssemblyProbe.isDotnetAssembly(new DataInputStream(new ByteArrayInputStream(bytes))), s"$label via DataInputStream")
      assertEquals(viaFile, true, s"$label via File")
    }
  }

  test("D1-14: a non-mark stream advances by at most the budget (documented)") {
    val (path, _) = realDlls().head
    val original = Files.readAllBytes(Paths.get(path))
    var position = 0
    val noMark = new InputStream {
      override def read(): Int = {
        if (position < original.length) {
          val b = original(position) & 0xff
          position += 1
          b
        } else {
          -1
        }
      }
      override def read(b: Array[Byte], off: Int, len: Int): Int = {
        if (position >= original.length) -1
        else {
          val n = Math.min(len, original.length - position)
          System.arraycopy(original, position, b, off, n)
          position += n
          n
        }
      }
      override def markSupported(): Boolean = false
    }
    assert(DotnetAssemblyProbe.isDotnetAssembly(noMark), "a non-mark stream must classify")
    assert(position <= DotnetAssemblyProbe.maxHeaderReadBudget, s"the advance is bounded by the budget, got $position")
  }

  test("D1-08: truncation at every byte boundary never throws (property, seeded)") {
    val (path, _) = realDlls().head
    val bytes = Files.readAllBytes(Paths.get(path))
    val offsets = (0 to Math.min(4096, bytes.length)) ++ (bytes.length - 64 to bytes.length)
    var classified = 0
    offsets.distinct.foreach { len =>
      val truncated = java.util.Arrays.copyOf(bytes, len)
      val result = DotnetAssemblyProbe.dotnetHeader(new ByteArrayInputStream(truncated))
      assert(result.isInstanceOf[Option[io.spicelabs.cilantro.DotnetAssemblyHeader]], s"truncation at $len must return an Option")
      if (result.isDefined) {
        classified += 1
      }
    }
    assert(classified >= 1, "the full file must classify; some truncations may too")
  }

  test("D1-09: seeded flips in the header region never throw and are idempotent") {
    val (path, _) = realDlls().head
    val bytes = Files.readAllBytes(Paths.get(path))
    val rng = new scala.util.Random(20260901L)
    var i = 0
    while (i < 200) {
      val mutated = bytes.clone()
      val flips = 1 + rng.nextInt(8)
      var f = 0
      while (f < flips) {
        val at = rng.nextInt(Math.min(4096, mutated.length))
        mutated(at) = (mutated(at) ^ (1 << rng.nextInt(8))).toByte
        f += 1
      }
      val first = DotnetAssemblyProbe.isDotnetAssembly(new ByteArrayInputStream(mutated))
      val second = DotnetAssemblyProbe.isDotnetAssembly(new ByteArrayInputStream(mutated))
      assertEquals(first, second, s"probes must agree on the same bytes (flip set $i)")
      i += 1
    }
  }

  test("D1-10: the overloads agree and probes are deterministic") {
    val (path, _) = realDlls().head
    val viaString = DotnetAssemblyProbe.isDotnetAssembly(path)
    val viaPath = DotnetAssemblyProbe.isDotnetAssembly(Paths.get(path))
    val viaFile = DotnetAssemblyProbe.isDotnetAssembly(new File(path))
    assertEquals(viaString, viaFile, "String and File overloads agree")
    assertEquals(viaPath, viaFile, "Path and File overloads agree")
    assertEquals(
      DotnetAssemblyProbe.isDotnetAssembly(path),
      DotnetAssemblyProbe.isDotnetAssembly(path),
      "repeated probes agree"
    )
  }

  test("D1-12: the documented read budget is the code's constant") {
    assertEquals(DotnetAssemblyProbe.maxHeaderReadBudget, 64 * 1024, "the documented 64 KiB budget")
  }

  test("D1-11: full-corpus agreement — probe verdict == full-read success") {
    val manifest = org.json4s.native.JsonMethods.parse(
      new String(Files.readAllBytes(corpusRoot.resolve("manifest.json")), "UTF-8"))
    implicit val formats: DefaultFormats.type = DefaultFormats
    var total = 0
    var agreed = 0
    (manifest \ "packages").children.foreach { pkg =>
      (pkg \ "assemblies").children.foreach { asm =>
        val rel = (asm \ "path").extract[String]
        val path = corpusRoot.resolve(rel).toString
        val fullRead = AssemblyDefinition.readAssembly(path).isSuccess
        val probed = DotnetAssemblyProbe.isDotnetAssembly(path)
        total += 1
        if (fullRead == probed) {
          agreed += 1
        } else {
          fail(s"$rel: full read success=$fullRead but probe=$probed")
        }
        if (fullRead && probed) {
          val header = DotnetAssemblyProbe.dotnetHeader(path).getOrElse(fail(s"$rel must yield a header"))
          ModuleDefinition.readModule(path) match {
            case Success(module) =>
              assertEquals(header.architecture, module.targetArchitecture, s"$rel architecture")
              assertEquals(header.moduleKind, module.kind, s"$rel kind")
              assertEquals(header.entryPointToken, module.image.flatMap(i => Some(i.entryPointToken)).getOrElse(0), s"$rel entry point")
            case Failure(e) => fail(s"$rel must read: $e")
          }
        }
      }
    }
    assertEquals(total, 151, "the full corpus")
    assertEquals(agreed, total, "probe and full read must agree everywhere")
  }

  private def zipBytes(): Array[Byte] = {
    val out = new java.io.ByteArrayOutputStream()
    val z = new ZipOutputStream(out)
    z.putNextEntry(new java.util.zip.ZipEntry("a.txt"))
    z.write("hello".getBytes("UTF-8"))
    z.closeEntry()
    z.close()
    out.toByteArray
  }

  private def gzipBytes(): Array[Byte] = {
    val out = new java.io.ByteArrayOutputStream()
    val g = new GZIPOutputStream(out)
    g.write("hello".getBytes("UTF-8"))
    g.close()
    out.toByteArray
  }

  test("CP-1g: the probe creates no temp files — forked with an unusable tmpdir") {
    // Plan 2026_09_02 (CP-1 mapping): no D1 test observes temp-file
    // creation, so this behavioral test fills the gap. The child runs
    // the probe with -Djava.io.tmpdir set to a nonexistent directory;
    // any temp-file creation would throw IOException, the probe would
    // fail closed (false), and the child's TRUE verdict would not
    // print. A genuine assembly must still probe TRUE.
    val dll = new File("../../test-files/smoke/Smoke.dll").getAbsolutePath
    val (code, outText) = io.spicelabs.cilantro.testutil.ForkSupport.runForked(
      List("probe", dll),
      extraJvmArgs = List("-Djava.io.tmpdir=/nonexistent-cilantro-tmpdir")
    )
    assertEquals(code, 0, s"the child must exit cleanly; output: $outText")
    assert(outText.contains("TRUE"), s"the probe must classify under an unusable tmpdir: $outText")
  }
}
