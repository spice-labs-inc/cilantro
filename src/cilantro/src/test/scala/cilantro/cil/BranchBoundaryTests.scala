// BranchBoundaryTests — C2-02.
//
// Why this test exists:
//   Branch offsets are signed and relative to the byte AFTER the branch
//   instruction (ECMA-335 III.3.x); off-by-one or sign-extension errors
//   here silently corrupt every control-flow graph the decoder produces.
//   This test pins both forms (short 8-bit, long 32-bit) at the exact
//   deltas −128/−127/−1/0/+1/+127/+128 and branch-to-self, where each
//   target lands on a real instruction.
//
// Theory of the test:
//   Hand-laid bodies (see BodyBuilder) place the branch so that
//   nextInstructionOffset + delta equals a chosen instruction offset; the
//   decoded Operand.Branch(targetIndex) must index the instruction whose
//   offset equals that target. Targets that land inside instructions or
//   outside the body are pinned as Failures in MalformedBodyTests.

package io.spicelabs.cilantro.cil

import scala.util.Success

class BranchBoundaryTests extends munit.FunSuite {

  private def decodeTiny(insns: Array[Byte]): MethodBody = {
    CodeReader.readBody(BodyBuilder.tiny(insns)) match {
      case Success(body) => body
      case scala.util.Failure(t) => fail(s"decode failed: $t")
    }
  }

  // Bodies longer than the tiny-format 63-byte cap must use the fat header.
  private def decodeFat(insns: Array[Byte]): MethodBody = {
    CodeReader.readBody(BodyBuilder.fat(insns)) match {
      case Success(body) => body
      case scala.util.Failure(t) => fail(s"decode failed: $t")
    }
  }

  private def branchTarget(body: MethodBody, branchIndex: Int): Int = {
    val instr = body.instructions(branchIndex)
    instr.operand match {
      case Some(Operand.Branch(targetIndex)) => body.instructions(targetIndex).offset
      case other => fail(s"expected Branch operand at index $branchIndex, got $other")
    }
  }

  // [0] nop | [1] br.s +0 -> target 3 | [3] ret
  test("C2-02: br.s delta 0 targets the following instruction") {
    val body = decodeTiny(BodyBuilder.concat(Seq(
      Array(0x00.toByte),            // nop
      Array(0x2b.toByte, 0x00),      // br.s +0
      Array(0x2a.toByte)             // ret
    )))
    assertEquals(branchTarget(body, 1), 3)
  }

  // [0] nop | [1] br.s +1 -> target 4 | [3] nop | [4] ret
  test("C2-02: br.s delta +1 targets the instruction after the following one") {
    val body = decodeTiny(BodyBuilder.concat(Seq(
      Array(0x00.toByte),
      Array(0x2b.toByte, 0x01),
      Array(0x00.toByte),
      Array(0x2a.toByte)
    )))
    assertEquals(branchTarget(body, 1), 4)
  }

  // A delta of -1 from a branch always lands on the branch's own operand
  // byte (the branch occupies the bytes between the previous instruction
  // and the next one), so it can never target an instruction start: it is
  // pinned here as a clean Failure.
  test("C2-02: br.s delta -1 lands inside the branch and is a Failure") {
    val body = BodyBuilder.tiny(BodyBuilder.concat(Seq(
      Array(0x00.toByte),
      Array(0x00.toByte),
      Array(0x2b.toByte, 0xff.toByte),
      Array(0x2a.toByte)
    )))
    CodeReader.readBody(body) match {
      case Success(_) => fail("delta -1 must not decode")
      case scala.util.Failure(_) => ()
    }
  }

  // [0] nop | [1] br.s -2 -> target 1 (branch to self)
  test("C2-02: br.s delta -2 is branch-to-self") {
    val body = decodeTiny(BodyBuilder.concat(Seq(
      Array(0x00.toByte),
      Array(0x2b.toByte, 0xfe.toByte)
    )))
    assertEquals(branchTarget(body, 1), 1)
  }

  // 126 nops [0..125] | [126] br.s -128 -> target 0
  test("C2-02: br.s delta -128 (sign-extended minimum) targets offset 0") {
    val pad = Array.fill[Byte](126)(0x00)
    val insns = BodyBuilder.concat(Seq(
      pad,
      Array(0x2b.toByte, 0x80.toByte)  // delta -128
    ))
    val body = decodeFat(insns)
    assertEquals(body.instructions(126).offset, 126)
    assertEquals(branchTarget(body, 126), 0)
  }

  // 126 nops [0..125] | [126] br.s -127 -> target 1
  test("C2-02: br.s delta -127 targets offset 1") {
    val pad = Array.fill[Byte](126)(0x00)
    val insns = BodyBuilder.concat(Seq(
      pad,
      Array(0x2b.toByte, 0x81.toByte)  // delta -127
    ))
    assertEquals(branchTarget(decodeFat(insns), 126), 1)
  }

  // [0] nop | [1] br.s +127 | [3..129] 127 nops | [130] ret
  test("C2-02: br.s delta +127 (sign-extended maximum) targets the padded tail") {
    val pad = Array.fill[Byte](127)(0x00)
    val insns = BodyBuilder.concat(Seq(
      Array(0x00.toByte),
      Array(0x2b.toByte, 0x7f.toByte),  // delta +127
      pad,                              // offsets 3..129
      Array(0x2a.toByte)                // ret at 130
    ))
    val body = decodeFat(insns)
    assertEquals(branchTarget(body, 1), 130)
  }

  // [0] nop | [1] br +128 (long form) | [6..133] 128 nops | [134] ret
  test("C2-02: br (long) delta +128 exceeds the short range and targets 134") {
    val pad = Array.fill[Byte](128)(0x00)
    val insns = BodyBuilder.concat(Seq(
      Array(0x00.toByte),
      BodyBuilder.concat(Seq(Array(0x38.toByte), BodyBuilder.i4(128))),
      pad,                              // offsets 6..133
      Array(0x2a.toByte)                // ret at 134
    ))
    val body = decodeFat(insns)
    assertEquals(branchTarget(body, 1), 134)
  }

  // [0..128] 129 nops | [129] br (long) -129 -> target 5
  test("C2-02: br (long) delta -129 is sign-extended and targets offset 5") {
    val pad = Array.fill[Byte](129)(0x00)
    val insns = BodyBuilder.concat(Seq(
      pad,                              // offsets 0..128
      BodyBuilder.concat(Seq(Array(0x38.toByte), BodyBuilder.i4(-129)))
    ))
    val body = decodeFat(insns)
    assertEquals(branchTarget(body, 129), 5)
  }

  // [0] nop | [1] br (long) -5 -> target 1 (branch to self, long form)
  test("C2-02: br (long) delta -5 is branch-to-self") {
    val insns = BodyBuilder.concat(Seq(
      Array(0x00.toByte),
      BodyBuilder.concat(Seq(Array(0x38.toByte), BodyBuilder.i4(-5)))
    ))
    assertEquals(branchTarget(decodeTiny(insns), 1), 1)
  }
}
