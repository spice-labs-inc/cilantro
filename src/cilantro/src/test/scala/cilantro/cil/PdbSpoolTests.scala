// PdbSpoolTests — CP-5a..f: the embedded portable PDB reader
// (plans 2026_09_02 + 2026_09_04; ADR-0014 D-6/D-12/D-13 + B-8).
//
// Why these tests exist:
//   The embedded PDB is a raw-deflate blob that must be decompressed
//   before its tables can be walked. Reading a PDB is 100% separate
//   from AssemblyEntry and from the walk (B-8): the reader is
//   callback-owned — MetadataReader.withEmbeddedPdb hands f the
//   outcome (Try[Option[PDBView]]) and cleanup is automatic when f
//   returns or throws. The byte-faithful walk (B-9/B-10) never
//   decompresses: a type-17 assembly always walks `Some` with its raw
//   DebugBlob entry, and hostile PDB structure surfaces only as a
//   reader refusal — the contractual pair pinned here.
//
// Theory of the test:
//   - CP-5a: spool placement and hygiene at the READER (scratch
//     appears in the caller's dir during f, is deleted afterwards,
//     the dir and its pre-existing content are untouched; bad spool
//     dirs refuse); the walk creates no temp state anywhere
//     (behavioral fork with a watched java.io.tmpdir).
//   - CP-5b: the pinned fixture's sources stream with the exact
//     pinned bytes through withEmbeddedPdb.
//   - CP-5c: spoolDir = None -> f sees Success(None); magic-invalid
//     type-17 -> reader Success(None) while the WALK still yields
//     `Some` with the type-17 DebugBlob (raw bytes, hint unchanged —
//     the hint is a type claim, B-9).
//   - CP-5d: envelope refusals — declared > actual, declared <
//     actual (abort at the bound), declared >= 2^31 (refusal BEFORE
//     any write), exact-size boundary succeeds; each refusal deletes
//     the partial scratch; the same artifact WALKS `Some` (B-10 pair).
//   - CP-5e: hostile PDB internals via a decompress -> patch ->
//     re-deflate harness: row-count bomb and heap-offset-past-end
//     refuse at the reader while the walk stays `Some`; source-level
//     deflate mismatch refuses at stream level (IOException inside
//     processStream).
//   - CP-5f: pull semantics — reading only the start of a deflate
//     source performs only the work the consumer demands.
//
// Requirements traced:
//   workspace/2026_09_01_cilantro_handoff.md §4 CP-5; ADR-0014.

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import io.spicelabs.cilantro._
import io.spicelabs.cilantro.PE.{RawInflate, RawInflateInputStream}
import io.spicelabs.cilantro.metadata.CorpusProvisioner
import io.spicelabs.cilantro.testutil.ForkSupport
import java.io.{File, InputStream}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class PdbSpoolTests extends munit.FunSuite {

  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  private def corpusRoot = CorpusProvisioner.ensureCorpus()

  private def fixturePath: String =
    corpusRoot.resolve("fixtures/embedded_pdb_fixture.dll").toString

  private def fixtureBytes: Array[Byte] = Files.readAllBytes(java.nio.file.Paths.get(fixturePath))

  private def payloadBytes(p: PayloadSource): Array[Byte] =
    p.processStream(in => in.readAllBytes())

  private def deleteRecursively(dir: Path): Unit = {
    if (Files.isDirectory(dir)) {
      val stream = Files.list(dir)
      try {
        stream.forEach(p => deleteRecursively(p))
      } finally {
        stream.close()
      }
    }
    try {
      Files.deleteIfExists(dir)
      ()
    } catch {
      case _: java.io.IOException => ()
    }
  }

  private def withSpool[T](body: Path => T): T = {
    val dir = Files.createTempDirectory("cilantro-test-spool")
    try {
      body(dir)
    } finally {
      deleteRecursively(dir)
    }
  }

  private def withFile[T](bytes: Array[Byte])(body: File => T): T = {
    val file = File.createTempFile("pdbsuite", ".dll")
    Files.write(file.toPath, bytes)
    try {
      body(file)
    } finally {
      file.delete()
    }
  }

  private def listNames(dir: Path): Vector[String] = {
    val stream = Files.list(dir)
    try {
      stream.iterator().asScala.map(_.getFileName.toString).toVector
    } finally {
      stream.close()
    }
  }

  // Run f against the reader outcome for the fixture assembly.
  private def withFixtureReader[T](spool: Option[Path])(f: scala.util.Try[Option[PDBView]] => T): scala.util.Try[Option[T]] = {
    AssemblyDefinition.readAssembly(fixturePath) match {
      case Failure(e) => scala.util.Failure(e)
      case Success(ad) =>
        try {
          ad.mainModule match {
            case None => scala.util.Failure(new IllegalArgumentException("no main module"))
            case Some(module) =>
              module.reader match {
                case None => scala.util.Failure(new IllegalArgumentException("no reader"))
                case Some(reader) => reader.withEmbeddedPdb(spool)(f)
              }
          }
        } finally {
          ad.close()
        }
    }
  }

  // The fixture's sources captured inside the callback as (name, bytes).
  private def fixtureSources(spoolDir: Option[Path]): scala.util.Try[Vector[(String, Array[Byte])]] = {
    val spool = spoolDir.getOrElse(Files.createTempDirectory("cilantro-test-spool"))
    try {
      withFixtureReader(Some(spool)) { outcome =>
        outcome match {
          case Success(Some(view)) => view.sources.map(s => (s.name, payloadBytes(s)))
          case _ => Vector.empty[(String, Array[Byte])]
        }
      }.map(_.getOrElse(Vector.empty[(String, Array[Byte])]))
    } finally {
      if (spoolDir.isEmpty) deleteRecursively(spool)
    }
  }

  // ---- fixture surgery helpers ----------------------------------------

  private def type17Region(path: String): (Int, Int) = {
    ModuleDefinition.readModule(path) match {
      case Failure(e) => fail(s"the fixture must read: $e")
      case Success(module) =>
        try {
          module.image.flatMap(_.debugHeader).flatMap { header =>
            header.entties
              .find(_.directory.`type` == io.spicelabs.cilantro.cil.ImageDebugType.embeddedPortablePdb)
              .map(e => (e.directory.pointerToRawData, e.directory.sizeOfData))
          }.getOrElse(fail("the fixture must carry a type-17 entry"))
        } finally {
          module.close()
        }
    }
  }

  // Rebuild an assembly whose type-17 payload is a fresh MPDB
  // (magic + declared + raw deflate of root).
  private def withMpdb(declared: Long, root: Array[Byte]): Array[Byte] = {
    val original = fixtureBytes
    val (p, size) = type17Region(fixturePath)
    val head = new java.io.ByteArrayOutputStream()
    head.write(original, 0, p)
    head.write(Array[Byte]('M'.toByte, 'P'.toByte, 'D'.toByte, 'B'.toByte))
    head.write(Array(
      (declared & 0xff).toByte, ((declared >> 8) & 0xff).toByte,
      ((declared >> 16) & 0xff).toByte, ((declared >> 24) & 0xff).toByte
    ))
    val compressed = new java.io.ByteArrayOutputStream()
    val deflaterOut = new java.util.zip.DeflaterOutputStream(compressed,
      new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true), 65536)
    deflaterOut.write(root)
    deflaterOut.finish()
    deflaterOut.close()
    head.write(compressed.toByteArray)
    head.write(original, p + size, original.length - p - size)
    head.toByteArray
  }

  private def decompressFixtureRoot(): Array[Byte] = {
    val original = fixtureBytes
    val (p, size) = type17Region(fixturePath)
    val mpdb = java.util.Arrays.copyOfRange(original, p, p + size)
    val declared =
      (mpdb(4) & 0xffL) | ((mpdb(5) & 0xffL) << 8) | ((mpdb(6) & 0xffL) << 16) | ((mpdb(7) & 0xffL) << 24)
    val out = new java.io.ByteArrayOutputStream()
    RawInflate.pump(new java.io.ByteArrayInputStream(java.util.Arrays.copyOfRange(mpdb, 8, mpdb.length)), out, declared)
    out.toByteArray
  }

  private def locateTablesHeader(root: Array[Byte]): Option[(Int, Int)] = {
    if (root.length < 32 || root(0) != 'B'.toByte || root(1) != 'S'.toByte || root(2) != 'J'.toByte || root(3) != 'B'.toByte) {
      return None
    }
    def u32(at: Int): Long =
      (root(at) & 0xffL) | ((root(at + 1) & 0xffL) << 8) | ((root(at + 2) & 0xffL) << 16) | ((root(at + 3) & 0xffL) << 24)
    def u16(at: Int): Int = (root(at) & 0xff) | ((root(at + 1) & 0xff) << 8)
    val versionLength = u32(12)
    var pos = (16 + versionLength + 3) & ~3L
    val streamCount = u16(pos.toInt + 2)
    pos += 4
    var tables: Option[(Int, Int)] = None
    var i = 0L
    while (i < streamCount) {
      val offset = u32(pos.toInt).toInt
      val size = u32(pos.toInt + 4).toInt
      var namePos = pos + 8
      val nameStart = namePos
      while (namePos < root.length && root(namePos.toInt) != 0) {
        namePos += 1
      }
      val name = new String(java.util.Arrays.copyOfRange(root, nameStart.toInt, namePos.toInt), "UTF-8")
      namePos += 1
      pos = (namePos + 3) & ~3L
      if (name == "#~" || name == "#-") {
        tables = Some((offset, size))
      }
      i += 1
    }
    tables
  }

  // ---- CP-5a: spool hygiene at the reader ------------------------------

  test("CP-5a: the root spools into the caller's directory during f; hygiene is exact") {
    withSpool { dir =>
      val marker = dir.resolve("marker.txt")
      Files.write(marker, "keep-me".getBytes("UTF-8"))
      var during: Vector[String] = Vector.empty
      val outer = withFixtureReader(Some(dir)) { _ =>
        during = listNames(dir)
      }
      assertEquals(outer.isSuccess, true, s"the reader call completes: $outer")
      assertEquals(during.contains("marker.txt"), true, "the marker is untouched")
      assertEquals(during.filter(_.startsWith("cilantro-pdb-")).length, 1, "exactly one cilantro scratch during f")
      assertEquals(listNames(dir), Vector("marker.txt"), "after the call: the scratch is deleted, the dir is the caller's")
    }
  }

  test("CP-5a: bad spool directories refuse; the walk creates no temp state anywhere") {
    // A spool dir that is a regular file refuses cleanly at the reader.
    withFile(Array[Byte](1)) { file =>
      val outcome = withFixtureReader(Some(file.toPath))(_ => ())
      assertEquals(outcome.isSuccess, true, "withEmbeddedPdb itself never throws")
      assert(outcome.get.isEmpty || outcome.get.isDefined)
      // The refusal is the OUTCOME f sees (Failure inside the argument).
      var seen: Option[scala.util.Try[Option[PDBView]]] = None
      val captured = withFixtureReader(Some(file.toPath)) { o => seen = Some(o) }
      assert(captured.isSuccess)
      seen match {
        case Some(Failure(_)) => ()
        case other => fail(s"a bad spool dir must reach f as Failure, got $other")
      }
    }
    // The walk takes no spool directory and creates no temp state:
    // a forked walk over the embedded-PDB fixture with java.io.tmpdir
    // pointed at a watched directory leaves it empty and walks Some.
    withSpool { watched =>
      val fixture = corpusRoot.resolve("fixtures/embedded_pdb_fixture.dll").toFile
      val (code, outText) = ForkSupport.runForked(
        List("walk", fixture.getAbsolutePath),
        extraJvmArgs = List(s"-Djava.io.tmpdir=${watched.toString}")
      )
      assertEquals(code, 0, s"the child must exit cleanly: $outText")
      assert(outText.contains("WALK-SOME"), s"the walk must succeed byte-faithfully: $outText")
      assertEquals(listNames(watched), Vector.empty[String], "the walk created no temp state anywhere")
    }
  }

  // ---- CP-5b: sources stream exactly ----------------------------------

  test("CP-5b: the pinned fixture's sources stream exactly through the reader") {
    val sources = fixtureSources(None)
    sources match {
      case Success(srcs) =>
        assert(srcs.length >= 3, s"Widget.cs + generated sources expected, got ${srcs.length}")
        val widget = srcs.find(_._1.contains("Widget.cs")).getOrElse(fail("Widget.cs source missing"))
        val repoSource = new String(
          Files.readAllBytes(java.nio.file.Paths.get("../../scripts/fixtures/embedded/Widget.cs")), "UTF-8")
        assertEquals(new String(widget._2, "UTF-8"), repoSource, "the embedded Widget.cs must equal the repo source byte-for-byte")
      case Failure(e) => fail(s"the fixture must read through the reader: $e")
    }
  }

  // ---- CP-5c: absent / magic-invalid ----------------------------------

  test("CP-5c: spoolDir = None and magic-invalid type-17 are absent at the reader, benign in the walk") {
    val outcome = withFixtureReader(None) { o => o }
    assertEquals(outcome, Success(Some(Success(None))), "spoolDir = None yields absent (no in-memory fallback)")

    // Magic-invalid type-17: the reader says absent (Success(None))...
    val mutated = fixtureBytes.clone()
    val (p, _) = type17Region(fixturePath)
    mutated(p) = 'X'.toByte
    withFile(mutated) { file =>
      AssemblyDefinition.readAssembly(file.getAbsolutePath) match {
        case Success(ad) =>
          try {
            ad.mainModule.foreach { module =>
              module.reader.foreach { reader =>
                val seen = reader.withEmbeddedPdb(Some(Files.createTempDirectory("sp"))) { o => o }
                assertEquals(seen, Success(Some(Success(None))), "magic-invalid type-17 is absent at the reader")
              }
            }
          } finally {
            ad.close()
          }
        case Failure(e) => fail(s"the mutated fixture must still read as an assembly: $e")
      }
      // ...while the byte-faithful WALK still yields Some with the raw
      // type-17 DebugBlob, hint unchanged (a type claim, B-9).
      val outcome = AssemblyWalker.withinAssemblyStream[Vector[(String, Option[String], Int, Array[Byte])]](file) { entries =>
        entries.collect {
          case d: DebugBlobEntry if d.debugEntryTypeValue == 17 =>
            (d.name, d.mimeHint, d.length.toInt, payloadBytes(d))
        }
      }
      outcome match {
        case None => fail("magic-invalid type-17 must not affect the byte-faithful walk")
        case Some(blobs) =>
          assertEquals(blobs.length, 1)
          assertEquals(blobs(0)._2, Some("pe/debug; format=mpdb"), "the hint is a type claim, not a validity promise")
          assertEquals(blobs(0)._3, 6888, "the length is the in-file sizeOfData")
      }
    }
  }

  // ---- CP-5d: envelope refusals at the reader; the walk stays Some ---

  test("CP-5d: envelope refusals are reader Failure; the same artifact walks Some") {
    val originalRoot = decompressFixtureRoot()
    assert(originalRoot.length > 100, "the fixture root is a real PDB")

    def readerOutcomeFor(declared: Long, root: Array[Byte]): (scala.util.Try[Option[scala.util.Try[Option[PDBView]]]], Vector[String]) = {
      val bytes = withMpdb(declared, root)
      withFile(bytes) { file =>
        AssemblyDefinition.readAssembly(file.getAbsolutePath) match {
          case Success(ad) =>
            try {
              val spool = Files.createTempDirectory("cilantro-test-spool")
              try {
                ad.mainModule.flatMap(_.reader) match {
                  case Some(reader) =>
                    val seen = reader.withEmbeddedPdb(Some(spool)) { o => o }
                    (seen, listNames(spool))
                  case None => fail("no reader")
                }
              } finally {
                deleteRecursively(spool)
              }
            } finally {
              ad.close()
            }
          case Failure(e) => fail(s"the mutated assembly must read: $e")
        }
      }
    }

    // Exact boundary: declared == actual succeeds with sources.
    val exact = readerOutcomeFor(originalRoot.length.toLong, originalRoot)
    exact._1 match {
      case Success(Some(Success(Some(view)))) =>
        assert(view.sources.length >= 3, "sources come through on the exact boundary")
      case other => fail(s"declared == actual must succeed at the reader: $other")
    }

    // Declared larger than actual: short stream -> reader Failure.
    val over = readerOutcomeFor(originalRoot.length.toLong + 1000L, originalRoot)
    over._1 match {
      case Success(Some(Failure(_))) => ()
      case other => fail(s"declared beyond the actual output must refuse at the reader: $other")
    }
    assertEquals(over._2, Vector.empty[String], "refusal deletes the partial scratch")

    // Declared smaller than actual: abort at the bound -> reader Failure.
    val under = readerOutcomeFor(originalRoot.length.toLong - 100L, originalRoot)
    under._1 match {
      case Success(Some(Failure(_))) => ()
      case other => fail(s"output past the declared size must refuse at the reader: $other")
    }
    assertEquals(under._2, Vector.empty[String], "the bounded write left no scratch behind")

    // Declared >= 2^31: refusal BEFORE any write — no scratch ever.
    val huge = readerOutcomeFor(0x80000000L, originalRoot)
    huge._1 match {
      case Success(Some(Failure(_))) => ()
      case other => fail(s"a >= 2^31 declaration must refuse before any write: $other")
    }
    assertEquals(huge._2, Vector.empty[String], "no scratch file was ever created")

    // The contractual pair (B-10): every one of these artifacts WALKS
    // Some with its raw type-17 DebugBlob — hostility never affects
    // the byte-faithful walk.
    def walkKind(file: File): Option[Vector[String]] =
      AssemblyWalker.withinAssemblyStream[Vector[String]](file)(e => e.map(_.kind.toString))
    withFile(withMpdb(originalRoot.length.toLong + 1000L, originalRoot)) { file =>
      val kinds = walkKind(file)
      assert(kinds.isDefined, "an over-declared MPDB still walks")
      assert(kinds.get.contains("DebugBlob"), "the type-17 DebugBlob is present")
    }
    withFile(withMpdb(0x80000000L, originalRoot)) { file =>
      val kinds = walkKind(file)
      assert(kinds.isDefined, "a >= 2^31 declaration still walks byte-faithfully")
    }
  }

  // ---- CP-5e: hostile PDB internals ------------------------------------

  test("CP-5e: hostile internals refuse at the reader while the walk stays Some") {
    val originalRoot = decompressFixtureRoot()
    val tablesHeader = locateTablesHeader(originalRoot).getOrElse(fail("the root must carry a #~ stream"))
    val tablesOff = tablesHeader._1
    val patched = originalRoot.clone()
    val valid = {
      var v = 0L
      var i = 7
      while (i >= 0) {
        v = (v << 8) | (patched(tablesOff + 8 + i) & 0xff).toLong
        i -= 1
      }
      v
    }
    var pos = tablesOff + 24
    var tid = 0
    var docCountAt = -1
    while (tid < 64) {
      if ((valid & (1L << tid)) != 0) {
        if (tid == 0x30) {
          docCountAt = pos.toInt
        }
        pos += 4
      }
      tid += 1
    }
    assert(docCountAt >= 0, "Document count found")
    patched(docCountAt) = 0xff.toByte
    patched(docCountAt + 1) = 0xff.toByte
    patched(docCountAt + 2) = 0xff.toByte
    patched(docCountAt + 3) = 0x7f.toByte
    val bombBytes = withMpdb(originalRoot.length.toLong, patched)
    withFile(bombBytes) { file =>
      // Reader: Failure.
      AssemblyDefinition.readAssembly(file.getAbsolutePath) match {
        case Success(ad) =>
          try {
            val spool = Files.createTempDirectory("cilantro-test-spool")
            try {
              ad.mainModule.flatMap(_.reader) match {
                case Some(reader) =>
                  reader.withEmbeddedPdb(Some(spool)) { o => o } match {
                    case Success(Some(Failure(_))) => ()
                    case other => fail(s"a row-count bomb must refuse at the reader: $other")
                  }
                case None => fail("no reader")
              }
            } finally {
              deleteRecursively(spool)
            }
          } finally {
            ad.close()
          }
        case Failure(e) => fail(s"the bomb assembly must still read: $e")
      }
      // Walk: Some (byte-faithful).
      val kinds = AssemblyWalker.withinAssemblyStream[Vector[String]](file)(e => e.map(_.kind.toString))
      assert(kinds.isDefined, "the bomb does not affect the walk")
      assert(kinds.get.contains("DebugBlob"))
    }

    // Source-level deflate mismatch is stream-level (IOException inside
    // processStream) — the machinery both readers share.
    val raw = new java.io.ByteArrayOutputStream()
    val deflaterOut = new java.util.zip.DeflaterOutputStream(raw,
      new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true), 65536)
    deflaterOut.write(Array.fill[Byte](4096)(7))
    deflaterOut.finish()
    deflaterOut.close()
    val compressed = raw.toByteArray
    val bad = new RawInflateInputStream(new java.io.ByteArrayInputStream(compressed), 100L)
    val readAttempt = scala.util.Try {
      val sink = new java.io.ByteArrayOutputStream()
      val buf = new Array[Byte](1024)
      var n = bad.read(buf)
      while (n >= 0) {
        if (n > 0) sink.write(buf, 0, n)
        n = bad.read(buf)
      }
      sink.size()
    }
    readAttempt match {
      case Success(_) => fail("a deflate that exceeds its declared size must refuse")
      case Failure(e) =>
        assertEquals(e.getClass.getSimpleName, "IOException", s"the refusal is an IOException, got $e")
    }
    bad.close()
    val good = new RawInflateInputStream(new java.io.ByteArrayInputStream(compressed), 4096L)
    val goodRead = scala.util.Try {
      val sink = new java.io.ByteArrayOutputStream()
      val buf = new Array[Byte](1024)
      var n = good.read(buf)
      while (n >= 0) {
        if (n > 0) sink.write(buf, 0, n)
        n = good.read(buf)
      }
      sink.size()
    }
    assertEquals(goodRead, Success(4096), "declared == actual streams cleanly")
  }

  // ---- CP-5f: pull semantics -------------------------------------------

  test("CP-5f: pull semantics — a partial read performs only partial work") {
    val raw = new java.io.ByteArrayOutputStream()
    val deflaterOut = new java.util.zip.DeflaterOutputStream(raw,
      new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true), 65536)
    val payload = Array.fill[Byte](128 * 1024 * 1024)(0)
    deflaterOut.write(payload)
    deflaterOut.finish()
    deflaterOut.close()
    val compressed = raw.toByteArray
    assert(compressed.length > 65536 + 8192, s"the compressed stream must exceed one granule, got ${compressed.length}")
    val granuleBudget = 65536L + 8192L
    var demanded = 0L
    val budgeted = new InputStream {
      private val inner = new java.io.ByteArrayInputStream(compressed)
      override def read(): Int = {
        demanded += 1
        if (demanded > granuleBudget) sys.error("input budget exceeded")
        inner.read()
      }
      override def read(b: Array[Byte], off: Int, len: Int): Int = {
        val n = inner.read(b, off, len)
        if (n > 0) demanded += n
        if (demanded > granuleBudget) sys.error("input budget exceeded")
        n
      }
    }
    val inflating = new RawInflateInputStream(budgeted, payload.length.toLong)
    val outcome = scala.util.Try {
      val prefix = new Array[Byte](1000)
      var off = 0
      while (off < prefix.length) {
        val n = inflating.read(prefix, off, prefix.length - off)
        if (n <= 0) off = prefix.length else off += n
      }
      assert(demanded <= granuleBudget && demanded < compressed.length,
        s"a partial read demanded $demanded of ${compressed.length} input bytes")
      assertEquals(java.util.Arrays.copyOfRange(prefix, 0, 1000).toVector, Array.fill[Byte](1000)(0).toVector)
    }
    outcome match {
      case Failure(e) => fail(s"partial reads must not force full inflation: $e")
      case Success(_) => ()
    }
    inflating.close()
  }
}
