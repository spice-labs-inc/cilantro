// BinaryStreamReaderSafetyTests — H1-xx: readBytes bounds checking.
//
// Why these tests exist:
//   Plan 2026_09_01 (phase-01) hardens BinaryStreamReader.readBytes so
//   the length is validated against the remaining buffer BEFORE the
//   Array.ofDim allocation: a hostile length claimed from a tiny file
//   must fail with DataFormatException, never OOM, and a negative
//   length must fail the same way instead of NegativeArraySizeException.
//   Every consumer of readBytes inherits the guard (heaps, debug data,
//   certificates, win32 blobs, managed resources).
//
// Theory of the test:
//   A BinaryStreamReader maps a temp file; the position controls the
//   remaining() budget. The boundary cases pin the exact comparison:
//   length == remaining succeeds, remaining + 1 fails. The bomb cases
//   would OOM (or throw NegativeArraySizeException) before the fix, so
//   their passing proves the check precedes the allocation.
//
// Requirements traced:
//   plans/2026_09_01_cilantro_hardening_and_dotnet_probe/phase-01.md
//   H1-01..H1-05 (suggestion cilantro #1; ADR-0013 failure contract).
//
// LLM notes:
//   - readBytes failure is DataFormatException (ADR-0013): hostile
//     format input, not NIO BufferUnderflowException and not a
//     NegativeArraySizeException.
//   - H1-05 is end-to-end through ModuleDefinition.readModule so the
//     guard is proven at the public entry point.

package io.spicelabs.cilantro.PE

import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.DataFormatException
import io.spicelabs.cilantro.cil.MinimalPeBuilder

class BinaryStreamReaderSafetyTests extends munit.FunSuite {

  private def withReader(bytes: Array[Byte])(body: BinaryStreamReader => Unit): Unit = {
    val file = java.io.File.createTempFile("bsr", ".bin")
    val out = new FileOutputStream(file)
    out.write(bytes)
    out.close()
    try {
      body(BinaryStreamReader(new FileInputStream(file)))
    } finally {
      file.delete()
    }
  }

  private def sixteen(): Array[Byte] = (0 until 16).map(_.toByte).toArray

  test("H1-01: a length exactly equal to the remaining bytes succeeds") {
    withReader(sixteen()) { reader =>
      reader.moveTo(8)
      val got = reader.readBytes(8)
      assertEquals(got.toVector, (8 until 16).map(_.toByte).toVector)
    }
  }

  test("H1-01b: a zero length succeeds and returns an empty array") {
    withReader(sixteen()) { reader =>
      assertEquals(reader.readBytes(0).length, 0)
    }
  }

  test("H1-02: one past the remaining bytes fails with DataFormatException") {
    withReader(sixteen()) { reader =>
      reader.moveTo(9)
      intercept[DataFormatException] {
        reader.readBytes(8)
      }
    }
  }

  test("H1-03: a 2 GiB claim on a 16-byte file fails before allocating") {
    withReader(sixteen()) { reader =>
      intercept[DataFormatException] {
        reader.readBytes(0x7fffffff)
      }
    }
  }

  test("H1-04: a negative length fails with DataFormatException") {
    withReader(sixteen()) { reader =>
      intercept[DataFormatException] {
        reader.readBytes(-1)
      }
    }
  }

  test("H1-05: a debug entry declaring 2 GiB fails the module read cleanly") {
    def i4(v: Int): Array[Byte] =
      Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte, ((v >> 16) & 0xff).toByte, ((v >> 24) & 0xff).toByte)
    def zero(n: Int): Array[Byte] = Array.ofDim[Byte](n)
    // 28-byte IMAGE_DEBUG_DIRECTORY: sizeOfData at offset 16, pointer at 24.
    val dir = zero(12) ++ i4(2) ++ i4(0x7fffffff) ++ i4(0) ++ i4(4)
    val file = java.io.File.createTempFile("bsr", ".dll")
    val out = new FileOutputStream(file)
    out.write(new MinimalPeBuilder(debugDirectory = Some(dir)).build())
    out.close()
    try {
      assert(
        io.spicelabs.cilantro.ModuleDefinition.readModule(file.getAbsolutePath).isFailure,
        "a 2 GiB debug-data claim must fail the read cleanly"
      )
    } finally {
      file.delete()
    }
  }
}
