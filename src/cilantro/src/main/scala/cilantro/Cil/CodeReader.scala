// CodeReader — the ECMA-335 method-body decoder.
//
// Port of Mono.Cecil's CodeReader (Cil/CodeReader.cs): header parse
// (tiny/fat), then a single decode pass over the IL stream producing
// instructions with typed operands. Branch and switch targets are decoded
// as offsets and resolved to instruction indices in a fix-up pass, so the
// result is acyclically addressable.
//
// Contract (per the phase plan):
//   - readBody returns Try[MethodBody]; every malformed input is a
//     Failure, never a throw and never a partial body.
//   - Decode-time failures: invalid header format bits, truncated
//     header/opcode/operand, operand crossing the code boundary, negative
//     or oversized switch count, branch target that does not land on an
//     instruction, unresolvable user-string token.
//   - The sum of decoded instruction sizes must equal the header codeSize;
//     the decode loop is structurally bounded so this holds or the body
//     fails.
//
// Resolvers (both optional):
//   resolveString: user-string token -> string value (InlineString)
//   resolveToken:  metadata token -> canonical name (InlineTok/Method/
//                  Field/Type). None means "unresolved": the token is kept
//                  raw in Operand.Tok and fails at resolve-time, not
//                  decode-time (plan gotcha).
//
// Switch-table layout follows the oracle (Mono.Cecil 0.11.6, pinned as the
// golden generator): the N offsets follow the count immediately with no
// alignment skip — verified against the ilasm fixture golden, where mono
// ilasm emits an unaligned table and Cecil decodes it. Offsets are relative
// to the address following the whole operand. See phase-2-claims.md.

package io.spicelabs.cilantro.cil

import scala.util.Try
import io.spicelabs.cilantro.MetadataToken

object CodeReader {

  // Decode-time failure. Private: only ever observed through Try, so no
  // exception crosses the API boundary (no exceptions for control flow).
  private final case class DecodeError(message: String) extends RuntimeException(message)

  def operandSize(operandType: OperandType): Int = operandType match {
    case OperandType.InlineNone | OperandType.InlinePhi => 0
    case OperandType.ShortInlineI | OperandType.ShortInlineBrTarget |
        OperandType.ShortInlineVar | OperandType.ShortInlineArg =>
      1
    case OperandType.InlineVar | OperandType.InlineArg => 2
    case OperandType.ShortInlineR | OperandType.InlineI | OperandType.InlineBrTarget |
        OperandType.InlineString | OperandType.InlineSig | OperandType.InlineMethod |
        OperandType.InlineField | OperandType.InlineType | OperandType.InlineTok =>
      4
    case OperandType.InlineI8 | OperandType.InlineR => 8
    case OperandType.InlineSwitch => 4
  }

  def readBody(bytes: Array[Byte]): Try[MethodBody] = {
    readBody(bytes, _ => None, _ => None)
  }

  def readBody(
      bytes: Array[Byte],
      resolveString: Int => Option[String],
      resolveToken: MetadataToken => Option[String]
  ): Try[MethodBody] = {
    Try { new Reader(bytes, resolveString, resolveToken).decode() }
  }

  private final class Reader(
      bytes: Array[Byte],
      resolveString: Int => Option[String],
      resolveToken: MetadataToken => Option[String]
  ) {
    private var position = 0

    private def fail(message: String): Nothing = throw DecodeError(message)

    private def available(n: Int): Boolean = position + n <= bytes.length

    private def readU1(): Int = {
      if (!available(1)) {
        fail("truncated body: expected 1 byte at offset " + position)
      }
      val b = bytes(position)
      position += 1
      b.toInt & 0xff
    }

    private def readI1(): Int = {
      if (!available(1)) {
        fail("truncated body: expected 1 byte at offset " + position)
      }
      val b = bytes(position)
      position += 1
      b.toInt
    }

    private def readU2(): Int = {
      if (!available(2)) {
        fail("truncated body: expected 2 bytes at offset " + position)
      }
      val low = bytes(position).toInt & 0xff
      val high = bytes(position + 1).toInt & 0xff
      position += 2
      low | (high << 8)
    }

    private def readI4(): Int = {
      if (!available(4)) {
        fail("truncated body: expected 4 bytes at offset " + position)
      }
      val b0 = bytes(position).toInt & 0xff
      val b1 = bytes(position + 1).toInt & 0xff
      val b2 = bytes(position + 2).toInt & 0xff
      val b3 = bytes(position + 3).toInt & 0xff
      position += 4
      b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)
    }

    private def readI8(): Long = {
      if (!available(8)) {
        fail("truncated body: expected 8 bytes at offset " + position)
      }
      val low = readI4().toLong & 0xffffffffL
      val high = readI4().toLong & 0xffffffffL
      low | (high << 32)
    }

    private def readF4(): Float = {
      java.lang.Float.intBitsToFloat(readI4())
    }

    private def readF8(): Double = {
      java.lang.Double.longBitsToDouble(readI8())
    }

    def decode(): MethodBody = {
      val first = readU1()
      val format = first & 0x3
      format match {
        case 0x2 =>
          decodeBody(codeSize = first >> 2, maxStack = 8, initLocals = false, headerSize = 1)
        case 0x3 =>
          if (!available(11)) {
            fail("truncated body: fat header needs 12 bytes")
          }
          // The first byte already consumed is the LOW byte of the flags
          // word (ECMA-335 II.25.4.5: the format bits live in Flags bits
          // 0-1); the rest of the header follows.
          val flags = first | (readU1() << 8)
          val maxStack = readU2()
          val codeSize = readI4()
          val localSigToken = readI4()
          if (codeSize < 0) {
            fail("invalid body: negative code size " + codeSize)
          }
          decodeBody(codeSize, maxStack, (flags & 0x10) != 0, 12)
        case other =>
          fail(f"invalid body: method header format bits $other%x (expected 0x2 tiny or 0x3 fat)")
      }
    }

    private def decodeBody(
        codeSize: Int,
        maxStack: Int,
        initLocals: Boolean,
        headerSize: Int
    ): MethodBody = {
      if (!available(codeSize)) {
        fail(s"truncated body: code size $codeSize exceeds the ${bytes.length - position} remaining bytes")
      }
      val codeStart = position
      val codeEnd = position + codeSize
      codeStartOffset = codeStart

      // (offset, opcode, raw operand) — branch/switch operands are decoded
      // as absolute target offsets and fixed up to indices after the pass.
      val decoded = scala.collection.mutable.ArrayBuffer.empty[(Int, OpCode, Option[Operand], Int)]

      while (position < codeEnd) {
        val offset = position - codeStart
        val op = readOpCode(codeEnd)
        val operand = readOperand(op, codeEnd)
        decoded.addOne((offset, op, operand, position - codeStart))
      }
      if (position != codeEnd) {
        fail(s"invalid body: decoded instructions end at $position, expected $codeEnd")
      }

      val offsetToIndex: Map[Int, Int] = decoded.zipWithIndex.map { case ((offset, _, _, _), idx) =>
        (offset, idx)
      }.toMap

      val instructions = decoded.map { case (offset, op, operand, _) =>
        val fixedOperand = operand match {
          case Some(Operand.Branch(target)) =>
            Some(Operand.Branch(fixupTarget(offset, op, target, offsetToIndex)))
          case Some(Operand.Switch(targets)) =>
            Some(Operand.Switch(targets.map(t => fixupTarget(offset, op, t, offsetToIndex))))
          case other => other
        }
        new Instruction(offset, op, fixedOperand)
      }.toVector

      new MethodBody(instructions, maxStack, initLocals, codeSize, None, Vector.empty)
    }

    private def fixupTarget(
        instructionOffset: Int,
        op: OpCode,
        targetOffset: Int,
        offsetToIndex: Map[Int, Int]
    ): Int = {
      offsetToIndex.get(targetOffset) match {
        case Some(idx) => idx
        case None =>
          fail(
            s"invalid body: ${op.name} at offset $instructionOffset targets offset $targetOffset, " +
              "which is not the start of an instruction"
          )
      }
    }

    private def readOpCode(codeEnd: Int): OpCode = {
      if (position >= codeEnd) {
        fail("invalid body: opcode expected but code stream exhausted")
      }
      val first = readU1()
      if (first == 0xfe) {
        if (position >= codeEnd) {
          fail("truncated body: missing second byte of 0xfe-prefixed opcode")
        }
        val second = readU1()
        OpCodes.forCode(0xfe | (second << 8)) match {
          case Some(op) => op
          case None =>
            fail(f"invalid body: unknown two-byte opcode 0xfe 0x$second%02x at offset ${position - 2 - codeStartOffset}")
        }
      } else {
        OpCodes.forCode(first) match {
          case Some(op) => op
          case None =>
            fail(f"invalid body: unknown opcode 0x$first%02x at offset ${position - 1 - codeStartOffset}")
        }
      }
    }

    // Offset of the IL stream start; set by decodeBody via the code-start
    // position captured in the loop. Simpler: track it here.
    private var codeStartOffset = 0

    private def readOperand(op: OpCode, codeEnd: Int): Option[Operand] = {
      op.operandType match {
        case OperandType.InlineNone | OperandType.InlinePhi => None
        case OperandType.ShortInlineI =>
          Some(Operand.I4(readI1Within(op, codeEnd)))
        case OperandType.InlineI =>
          Some(Operand.I4(readI4Within(op, codeEnd)))
        case OperandType.InlineI8 =>
          Some(Operand.I8(readI8Within(op, codeEnd)))
        case OperandType.ShortInlineR =>
          Some(Operand.R4(readF4Within(op, codeEnd)))
        case OperandType.InlineR =>
          Some(Operand.R8(readF8Within(op, codeEnd)))
        case OperandType.ShortInlineVar =>
          Some(Operand.Var(readU1Within(op, codeEnd)))
        case OperandType.ShortInlineArg =>
          Some(Operand.Arg(readU1Within(op, codeEnd)))
        case OperandType.InlineVar =>
          Some(Operand.Var(readU2Within(op, codeEnd)))
        case OperandType.InlineArg =>
          Some(Operand.Arg(readU2Within(op, codeEnd)))
        case OperandType.ShortInlineBrTarget =>
          val delta = readI1Within(op, codeEnd)
          Some(Operand.Branch(delta + (position - codeStartOffset)))
        case OperandType.InlineBrTarget =>
          val delta = readI4Within(op, codeEnd)
          Some(Operand.Branch(delta + (position - codeStartOffset)))
        case OperandType.InlineSwitch =>
          Some(Operand.Switch(readSwitch(op, codeEnd)))
        case OperandType.InlineString =>
          val token = readI4Within(op, codeEnd)
          resolveString(token) match {
            case Some(value) => Some(Operand.StringLit(value))
            case None =>
              fail(f"invalid body: user string token 0x$token%08x could not be resolved at offset ${position - 4 - codeStartOffset}")
          }
        case OperandType.InlineSig =>
          Some(Operand.Sig(readI4Within(op, codeEnd)))
        case OperandType.InlineTok | OperandType.InlineMethod | OperandType.InlineField |
            OperandType.InlineType =>
          val raw = readI4Within(op, codeEnd)
          val token = MetadataToken(raw)
          Some(Operand.Tok(token, resolveToken(token)))
      }
    }

    private def readSwitch(op: OpCode, codeEnd: Int): Vector[Int] = {
      val count = readI4Within(op, codeEnd)
      if (count < 0) {
        fail(s"invalid body: switch with negative target count $count")
      }
      if (count > 65536) {
        fail(s"invalid body: switch with $count targets exceeds the 65536 cap")
      }
      // Pre-check before allocation (plan: limits are checked before any
      // allocation): the table must fit inside the code stream.
      val tableBytes = count.toLong * 4L
      if (tableBytes > (codeEnd - position).toLong) {
        fail(s"invalid body: switch table ($count targets) exceeds the code stream")
      }
      val deltas = Array.ofDim[Int](count)
      var i = 0
      while (i < count) {
        deltas(i) = readI4Within(op, codeEnd)
        i += 1
      }
      // Targets are relative to the address following the whole operand
      // (the instruction after the table), in IL offsets.
      val base = position - codeStartOffset
      deltas.toVector.map(d => base + d)
    }

    private def readI1Within(op: OpCode, codeEnd: Int): Int = {
      checkOperandSpace(op, 1, codeEnd)
      readI1()
    }

    private def readU1Within(op: OpCode, codeEnd: Int): Int = {
      checkOperandSpace(op, 1, codeEnd)
      readU1()
    }

    private def readU2Within(op: OpCode, codeEnd: Int): Int = {
      checkOperandSpace(op, 2, codeEnd)
      readU2()
    }

    private def readI4Within(op: OpCode, codeEnd: Int): Int = {
      checkOperandSpace(op, 4, codeEnd)
      readI4()
    }

    private def readI8Within(op: OpCode, codeEnd: Int): Long = {
      checkOperandSpace(op, 8, codeEnd)
      readI8()
    }

    private def readF4Within(op: OpCode, codeEnd: Int): Float = {
      checkOperandSpace(op, 4, codeEnd)
      readF4()
    }

    private def readF8Within(op: OpCode, codeEnd: Int): Double = {
      checkOperandSpace(op, 8, codeEnd)
      readF8()
    }

    private def checkOperandSpace(op: OpCode, operandBytes: Int, codeEnd: Int): Unit = {
      if (position + operandBytes > codeEnd) {
        fail(
          s"invalid body: operand of ${op.name} at offset ${position - codeStartOffset} " +
            s"crosses the code boundary ($codeEnd)"
        )
      }
    }
  }
}
