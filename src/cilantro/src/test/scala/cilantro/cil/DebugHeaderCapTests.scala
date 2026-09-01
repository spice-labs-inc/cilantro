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
//   - The entry-count boundary pins 1024 accepted / 1025 rejected
//     (the cap fires before Array.ofDim, so the rejection is a clean
//     DataFormatException, not an OOM).
//   - The sizeOfData bomb (2 GiB claim) and the pointer bomb
//     (pointer + size past EOF) fail with DataFormatException.
//   - The exact-extent boundary (pointer + size == fileSize) succeeds
//     and returns the exact blob bytes.
//   - The pinned embedded-PDB fixture regression pins the real-world
//     entries (types 2/19/17, sizes 47/39/6888).
//
// Requirements traced:
//   plans/2026_09_01_cilantro_hardening_and_dotnet_probe/phase-06.md
//   H6-01..H6-05 (suggestion cilantro #6; ADR-0013).
//
// LLM notes:
//   - The 28-byte entry layout: characteristics(4) timeDataStamp(4)
//     majorVersion(2) minorVersion(2) type(4) sizeOfData(4)
//     addressOfRawData(4) pointerToRawData(4).
//   - pointerToRawData is a RAW file offset (not an RVA).
//   - The caps: MaxDebugEntries = 1024, MaxDebugDataSize = 256 MiB
//     (shared with AssemblyReader via ImageReader.MaxDebugDataSize).

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

  test("H6-01: the entry count cap accepts 1024 and rejects 1025 before allocating") {
    // Entries with type 2 (CodeView) and zeroed data pointers: each is
    // skipped as empty, so the count is the only thing being tested.
    def entries(n: Int): Array[Byte] = {
      val out = new Array[Byte](n * 28)
      var i = 0
      while (i < n) {
        System.arraycopy(zero(12) ++ i4(2) ++ i4(0) ++ i4(0) ++ i4(0), 0, out, i * 28, 28)
        i += 1
      }
      out
    }
    withPe(entries(1024)) { path =>
      assert(ModuleDefinition.readModule(path).isSuccess, "1024 debug entries is the cap and must parse")
    }
    withPe(entries(1025)) { path =>
      assert(ModuleDefinition.readModule(path).isFailure, "1025 debug entries must fail cleanly")
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

  test("H6-04: pointer + size exactly at EOF reads the exact blob") {
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
          val entries = module.image.flatMap(_.debugHeader).map(_.entties).getOrElse(fail("a debug header must parse"))
          assertEquals(entries.length, 1)
          assertEquals(entries(0).data.toVector, blob.toVector, "the exact blob bytes must round-trip")
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
        assertEquals(withData(0).data.length, 47)
        assertEquals(withData(1).directory.`type`.value, 19) // PdbChecksum
        assertEquals(withData(1).data.length, 39)
        assertEquals(withData(2).directory.`type`.value, 17) // EmbeddedPortablePdb
        assertEquals(withData(2).data.length, 6888)
      case Failure(e) => fail(s"the fixture must read: $e")
    }
  }
}
