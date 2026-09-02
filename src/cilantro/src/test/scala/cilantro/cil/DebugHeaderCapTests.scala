// DebugHeaderCapTests — H6-xx: debug-directory entry and size caps.
//
// Why these tests exist:
//   Plan 2026_09_01 (phase-06) closes two holes in
//   ImageReader.readDebugHeader: the entry count is derived from the
//   debug data-directory size with NO cap (a hostile 0xFFFFFFFF size
//   claims ~153M entries -> a ~1.2 GiB array allocation BEFORE any
//   per-entry check), and each entry's sizeOfData / pointerToRawData
//   are not validated against the file extent (a hostile pair fails
//   with an NIO IllegalArgumentException today, or reads past EOF).
//
// Theory of the test:
//   - The entry-count boundary pins 1,000,000 accepted / 1,000,001
//     rejected (plan 2026_09_02, D-10 — the guard is 1,000,000; the
//     cap fires before Array.ofDim, so the rejection is a clean
//     DataFormatException, not an OOM).
//   - The sizeOfData bomb (2 GiB claim) and the pointer bomb
//     (pointer + size past EOF) fail with DataFormatException.
//   - The exact-extent boundary (pointer + size == fileSize) succeeds
//     and returns the exact blob bytes (re-pinned at the slice path
//     in plan 2026_09_02 phase D when the open-time data copy is
//     removed).
//   - The pinned embedded-PDB fixture regression pins the real-world
//     entries (types 2/19/17, sizes 47/39/6888).
//
// Requirements traced:
//   plans/2026_09_01_cilantro_hardening_and_dotnet_probe/phase-06.md
//   H6-01..H6-05 (suggestion cilantro #6; ADR-0013); plan
//   2026_09_02_cilantro_streaming_assembly_walk (D-9/D-10).
//
// LLM notes:
//   - The 28-byte entry layout: characteristics(4) timeDataStamp(4)
//     majorVersion(2) minorVersion(2) type(4) sizeOfData(4)
//     addressOfRawData(4) pointerToRawData(4).
//   - pointerToRawData is a RAW file offset (not an RVA).
//   - The caps: MaxDebugEntries = 1,000,000 (D-10). MaxDebugDataSize
//     (256 MiB) bounds the open-time data copy until phase D removes
//     the copy (D-9: extent-only slices).

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import io.spicelabs.cilantro.metadata.CorpusProvisioner
import io.spicelabs.cilantro.ModuleDefinition
import java.io.FileOutputStream

class DebugHeaderCapTests extends munit.FunSuite {

  private def corpusRoot = CorpusProvisioner.ensureCorpus()

  private def i4(v: Int): Array[Byte] =
    Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte, ((v >> 16) & 0xff).toByte, ((v >> 24) & 0xff).toByte)

  private def zero(n: Int): Array[Byte] = Array.ofDim[Byte](n)

  // 28-byte IMAGE_DEBUG_DIRECTORY entry.
  private def debugEntry(entryType: Int, size: Int, pointer: Int): Array[Byte] =
    zero(12) ++ i4(entryType) ++ i4(size) ++ i4(0) ++ i4(pointer)

  private def withPe(debugDir: Array[Byte], managedBlob: Option[Array[Byte]] = None)(body: String => Unit): Unit = {
    val file = java.io.File.createTempFile("dbg", ".dll")
    val out = new FileOutputStream(file)
    out.write(new MinimalPeBuilder(
      debugDirectory = Some(debugDir),
      managedResourceBlob = managedBlob
    ).build())
    out.close()
    try {
      body(file.getAbsolutePath)
    } finally {
      file.delete()
    }
  }

  test("H6-01: the entry count cap accepts 1,000,000 and rejects 1,000,001 before allocating") {
    // Plan 2026_09_02 phase B (D-10): the debug entry-count guard is
    // 1,000,000 (was 1024). Entries with type 2 (CodeView) and zeroed
    // data pointers are skipped as empty, so the count is the only
    // thing being tested.
    def entries(n: Int): Array[Byte] = {
      val out = new Array[Byte](n * 28)
      var i = 0
      while (i < n) {
        System.arraycopy(zero(12) ++ i4(2) ++ i4(0) ++ i4(0) ++ i4(0), 0, out, i * 28, 28)
        i += 1
      }
      out
    }
    withPe(entries(1000000)) { path =>
      assert(ModuleDefinition.readModule(path).isSuccess, "1,000,000 debug entries is the cap and must parse")
    }
    withPe(entries(1000001)) { path =>
      assert(ModuleDefinition.readModule(path).isFailure, "1,000,001 debug entries must fail cleanly")
    }
  }

  test("H6-02: a 2 GiB sizeOfData claim fails cleanly, no OOM") {
    val dir = debugEntry(2, 0x7fffffff, 4)
    withPe(dir) { path =>
      assert(ModuleDefinition.readModule(path).isFailure, "a 2 GiB debug-data claim must fail cleanly")
    }
  }

  test("H6-03: a pointer whose data runs past EOF fails cleanly") {
    val dir = debugEntry(2, 16, 0x7fffffff)
    withPe(dir) { path =>
      assert(ModuleDefinition.readModule(path).isFailure, "an out-of-file debug data range must fail cleanly")
    }
  }

  test("H6-04: pointer + size exactly at EOF exposes the exact blob at the slice path") {
    // Plan 2026_09_02 phase D (D-9): the header walk validates the
    // declaration and never copies the payload; the exact-extent blob
    // is read through readDebugEntryData's slice payload.
    val blob = Array[Byte](1, 2, 3, 4)
    // Two-pass: the file layout is fixed by the builder, so build once
    // to learn the length, then point the entry at the blob's raw
    // offset (the managed-resource slot is the LAST content block, so
    // blobRaw + blob.length == fileSize).
    val probe = new MinimalPeBuilder(
      debugDirectory = Some(debugEntry(2, 0, 0)),
      managedResourceBlob = Some(blob)
    ).build()
    val fileSize = probe.length
    val blobRaw = fileSize - blob.length
    val dir = debugEntry(2, blob.length, blobRaw)
    withPe(dir, Some(blob)) { path =>
      ModuleDefinition.readModule(path) match {
        case Success(module) =>
          val headerEntries = module.image.flatMap(_.debugHeader).map(_.entties).getOrElse(fail("a debug header must parse"))
          assertEquals(headerEntries.length, 1)
          assertEquals(headerEntries(0).directory.sizeOfData, blob.length, "the declaration is intact")
          val payloadOutcome: scala.util.Try[scala.collection.mutable.ArrayBuffer[Vector[Byte]]] = scala.util.Try {
            module.read(Vector.empty[io.spicelabs.cilantro.DebugEntryData], (_, reader: io.spicelabs.cilantro.MetadataReader) => {
              reader.readDebugEntryData().map(e => e.processStream(in => in.readAllBytes()).toVector)
            })
          }
          payloadOutcome match {
            case scala.util.Success(payloads) =>
              assertEquals(payloads.toVector, Vector(blob.toVector), "the exact blob bytes must round-trip through the slice path")
            case scala.util.Failure(t) => fail(s"slice read failed: $t")
          }
        case Failure(e) => fail(s"pointer + size == fileSize must parse: $e")
      }
    }
  }

  test("H6-05: the pinned embedded-PDB fixture's debug entries are unchanged (regression)") {
    ModuleDefinition.readModule(corpusRoot.resolve("fixtures/embedded_pdb_fixture.dll").toString) match {
      case Success(module) =>
        val entries = module.image.flatMap(_.debugHeader).map(_.entties).getOrElse(fail("the fixture must have a debug header"))
        val withData = entries.filter(e => e.directory.sizeOfData > 0)
        assertEquals(withData.length, 3)
        assertEquals(withData(0).directory.`type`.value, 2) // CodeView
        assertEquals(withData(0).directory.sizeOfData, 47, "the codeview declaration")
        assertEquals(withData(1).directory.`type`.value, 19) // PdbChecksum
        assertEquals(withData(1).directory.sizeOfData, 39, "the pdbchecksum declaration")
        assertEquals(withData(2).directory.`type`.value, 17) // EmbeddedPortablePdb
        assertEquals(withData(2).directory.sizeOfData, 6888, "the embedded-PDB declaration")
        // D-9: the open-time copy is gone; the data is read through the
        // slice path with the same pinned lengths.
        val payloadOutcome: scala.util.Try[scala.collection.mutable.ArrayBuffer[(Int, Int)]] = scala.util.Try {
          module.read(Vector.empty[io.spicelabs.cilantro.DebugEntryData], (_, reader: io.spicelabs.cilantro.MetadataReader) => {
            reader.readDebugEntryData().map(e => (e.entryType, e.processStream(in => in.readAllBytes()).length))
          })
        }
        payloadOutcome match {
          case scala.util.Success(payloads) =>
            assertEquals(payloads.toVector.sortBy(_._1), Vector((2, 47), (17, 6888), (19, 39)))
          case scala.util.Failure(t) => fail(s"slice read failed: $t")
        }
      case Failure(e) => fail(s"the fixture must read: $e")
    }
  }
}
