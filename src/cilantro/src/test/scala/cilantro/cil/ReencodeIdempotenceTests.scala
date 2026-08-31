// ReencodeIdempotenceTests — C4-06.
//
// Why this test exists:
//   Phase 4 requires decode -> re-encode -> decode stability: a body
//   written back out and re-read must decode to the same instruction
//   stream, and short branches that grow out of range must resize to
//   their long form without corrupting the layout. This is the
//   round-trip guarantee a writer (and any future assembler) depends on,
//   and it pins the branch sizing rules (delta measured from the END of
//   the branch instruction, signed byte range for the short form).
//
// Theory of the test:
//   A minimal encoder in this file (test-only) emits each decoded
//   instruction back to bytes. The sizing pass assumes the short branch
//   form, checks every branch delta, and promotes any overflowed branch
//   to the long form; the pass repeats until the layout is stable. The
//   re-decoded body must be structurally identical (same offsets,
//   opcodes, operand values, and branch target indices). A second test
//   forces the resize by padding the branch span past 127 bytes and
//   pins the long-form encoding and the resulting offsets.
//
// Requirements traced:
//   04_parity_harness_and_security_caps.md — C4-06
//   ReencodeIdempotenceProperty: decode->re-encode->decode stable;
//   branch resize stable.
//
// LLM notes:
//   - The switch operand table follows the count with no alignment
//     (see the pinned oracle decision in SwitchTests).
//   - The branch delta is relative to the offset AFTER the branch
//     instruction (the next instruction's offset).

package io.spicelabs.cilantro.cil

import scala.util.Success

object ReencodeEncoder {
  // branch short -> long opcode pairs by code value
  private val longForm: Map[Int, Int] = Map(
    0x2b -> 0x38, // br.s -> br
    0x2d -> 0x39, // brtrue.s -> brtrue
    0x2c -> 0x3a, // brfalse.s -> brfalse
    0x2e -> 0x3b, // beq.s -> beq
    0x30 -> 0x3e, // ble.s -> ble
    0x2f -> 0x3d  // bge.s -> bge
  )

  private def operandSize(body: MethodBody, insn: Instruction, shortBranches: Boolean): Int = {
    insn.opcode.operandType match {
      case OperandType.InlineNone | OperandType.InlinePhi => 0
      case OperandType.ShortInlineI | OperandType.ShortInlineVar | OperandType.ShortInlineArg => 1
      case OperandType.InlineVar | OperandType.InlineArg => 2
      case OperandType.InlineI | OperandType.InlineBrTarget | OperandType.InlineField |
          OperandType.InlineMethod | OperandType.InlineSig | OperandType.InlineString |
          OperandType.InlineTok | OperandType.InlineType => 4
      case OperandType.ShortInlineR => 4
      case OperandType.InlineI8 | OperandType.InlineR => 8
      case OperandType.ShortInlineBrTarget =>
        if (shortBranches) 1 else 4
      case OperandType.InlineSwitch =>
        insn.operand match {
          case Some(Operand.Switch(targets)) => 4 + 4 * targets.length
          case _ => 4
        }
    }
  }

  def encode(body: MethodBody): Array[Byte] = {
    val insns = body.instructions
    // Iterate: assume short branches; promote overflowed ones to long
    // until the layout is stable.
    var short = insns.map(_ => true).toArray
    var stable = false
    while (!stable) {
      stable = true
      var offset = 0
      val ends = Array.ofDim[Int](insns.length)
      val offsets = Array.ofDim[Int](insns.length)
      var i = 0
      while (i < insns.length) {
        offsets(i) = offset
        val size = insns(i).opcode.size + operandSize(body, insns(i), short(i))
        offset += size
        ends(i) = offset
        i += 1
      }
      i = 0
      while (i < insns.length) {
        if (insns(i).opcode.operandType == OperandType.ShortInlineBrTarget) {
          insns(i).operand match {
            case Some(Operand.Branch(targetIndex)) =>
              val delta = offsets(targetIndex) - ends(i)
              if (delta < -128 || delta > 127) {
                if (short(i)) {
                  short(i) = false
                  stable = false
                }
              }
            case _ => ()
          }
        }
        i += 1
      }
    }

    val builder = Array.newBuilder[Byte]
    // Layout pass result: offsets and instruction ends for the stable
    // short/long assignment.
    val offsets = Array.ofDim[Int](insns.length)
    val ends = Array.ofDim[Int](insns.length)
    var o = 0
    var k = 0
    while (k < insns.length) {
      offsets(k) = o
      o += insns(k).opcode.size + operandSize(body, insns(k), short(k))
      ends(k) = o
      k += 1
    }
    var i = 0
    while (i < insns.length) {
      val insn = insns(i)
      val useShort = insn.opcode.operandType == OperandType.ShortInlineBrTarget && short(i)
      val code = if (insn.opcode.operandType == OperandType.ShortInlineBrTarget && !useShort) {
        longForm(insn.opcode.code1.toInt & 0xff)
      } else {
        insn.opcode.code1.toInt & 0xff
      }
      builder += code.toByte
      if (insn.opcode.size == 2) {
        builder += (insn.opcode.code2.toInt & 0xff).toByte
      }
      insn.operand match {
        case None => ()
        case Some(Operand.I4(v)) =>
          insn.opcode.operandType match {
            case OperandType.ShortInlineI => builder += v.toByte
            case _ => BodyBuilder.i4(v).foreach(builder += _)
          }
        case Some(Operand.I8(v)) => BodyBuilder.i8(v).foreach(builder += _)
        case Some(Operand.R4(v)) => BodyBuilder.f4(v).foreach(builder += _)
        case Some(Operand.R8(v)) => BodyBuilder.f8(v).foreach(builder += _)
        case Some(Operand.Var(index)) =>
          insn.opcode.operandType match {
            case OperandType.ShortInlineVar => builder += index.toByte
            case _ =>
              builder += (index & 0xff).toByte
              builder += ((index >> 8) & 0xff).toByte
          }
        case Some(Operand.Arg(index)) =>
          insn.opcode.operandType match {
            case OperandType.ShortInlineArg => builder += index.toByte
            case _ =>
              builder += (index & 0xff).toByte
              builder += ((index >> 8) & 0xff).toByte
          }
        case Some(Operand.Tok(token, _)) =>
          BodyBuilder.i4(token.token).foreach(builder += _)
        case Some(Operand.Branch(targetIndex)) =>
          val delta = offsets(targetIndex) - ends(i)
          if (useShort) {
            builder += delta.toByte
          } else {
            BodyBuilder.i4(delta).foreach(builder += _)
          }
        case Some(Operand.Switch(targets)) =>
          BodyBuilder.i4(targets.length).foreach(builder += _)
          targets.foreach { t => BodyBuilder.i4(offsets(t) - ends(i)).foreach(builder += _) }
        case Some(Operand.StringLit(_)) => ()
        case Some(Operand.Sig(_)) => ()
      }
      i += 1
    }
    builder.result()
  }
}

class ReencodeIdempotenceTests extends munit.FunSuite {

  private def decode(insns: Array[Byte]): MethodBody = {
    CodeReader.readBody(BodyBuilder.fat(insns)) match {
      case Success(body) => body
      case scala.util.Failure(t) => fail(s"decode failed: $t")
    }
  }

  private def fingerprint(body: MethodBody): String = {
    body.instructions.map { insn =>
      val op = insn.operand match {
        case None => "none"
        case Some(Operand.Branch(t)) => s"branch:$t"
        case Some(Operand.Switch(ts)) => s"switch:${ts.mkString(",")}"
        case Some(Operand.I4(v)) => s"i4:$v"
        case Some(Operand.I8(v)) => s"i8:$v"
        case Some(Operand.R4(v)) => s"r4:$v"
        case Some(Operand.R8(v)) => s"r8:$v"
        case Some(Operand.Var(v)) => s"var:$v"
        case Some(Operand.Arg(v)) => s"arg:$v"
        case Some(Operand.Tok(t, r)) => s"tok:${t.token}:$r"
        case other => s"other:$other"
      }
      s"${insn.offset}:${insn.opcode.code.toHexString}:$op"
    }.mkString("|")
  }

  test("C4-06: decode -> encode -> decode round-trips offsets, opcodes and operands") {
    val insns = BodyBuilder.concat(Seq(
      Array(0x00.toByte), // nop
      BodyBuilder.concat(Seq(Array(0x20.toByte), BodyBuilder.i4(123456))), // ldc.i4
      Array(0x03.toByte), // ldarg.1
      BodyBuilder.concat(Seq(Array(0x21.toByte), BodyBuilder.i8(1234567890123L))), // ldc.i8
      BodyBuilder.concat(Seq(Array(0x1f.toByte), Array(7.toByte))), // ldc.i4.s
      BodyBuilder.concat(Seq(Array(0xfe.toByte, 0x09.toByte), Array(0.toByte, 0.toByte))), // ldloc.2
      BodyBuilder.concat(Seq(Array(0x23.toByte), BodyBuilder.f8(3.5))), // ldc.r8
      Array(0x58.toByte), // add
      BodyBuilder.concat(Seq(Array(0x2b.toByte), Array(0.toByte))), // br.s -> ret
      Array(0x2a.toByte) // ret
    ))
    val first = decode(insns)
    val reencoded = ReencodeEncoder.encode(first)
    val second = decode(reencoded)
    assertEquals(fingerprint(second), fingerprint(first))
    assertEquals(second.instructions.map(_.offset), first.instructions.map(_.offset))
  }

  test("C4-06: a short branch out of range resizes to the long form and stays stable") {
    // Construct a body directly: a short br.s whose target sits 200
    // bytes away behind a nop pad (the layout a decoder would see after
    // a body edit that pushed the target out of the sbyte range). The
    // encoder must promote it to the long br; the re-decoded body must
    // keep the target and the sizing must stay stable on a second
    // encode.
    val br = OpCodes.forCode(0x2b).get
    val nop = OpCodes.forCode(0x00).get
    val ret = OpCodes.forCode(0x2a).get
    val insns: Vector[Instruction] =
      new Instruction(0, br, Some(Operand.Branch(201))) +:
      (1 to 200).map(i => new Instruction(1 + i, nop, None)).toVector :+
      new Instruction(202, ret, None)
    val body = new MethodBody(insns, 8, false, 203, None, Vector.empty)
    val reencoded = ReencodeEncoder.encode(body)
    val second = decode(reencoded)
    val branch = second.instructions(0)
    assert(branch.opcode.code1.toInt == 0x38, "the branch must be promoted to the long form")
    branch.operand match {
      case Some(Operand.Branch(t)) =>
        assertEquals(second.instructions(t).opcode.code1.toInt, 0x2a)
      case other => fail(s"expected branch operand, got $other")
    }
    // Idempotence: encoding the resized body again must be stable.
    val third = decode(ReencodeEncoder.encode(second))
    assertEquals(fingerprint(third), fingerprint(second))
  }

  test("C4-06: switch round-trips with its target table") {
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(
        Array(0x45.toByte),
        BodyBuilder.i4(2),
        BodyBuilder.i4(0),
        BodyBuilder.i4(1)
      )),
      Array(0x2a.toByte), // ret (target 0)
      Array(0x00.toByte) // nop (target 1)
    ))
    val first = decode(insns)
    val reencoded = ReencodeEncoder.encode(first)
    val second = decode(reencoded)
    second.instructions(0).operand match {
      case Some(Operand.Switch(targets)) =>
        assertEquals(targets.length, 2)
        assertEquals(second.instructions(targets(0)).opcode.code1.toInt, 0x2a)
        assertEquals(second.instructions(targets(1)).opcode.code1.toInt, 0x00)
      case other => fail(s"expected switch operand, got $other")
    }
    assertEquals(second.instructions.map(_.offset), first.instructions.map(_.offset))
  }
}
