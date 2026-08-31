// CodeSizeInvariantTests — C2-06.
//
// Why this test exists:
//   The plan's invariant: the sum of decoded instruction sizes must equal
//   the header's CodeSize. It catches ~90% of operand-length bugs before
//   the goldens do, because any operand mis-sized or mis-signed shifts the
//   stream and breaks the invariant (or lands a branch on a non-instruction
//   offset). This test pins the invariant on valid bodies of every header
//   shape and pins Failure on bodies whose stream does not fit the header
//   exactly.
//
// Theory of the test:
//   Valid tiny and fat bodies of mixed operand widths must decode with
//   sum(sizes) == codeSize and instruction offsets covering the whole
//   stream. A header claiming more code than exists, and a stream that
//   runs out before the header's codeSize, must both be Failures.

package io.spicelabs.cilantro.cil

import scala.util.{Failure, Success}

class CodeSizeInvariantTests extends munit.FunSuite {

  private def assertInvariant(body: MethodBody): Unit = {
    val sum = body.instructions.map(_.size).sum
    assertEquals(sum, body.codeSize, "sum of instruction sizes must equal codeSize")
    if (body.instructions.nonEmpty) {
      assertEquals(body.instructions.head.offset, 0)
    }
    body.instructions.zipWithIndex.foreach { case (instr, idx) =>
      if (idx > 0) {
        val previous = body.instructions(idx - 1)
        assertEquals(
          instr.offset,
          previous.offset + previous.size,
          s"instruction $idx offset must follow its predecessor"
        )
      }
    }
  }

  test("C2-06: tiny body satisfies the size invariant") {
    val insns = BodyBuilder.concat(Seq(
      Array(0x02.toByte, 0x04.toByte),       // ldarg.0 ldarg.2
      Array(0x58.toByte),                    // add
      BodyBuilder.concat(Seq(Array(0x0e.toByte), BodyBuilder.i1(1))), // ldarg.s 1
      Array(0x58.toByte),
      Array(0x2a.toByte)
    ))
    CodeReader.readBody(BodyBuilder.tiny(insns)) match {
      case Success(body) =>
        assertEquals(body.codeSize, insns.length)
        assertInvariant(body)
      case Failure(t) => fail(s"valid body failed: $t")
    }
  }

  test("C2-06: fat body with mixed operand widths satisfies the size invariant") {
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(Array(0x20.toByte), BodyBuilder.i4(42))), // ldc.i4
      BodyBuilder.concat(Seq(Array(0x21.toByte), BodyBuilder.i8(7L))), // ldc.i8
      BodyBuilder.concat(Seq(Array(0x22.toByte), BodyBuilder.f4(1f))), // ldc.r4
      BodyBuilder.concat(Seq(Array(0x23.toByte), BodyBuilder.f8(2d))), // ldc.r8
      BodyBuilder.concat(Seq(Array(0x72.toByte), BodyBuilder.i4(0x70000001))), // ldstr
      BodyBuilder.concat(Seq(Array(0x2b.toByte), BodyBuilder.i1(0))), // br.s
      Array(0x2a.toByte)
    ))
    CodeReader.readBody(
      BodyBuilder.fat(insns, maxStack = 4, initLocals = false),
      _ => Some("ignored"),
      _ => None
    ) match {
      case Success(body) =>
        assertEquals(body.codeSize, insns.length)
        assertEquals(body.maxStackSize, 4)
        assertEquals(body.initLocals, false)
        assertInvariant(body)
      case Failure(t) => fail(s"valid body failed: $t")
    }
  }

  test("C2-06: a header claiming more code than exists is a Failure") {
    val insns = Array(0x2a.toByte) // ret
    // Fat header claims 16 bytes of code; only 1 exists.
    val body = BodyBuilder.concat(Seq(
      BodyBuilder.i2(0x03 | 0x10), BodyBuilder.i2(8), BodyBuilder.i4(16), BodyBuilder.i4(0),
      insns
    ))
    CodeReader.readBody(body) match {
      case Success(_) => fail("truncated body must not decode")
      case Failure(_) => ()
    }
  }

  test("C2-06: a stream that runs out before the header codeSize is a Failure") {
    // Tiny header claims 8 bytes of code; the stream has 4.
    CodeReader.readBody(Array(0x02 | (8 << 2), 0x2a.toByte, 0x2a.toByte, 0x2a.toByte, 0x2a.toByte)) match {
      case Success(_) => fail("short stream must not decode")
      case Failure(_) => ()
    }
  }
}
