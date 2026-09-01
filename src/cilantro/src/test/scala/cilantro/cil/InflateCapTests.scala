// InflateCapTests — H3-xx: rawInflate output caps.
//
// Why these tests exist:
//   Plan 2026_09_01 (phase-03) caps the inflate output DURING
//   inflation: a hostile deflate stream must not write unboundedly and
//   compare against the declared size afterwards (and embedded sources
//   have no declared size at all). Failing the cap returns None — the
//   established "not present" shape for classification/absence, never
//   an exception (principal review PE-3).
//
// Theory of the test:
//   - The unit tests exercise rawInflate directly through the
//     private[cilantro] test seam with explicit caps, pinning the
//     boundary (cap == output succeeds, cap-1 fails) and the
//     declared-size/ceiling mechanics.
//   - The end-to-end tests drive the embedded-PDB path with a
//     synthetic MPDB blob (type-17 debug entry pointing at the blob in
//     the section) so the public reader accessor is covered.
//   - The pinned embedded_pdb_fixture.dll regression proves the real
//     Widget.cs extraction still works under the new caps.
//
// Requirements traced:
//   plans/2026_09_01_cilantro_hardening_and_dotnet_probe/phase-03.md
//   H3-01..H3-06 (suggestion cilantro #3; ADR-0013).
//
// LLM notes:
//   - Deflater(true)/Inflater(true) is the raw (zlib-headerless)
//     stream the embedded-PDB format uses.
//   - MPDB blob layout: "MPDB" + u32 uncompressed size + raw deflate
//     of the BSJB metadata root.
//   - The embedded-source inflate cap is min(declared format, 256 MiB);
//     the MPDB cap is the declared uncompressed size (already bounded
//     by the 256 MiB pre-check).

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import io.spicelabs.cilantro.{MetadataReader, ModuleDefinition, EmbeddedPdb}
import io.spicelabs.cilantro.metadata.CorpusProvisioner
import java.io.FileOutputStream

class InflateCapTests extends munit.FunSuite {

  private def corpusRoot = CorpusProvisioner.ensureCorpus()

  private def i4(v: Int): Array[Byte] =
    Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte, ((v >> 16) & 0xff).toByte, ((v >> 24) & 0xff).toByte)

  private def zero(n: Int): Array[Byte] = Array.ofDim[Byte](n)

  private def deflateRaw(bytes: Array[Byte]): Array[Byte] = {
    val deflater = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
    try {
      deflater.setInput(bytes)
      deflater.finish()
      val out = new java.io.ByteArrayOutputStream()
      val buf = new Array[Byte](4096)
      while (!deflater.finished()) {
        val n = deflater.deflate(buf)
        if (n > 0) {
          out.write(buf, 0, n)
        }
      }
      out.toByteArray
    } finally {
      deflater.end()
    }
  }

  private def withReader(body: MetadataReader => Unit): Unit = {
    val module = ModuleDefinition.readModule("../../test-files/smoke/Smoke.dll").getOrElse(fail("Smoke.dll must read"))
    try {
      body(module.reader.getOrElse(fail("module must carry a reader")))
    } finally {
      module.close()
    }
  }

  // A synthetic type-17 debug entry whose blob (the MPDB bytes) lives at
  // the builder's managed-resource slot. The section starts at
  // bodyOffset = 0x40 + 4 + 20 + 0xe0 + 40 * sectionCount (the builder's
  // own formula, one section), and the managed slot sits at section
  // offset 0x700; the debug entry's pointerToRawData is the resulting
  // raw file offset. The guard asserts the pointer really lands on the
  // MPDB magic so a builder layout change fails loudly, not silently.
  private def mpdbFile(mpdb: Array[Byte]): java.io.File = {
    val bodyOffset = 0x40 + 4 + 20 + 0xe0 + 40
    val blobRaw = bodyOffset + 0x700
    val entry = zero(12) ++ i4(17) ++ i4(mpdb.length) ++ i4(0) ++ i4(blobRaw)
    val bytes = new MinimalPeBuilder(
      debugDirectory = Some(entry),
      managedResourceBlob = Some(mpdb)
    ).build()
    assertEquals(
      bytes.slice(blobRaw, blobRaw + 4).toVector,
      Vector('M'.toByte, 'P'.toByte, 'D'.toByte, 'B'.toByte),
      "the debug entry pointer must resolve to the MPDB blob"
    )
    val file = java.io.File.createTempFile("mpdb", ".dll")
    val out = new FileOutputStream(file)
    out.write(bytes)
    out.close()
    file
  }

  private def readEmbeddedPdb(path: String): scala.util.Try[Option[EmbeddedPdb]] = {
    ModuleDefinition.readModule(path).flatMap { module =>
      scala.util.Try {
        module.read(Option.empty[EmbeddedPdb], "embedded-pdb", (_, reader: MetadataReader) => reader.readEmbeddedPortablePdb())
      }
    }
  }

  test("H3-02: inflation output exactly at the cap succeeds; one less fails") {
    withReader { reader =>
      val payload = deflateRaw(zero(1024))
      reader.rawInflate(payload, 1024L) match {
        case Some(out) => assertEquals(out.length, 1024)
        case None => fail("exact-cap inflation must succeed")
      }
      assertEquals(reader.rawInflate(payload, 1023L), None, "output past the cap must fail")
    }
  }

  test("H3-01: inflation that exceeds the declared size fails during inflation") {
    withReader { reader =>
      val payload = deflateRaw(zero(64 * 1024))
      assertEquals(reader.rawInflate(payload, 1024L), None, "the declared size (1 KiB) caps a 64 KiB expansion")
    }
  }

  test("H3-01b: end-to-end — an MPDB declaring 1 KiB with a 64 KiB expansion yields None") {
    val mpdb = "MPDB".getBytes("UTF-8") ++ i4(1024) ++ deflateRaw(zero(64 * 1024))
    val file = mpdbFile(mpdb)
    try {
      readEmbeddedPdb(file.getAbsolutePath) match {
        case Success(result) => assertEquals(result, None, "inflation past the declared size must be rejected")
        case Failure(t) => fail(s"the module must read: $t")
      }
    } finally {
      file.delete()
    }
  }

  test("H3-03: a generous absolute ceiling still inflates legitimate data") {
    withReader { reader =>
      val payload = deflateRaw(zero(300 * 1024))
      reader.rawInflate(payload, 256L * 1024 * 1024) match {
        case Some(out) => assertEquals(out.length, 300 * 1024)
        case None => fail("300 KiB under a 256 MiB ceiling must inflate")
      }
    }
  }

  test("H3-04: an MPDB root declaring 2 GiB uncompressed yields None end-to-end") {
    val mpdb = "MPDB".getBytes("UTF-8") ++ i4(0x7fffffff) ++ deflateRaw(zero(16))
    val file = mpdbFile(mpdb)
    try {
      readEmbeddedPdb(file.getAbsolutePath) match {
        case Success(result) => assertEquals(result, None, "a 2 GiB declared root must be rejected before inflation")
        case Failure(t) => fail(s"the module must read: $t")
      }
    } finally {
      file.delete()
    }
  }

  test("H3-05: bounded round-trip — exact payload when cap >= size, None when cap < size") {
    withReader { reader =>
      val rng = new scala.util.Random(42)
      val sizes = List(1, 100, 64 * 1024, 1024 * 1024)
      sizes.foreach { size =>
        val data = Array.ofDim[Byte](size)
        rng.nextBytes(data)
        val compressed = deflateRaw(data)
        reader.rawInflate(compressed, size.toLong) match {
          case Some(out) => assert(java.util.Arrays.equals(out, data), s"cap == size must round-trip at size $size")
          case None => fail(s"cap == size must round-trip at size $size")
        }
        assertEquals(reader.rawInflate(compressed, (size - 1).toLong), None, s"cap < size must fail at size $size")
      }
    }
  }

  test("H3-06: the pinned embedded-PDB fixture still extracts its sources (regression)") {
    readEmbeddedPdb(corpusRoot.resolve("fixtures/embedded_pdb_fixture.dll").toString) match {
      case Success(Some(pdb)) =>
        assertEquals(pdb.sources.length, 3, "Widget.cs + two generated sources")
        val widget = pdb.sources.find(_.name.endsWith("Widget.cs")).getOrElse(fail("Widget.cs missing"))
        val repoSource = new String(
          java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../../scripts/fixtures/embedded/Widget.cs")), "UTF-8")
        assertEquals(new String(widget.bytes, "UTF-8"), repoSource, "the embedded Widget.cs must equal the repo source")
      case Success(None) => fail("the fixture must carry an embedded PDB")
      case Failure(t) => fail(s"the fixture must read: $t")
    }
  }
}
