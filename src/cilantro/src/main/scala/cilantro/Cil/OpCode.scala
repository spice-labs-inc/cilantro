// OpCode — the ECMA-335 opcode model.
//
// Port of Mono.Cecil's OpCode/OpCodes (Cil/OpCode.cs, OpCodes.cs):
// name, two-byte code value, operand type, flow control, and stack
// behaviour for every defined opcode, plus lookup by code value (with the
// 0xFE two-byte prefix).
//
// Stack behaviour naming follows Cecil: `Pop1_pop1` pops two values,
// `Popi_popi8` pops one native int and one int64, etc. These are advisory
// metadata; the decoder does not interpret them.

package io.spicelabs.cilantro.cil

enum OperandType {
  case InlineBrTarget, InlineField, InlineI, InlineI8, InlineMethod, InlineNone,
    InlinePhi, InlineR, InlineSig, InlineString, InlineSwitch, InlineTok,
    InlineType, InlineVar, InlineArg, ShortInlineBrTarget, ShortInlineI,
    ShortInlineR, ShortInlineVar, ShortInlineArg
}

enum FlowControl {
  case Branch, Break, Call, Cond_Branch, Meta, Next, Phi, Return, Throw
}

enum StackBehaviour {
  case Pop0, Pop1, Pop1_pop1, Popi, Popi_pop1, Popi_popi, Popi_popi8, Popi_popi_popi,
    Popi_popi_popi8, Popi_popr4, Popi_popr8, Popref, Popref_pop1, Popref_popi,
    Popref_popi_popi, Popref_popi_popi8, Popref_popi_popr4, Popref_popi_popr8,
    Popref_popi_popref, Push0, Push1, Push1_push1, Pushi, Pushi8, Pushr4, Pushr8,
    Pushref, Varpop, Varpush
}

case class OpCode(
    name: String,
    code1: Byte,
    code2: Byte,
    operandType: OperandType,
    flowControl: FlowControl,
    stackBehaviourPop: StackBehaviour,
    stackBehaviourPush: StackBehaviour
) {
  // Cecil-style 16-bit code value: one-byte opcodes carry 0xFF in the high
  // byte; two-byte opcodes carry 0xFE in the low byte.
  def code: Int = (code1.toInt & 0xff) | ((code2.toInt & 0xff) << 8)

  def size: Int = if ((code1.toInt & 0xff) == 0xfe) 2 else 1

  override def toString: String = name
}
