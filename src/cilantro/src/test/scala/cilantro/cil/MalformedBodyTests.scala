// MalformedBodyTests — C2-07.
//
// Why this test exists:
//   The decoder's contract is that every malformed input is a clean
//   Failure, never a throw and never a partial garbage body. Corrupt
//   corpus fixtures and hostile inputs exercise exactly these paths, so
//   the failure modes are pinned before any corpus file can reach the
//   decoder: truncated bodies, truncated operands, bad opcodes, invalid
//   header bits, negative/oversized switch counts and branch targets that
//   do not land on instructions.
//
// Theory of the test:
//   Each hand-built malformed byte sequence is decoded; the result must be
//   Failure (asserted via pattern match, so a thrown exception would fail
//   the test). The decode contract is Try-based: isFailure and no escape.

package io.spicelabs.cilantro.cil

import scala.util.{Failure, Success}

class MalformedBodyTests extends munit.FunSuite {

  private def assertFails(bytes: Array[Byte]): Unit = {
    CodeReader.readBody(bytes) match {
      case Success(body) => fail(s"malformed body decoded: ${body.instructions.mkString("; ")}")
      case Failure(_) => ()
    }
  }

  test("C2-07: empty input is a Failure") {
    assertFails(Array.emptyByteArray)
  }

  test("C2-07: header-only input is a Failure") {
    assertFails(Array(0x2a.toByte)) // looks like an opcode, not a header
  }

  test("C2-07: invalid header format bits (0x0, 0x1) are Failures") {
    assertFails(Array(0x00.toByte))
    assertFails(Array(0x01.toByte))
  }

  test("C2-07: truncated fat header is a Failure") {
    assertFails(Array(0x03.toByte, 0x10, 0x00, 0x08, 0x00)) // 5 of 12 bytes
  }

  test("C2-07: truncated opcode is a Failure") {
    // Tiny body claiming 1 code byte but containing none.
    assertFails(Array(0x02 | (1 << 2)).map(_.toByte))
  }

  test("C2-07: missing second byte of a 0xfe-prefixed opcode is a Failure") {
    // Tiny body with one 0xfe byte and nothing after.
    assertFails(Array(0x02 | (1 << 2), 0xfe.toByte).map(_.toByte))
  }

  test("C2-07: unknown opcodes are Failures") {
    assertFails(Array(0x02 | (1 << 2), 0xff.toByte).map(_.toByte))
    assertFails(Array(0x02 | (2 << 2), 0xfe.toByte, 0x08.toByte).map(_.toByte))
  }

  test("C2-07: truncated inline operand is a Failure") {
    // Tiny body: ldc.i4 needs 4 operand bytes, only 2 present.
    assertFails(
      BodyBuilder.tiny(Array(0x20.toByte, 0x01, 0x02))
    )
  }

  test("C2-07: negative switch count is a Failure") {
    assertFails(
      BodyBuilder.tiny(BodyBuilder.concat(Seq(Array(0x45.toByte), BodyBuilder.i4(-1))))
    )
  }

  test("C2-07: switch table overflowing the code stream is a Failure") {
    // switch claims 10 targets but the body ends after the count.
    assertFails(
      BodyBuilder.tiny(BodyBuilder.concat(Seq(Array(0x45.toByte), BodyBuilder.i4(10), BodyBuilder.i4(0))))
    )
  }

  test("C2-07: branch to a non-instruction offset is a Failure") {
    // br.s +1 targets offset 3, but the only instruction after the branch
    // starts at offset 3? No — ret at 3 IS an instruction. Target offset 2
    // instead: [0] nop | [1] br.s -1 -> 2 | [2..] nothing. Layout: ret at
    // 3; delta -1 targets 2 which is inside the branch.
    assertFails(
      BodyBuilder.tiny(BodyBuilder.concat(Seq(
        Array(0x00.toByte),
        Array(0x2b.toByte, 0xff.toByte), // br.s -1 -> target 2 (inside branch)
        Array(0x2a.toByte)
      )))
    )
  }

  test("C2-07: branch outside the code stream is a Failure") {
    // br.s +2 from next-offset 3 -> target 5, but the stream ends at 3.
    assertFails(
      BodyBuilder.tiny(BodyBuilder.concat(Seq(
        Array(0x2b.toByte, 0x02.toByte) // br.s +2 -> 5, beyond the body
      )))
    )
  }

  test("C2-07: switch target to a non-instruction offset is a Failure") {
    // switch(1) at 0 with delta 1 -> post-table address 9, target 10:
    // instruction at 9 is ret (1 byte), 10 is past the stream.
    assertFails(
      BodyBuilder.tiny(BodyBuilder.concat(Seq(
        Array(0x45.toByte), BodyBuilder.i4(1), BodyBuilder.i4(1),
        Array(0x2a.toByte)
      )))
    )
  }
}
