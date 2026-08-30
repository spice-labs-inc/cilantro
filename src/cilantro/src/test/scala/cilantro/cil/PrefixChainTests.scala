// PrefixChainTests — C2-04.
//
// Why this test exists:
//   constrained., unaligned., tail., no., readonly. (and volatile.) are
//   prefix instructions: they form a chain with the following instruction
//   and must each appear in the decoded stream, in order, with their
//   operands — treating any of them as skippable would silently misalign
//   every consumer of the instruction list (ECMA-335 III.2.1).
//
// Theory of the test:
//   A body is laid out as a full five-prefix chain followed by call. The
//   decoder must produce six instructions in order with the exact opcode
//   names and operand values: constrained.(type token), unaligned.(1),
//   tail., no.(0), readonly., call(method token).

package io.spicelabs.cilantro.cil

import scala.util.Success

class PrefixChainTests extends munit.FunSuite {

  private def decode(insns: Array[Byte]): MethodBody = {
    CodeReader.readBody(BodyBuilder.tiny(insns)) match {
      case Success(body) => body
      case scala.util.Failure(t) => fail(s"decode failed: $t")
    }
  }

  test("C2-04: a five-prefix chain decodes in order with its operands") {
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(Array(0xfe.toByte, 0x16.toByte), BodyBuilder.i4(0x0a000001))), // constrained. <type token>
      BodyBuilder.concat(Seq(Array(0xfe.toByte, 0x12.toByte), BodyBuilder.i1(1))),          // unaligned. 1
      Array(0xfe.toByte, 0x14.toByte),                                                       // tail.
      BodyBuilder.concat(Seq(Array(0xfe.toByte, 0x19.toByte), BodyBuilder.i1(0))),          // no. 0
      Array(0xfe.toByte, 0x1e.toByte),                                                       // readonly.
      BodyBuilder.concat(Seq(Array(0x28.toByte), BodyBuilder.i4(0x0a000002))),              // call <method token>
      Array(0x2a.toByte)                                                                     // ret
    ))
    val body = decode(insns)
    assertEquals(body.instructions.map(_.opcode.name).toSeq, Seq(
      "constrained.", "unaligned.", "tail.", "no.", "readonly.", "call", "ret"
    ))
    body.instructions(0).operand match {
      case Some(Operand.Tok(token, _)) => assertEquals(token.token, 0x0a000001)
      case other => fail(s"constrained. operand wrong: $other")
    }
    body.instructions(1).operand match {
      case Some(Operand.I4(value)) => assertEquals(value, 1)
      case other => fail(s"unaligned. operand wrong: $other")
    }
    body.instructions(3).operand match {
      case Some(Operand.I4(value)) => assertEquals(value, 0)
      case other => fail(s"no. operand wrong: $other")
    }
    body.instructions(5).operand match {
      case Some(Operand.Tok(token, _)) => assertEquals(token.token, 0x0a000002)
      case other => fail(s"call operand wrong: $other")
    }
    assertEquals(body.instructions(2).operand, None)
    assertEquals(body.instructions(4).operand, None)
  }

  test("C2-04: volatile. decodes as a prefix with no operand") {
    val insns = BodyBuilder.concat(Seq(
      Array(0xfe.toByte, 0x13.toByte), // volatile.
      BodyBuilder.concat(Seq(Array(0x7b.toByte), BodyBuilder.i4(0x04000001))), // ldfld
      Array(0x2a.toByte)
    ))
    val body = decode(insns)
    assertEquals(body.instructions.map(_.opcode.name).toSeq, Seq("volatile.", "ldfld", "ret"))
    assertEquals(body.instructions(0).operand, None)
  }

  test("C2-04: prefix opcodes are two bytes wide") {
    assertEquals(OpCodes.forCode(0x16fe).map(_.size), Some(2))
    assertEquals(OpCodes.forCode(0x16fe).map(_.operandType.toString), Some("InlineType"))
  }
}
