// Instruction — one decoded CIL instruction.
//
// The operand is an ADT (no raw nulls, no boxed AnyRef):
//   Tok(token, resolved)  metadata token; `resolved` is None until a
//                         resolver supplies the canonical name
//   Branch(targetIndex)   branch; index into the body's instruction vector
//   Switch(targets)       switch; indices into the body's instruction vector
//   StringLit(value)      user string resolved via the string resolver
//   Sig(blobIndex)        standalone signature blob index (calli)
//   I4/I8/R4/R8           inline numerics
//   Var/Arg               local/argument index
//
// Branch targets are stored as indices rather than direct references so the
// body vector stays acyclic and immutable-friendly; the decoder resolves
// indices from target offsets after the single decode pass.

package io.spicelabs.cilantro.cil

import io.spicelabs.cilantro.MetadataToken

enum Operand {
  case Tok(token: MetadataToken, resolved: Option[String])
  case Branch(targetIndex: Int)
  case Switch(targets: Vector[Int])
  case StringLit(value: String)
  case Sig(blobIndex: Int)
  case I4(value: Int)
  case I8(value: Long)
  case R4(value: Float)
  case R8(value: Double)
  case Var(index: Int)
  case Arg(index: Int)
}

sealed class Instruction(
    private val _offset: Int,
    private val _opcode: OpCode,
    private val _operand: Option[Operand]
) {
  def offset: Int = _offset
  def opcode: OpCode = _opcode
  def operand: Option[Operand] = _operand

  // Total encoded size in bytes: opcode bytes plus operand bytes
  // (a switch carries 4 count bytes plus 4 bytes per target).
  def size: Int = {
    val operandBytes = _operand match {
      case Some(Operand.Switch(targets)) => 4 + 4 * targets.length
      case _                             => CodeReader.operandSize(_opcode.operandType)
    }
    _opcode.size + operandBytes
  }

  override def toString: String = {
    val operandText = _operand match {
      case Some(op) => s" $op"
      case None     => ""
    }
    s"$offset ${_opcode.name}$operandText"
  }
}
