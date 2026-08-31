// OpCodes — the complete ECMA-335 opcode table.
//
// Every opcode defined by ECMA-335 Partition III plus Cecil's prefixes
// (including the `no.` prefix from Cecil 0.11.5+) is registered here with
// its name, code value, operand type, flow control and stack behaviour.
// Lookup is by full 16-bit code value; the 0xFE prefix selects the
// two-byte table. The registration order is the definition order below,
// which matches Cecil's OpCodes.cs so `OpCodes.all` is stable.
//
// Names follow Cecil exactly: dotted names for prefixes
// (`unaligned.`, `tail.`, `constrained.`, `no.`, `readonly.`, `volatile.`),
// lowercase opcode mnemonics (`ldc.i4.s`, `conv.ovf.i1.un`).
//
// Verified by `OpCodeTableTests` (full-enumeration round trip against an
// independent transcription of the ECMA table) and by the corpus goldens.

package io.spicelabs.cilantro.cil

import scala.collection.mutable.ArrayBuffer

object OpCodes {
  private val oneByteTable: Array[Option[OpCode]] = Array.fill[Option[OpCode]](256)(None)
  private val twoByteTable: Array[Option[OpCode]] = Array.fill[Option[OpCode]](256)(None)
  private val registered: ArrayBuffer[OpCode] = ArrayBuffer.empty[OpCode]

  private def register(op: OpCode): OpCode = {
    val c1 = op.code1.toInt & 0xff
    if (c1 == 0xfe) {
      twoByteTable(op.code2.toInt & 0xff) = Some(op)
    } else {
      oneByteTable(c1) = Some(op)
    }
    registered.addOne(op)
    op
  }

  private def one(
      code: Int,
      name: String,
      fc: FlowControl,
      ot: OperandType,
      pop: StackBehaviour,
      push: StackBehaviour
  ): OpCode =
    register(OpCode(name, code.toByte, 0xff.toByte, ot, fc, pop, push))

  private def two(
      code: Int,
      name: String,
      fc: FlowControl,
      ot: OperandType,
      pop: StackBehaviour,
      push: StackBehaviour
  ): OpCode =
    register(OpCode(name, 0xfe.toByte, code.toByte, ot, fc, pop, push))

  private val Next = FlowControl.Next
  private val Call = FlowControl.Call
  private val Branch = FlowControl.Branch
  private val Cond = FlowControl.Cond_Branch
  private val Return = FlowControl.Return
  private val Throw = FlowControl.Throw
  private val Break = FlowControl.Break
  private val Meta = FlowControl.Meta

  private val InlineNone = OperandType.InlineNone
  private val ShortInlineI = OperandType.ShortInlineI
  private val InlineI = OperandType.InlineI
  private val InlineI8 = OperandType.InlineI8
  private val ShortInlineR = OperandType.ShortInlineR
  private val InlineR = OperandType.InlineR
  private val ShortInlineBr = OperandType.ShortInlineBrTarget
  private val InlineBr = OperandType.InlineBrTarget
  private val InlineSwitch = OperandType.InlineSwitch
  private val InlineString = OperandType.InlineString
  private val InlineSig = OperandType.InlineSig
  private val InlineMethod = OperandType.InlineMethod
  private val InlineField = OperandType.InlineField
  private val InlineType = OperandType.InlineType
  private val InlineTok = OperandType.InlineTok
  private val InlineVar = OperandType.InlineVar
  private val InlineArg = OperandType.InlineArg
  private val ShortInlineVar = OperandType.ShortInlineVar
  private val ShortInlineArg = OperandType.ShortInlineArg

  private val Pop0 = StackBehaviour.Pop0
  private val Pop1 = StackBehaviour.Pop1
  private val Pop1_pop1 = StackBehaviour.Pop1_pop1
  private val Popi = StackBehaviour.Popi
  private val Popi_pop1 = StackBehaviour.Popi_pop1
  private val Popi_popi = StackBehaviour.Popi_popi
  private val Popi_popi8 = StackBehaviour.Popi_popi8
  private val Popi_popi_popi = StackBehaviour.Popi_popi_popi
  private val Popi_popi_popi8 = StackBehaviour.Popi_popi_popi8
  private val Popi_popr4 = StackBehaviour.Popi_popr4
  private val Popi_popr8 = StackBehaviour.Popi_popr8
  private val Popref = StackBehaviour.Popref
  private val Popref_pop1 = StackBehaviour.Popref_pop1
  private val Popref_popi = StackBehaviour.Popref_popi
  private val Popref_popi_popi = StackBehaviour.Popref_popi_popi
  private val Popref_popi_popi8 = StackBehaviour.Popref_popi_popi8
  private val Popref_popi_popr4 = StackBehaviour.Popref_popi_popr4
  private val Popref_popi_popr8 = StackBehaviour.Popref_popi_popr8
  private val Popref_popi_popref = StackBehaviour.Popref_popi_popref
  private val Push0 = StackBehaviour.Push0
  private val Push1 = StackBehaviour.Push1
  private val Push1_push1 = StackBehaviour.Push1_push1
  private val Pushi = StackBehaviour.Pushi
  private val Pushi8 = StackBehaviour.Pushi8
  private val Pushr4 = StackBehaviour.Pushr4
  private val Pushr8 = StackBehaviour.Pushr8
  private val Pushref = StackBehaviour.Pushref
  private val Varpop = StackBehaviour.Varpop
  private val Varpush = StackBehaviour.Varpush

  // ------------------------------------------------------------- one-byte

  private val nop = one(0x00, "nop", Next, InlineNone, Pop0, Push0)
  private val breakOp = one(0x01, "break", Break, InlineNone, Pop0, Push0)
  private val ldarg_0 = one(0x02, "ldarg.0", Next, InlineNone, Pop0, Push1)
  private val ldarg_1 = one(0x03, "ldarg.1", Next, InlineNone, Pop0, Push1)
  private val ldarg_2 = one(0x04, "ldarg.2", Next, InlineNone, Pop0, Push1)
  private val ldarg_3 = one(0x05, "ldarg.3", Next, InlineNone, Pop0, Push1)
  private val ldloc_0 = one(0x06, "ldloc.0", Next, InlineNone, Pop0, Push1)
  private val ldloc_1 = one(0x07, "ldloc.1", Next, InlineNone, Pop0, Push1)
  private val ldloc_2 = one(0x08, "ldloc.2", Next, InlineNone, Pop0, Push1)
  private val ldloc_3 = one(0x09, "ldloc.3", Next, InlineNone, Pop0, Push1)
  private val stloc_0 = one(0x0a, "stloc.0", Next, InlineNone, Pop1, Push0)
  private val stloc_1 = one(0x0b, "stloc.1", Next, InlineNone, Pop1, Push0)
  private val stloc_2 = one(0x0c, "stloc.2", Next, InlineNone, Pop1, Push0)
  private val stloc_3 = one(0x0d, "stloc.3", Next, InlineNone, Pop1, Push0)
  private val ldarg_s = one(0x0e, "ldarg.s", Next, ShortInlineArg, Pop0, Push1)
  private val ldarga_s = one(0x0f, "ldarga.s", Next, ShortInlineArg, Pop0, Pushi)
  private val starg_s = one(0x10, "starg.s", Next, ShortInlineArg, Pop1, Push0)
  private val ldloc_s = one(0x11, "ldloc.s", Next, ShortInlineVar, Pop0, Push1)
  private val ldloca_s = one(0x12, "ldloca.s", Next, ShortInlineVar, Pop0, Pushi)
  private val stloc_s = one(0x13, "stloc.s", Next, ShortInlineVar, Pop1, Push0)
  private val ldnull = one(0x14, "ldnull", Next, InlineNone, Pop0, Pushref)
  private val ldc_i4_m1 = one(0x15, "ldc.i4.m1", Next, InlineNone, Pop0, Pushi)
  private val ldc_i4_0 = one(0x16, "ldc.i4.0", Next, InlineNone, Pop0, Pushi)
  private val ldc_i4_1 = one(0x17, "ldc.i4.1", Next, InlineNone, Pop0, Pushi)
  private val ldc_i4_2 = one(0x18, "ldc.i4.2", Next, InlineNone, Pop0, Pushi)
  private val ldc_i4_3 = one(0x19, "ldc.i4.3", Next, InlineNone, Pop0, Pushi)
  private val ldc_i4_4 = one(0x1a, "ldc.i4.4", Next, InlineNone, Pop0, Pushi)
  private val ldc_i4_5 = one(0x1b, "ldc.i4.5", Next, InlineNone, Pop0, Pushi)
  private val ldc_i4_6 = one(0x1c, "ldc.i4.6", Next, InlineNone, Pop0, Pushi)
  private val ldc_i4_7 = one(0x1d, "ldc.i4.7", Next, InlineNone, Pop0, Pushi)
  private val ldc_i4_8 = one(0x1e, "ldc.i4.8", Next, InlineNone, Pop0, Pushi)
  private val ldc_i4_s = one(0x1f, "ldc.i4.s", Next, ShortInlineI, Pop0, Pushi)
  private val ldc_i4 = one(0x20, "ldc.i4", Next, InlineI, Pop0, Pushi)
  private val ldc_i8 = one(0x21, "ldc.i8", Next, InlineI8, Pop0, Pushi8)
  private val ldc_r4 = one(0x22, "ldc.r4", Next, ShortInlineR, Pop0, Pushr4)
  private val ldc_r8 = one(0x23, "ldc.r8", Next, InlineR, Pop0, Pushr8)
  private val dup = one(0x25, "dup", Next, InlineNone, Pop1, Push1_push1)
  private val pop = one(0x26, "pop", Next, InlineNone, Pop1, Push0)
  private val jmp = one(0x27, "jmp", Call, InlineMethod, Pop0, Push0)
  private val call = one(0x28, "call", Call, InlineMethod, Varpop, Varpush)
  private val calli = one(0x29, "calli", Call, InlineSig, Varpop, Varpush)
  private val ret = one(0x2a, "ret", Return, InlineNone, Pop1, Push0)
  private val br_s = one(0x2b, "br.s", Branch, ShortInlineBr, Pop0, Push0)
  private val brfalse_s = one(0x2c, "brfalse.s", Cond, ShortInlineBr, Popi, Push0)
  private val brtrue_s = one(0x2d, "brtrue.s", Cond, ShortInlineBr, Popi, Push0)
  private val beq_s = one(0x2e, "beq.s", Cond, ShortInlineBr, Pop1_pop1, Push0)
  private val bge_s = one(0x2f, "bge.s", Cond, ShortInlineBr, Pop1_pop1, Push0)
  private val bgt_s = one(0x30, "bgt.s", Cond, ShortInlineBr, Pop1_pop1, Push0)
  private val ble_s = one(0x31, "ble.s", Cond, ShortInlineBr, Pop1_pop1, Push0)
  private val blt_s = one(0x32, "blt.s", Cond, ShortInlineBr, Pop1_pop1, Push0)
  private val bne_un_s = one(0x33, "bne.un.s", Cond, ShortInlineBr, Pop1_pop1, Push0)
  private val bge_un_s = one(0x34, "bge.un.s", Cond, ShortInlineBr, Pop1_pop1, Push0)
  private val bgt_un_s = one(0x35, "bgt.un.s", Cond, ShortInlineBr, Pop1_pop1, Push0)
  private val ble_un_s = one(0x36, "ble.un.s", Cond, ShortInlineBr, Pop1_pop1, Push0)
  private val blt_un_s = one(0x37, "blt.un.s", Cond, ShortInlineBr, Pop1_pop1, Push0)
  private val br = one(0x38, "br", Branch, InlineBr, Pop0, Push0)
  private val brfalse = one(0x39, "brfalse", Cond, InlineBr, Popi, Push0)
  private val brtrue = one(0x3a, "brtrue", Cond, InlineBr, Popi, Push0)
  private val beq = one(0x3b, "beq", Cond, InlineBr, Pop1_pop1, Push0)
  private val bge = one(0x3c, "bge", Cond, InlineBr, Pop1_pop1, Push0)
  private val bgt = one(0x3d, "bgt", Cond, InlineBr, Pop1_pop1, Push0)
  private val ble = one(0x3e, "ble", Cond, InlineBr, Pop1_pop1, Push0)
  private val blt = one(0x3f, "blt", Cond, InlineBr, Pop1_pop1, Push0)
  private val bne_un = one(0x40, "bne.un", Cond, InlineBr, Pop1_pop1, Push0)
  private val bge_un = one(0x41, "bge.un", Cond, InlineBr, Pop1_pop1, Push0)
  private val bgt_un = one(0x42, "bgt.un", Cond, InlineBr, Pop1_pop1, Push0)
  private val ble_un = one(0x43, "ble.un", Cond, InlineBr, Pop1_pop1, Push0)
  private val blt_un = one(0x44, "blt.un", Cond, InlineBr, Pop1_pop1, Push0)
  private val switchOp = one(0x45, "switch", Cond, InlineSwitch, Popi, Push0)
  private val ldind_i1 = one(0x46, "ldind.i1", Next, InlineNone, Popi, Pushi)
  private val ldind_u1 = one(0x47, "ldind.u1", Next, InlineNone, Popi, Pushi)
  private val ldind_i2 = one(0x48, "ldind.i2", Next, InlineNone, Popi, Pushi)
  private val ldind_u2 = one(0x49, "ldind.u2", Next, InlineNone, Popi, Pushi)
  private val ldind_i4 = one(0x4a, "ldind.i4", Next, InlineNone, Popi, Pushi)
  private val ldind_u4 = one(0x4b, "ldind.u4", Next, InlineNone, Popi, Pushi)
  private val ldind_i8 = one(0x4c, "ldind.i8", Next, InlineNone, Popi, Pushi8)
  private val ldind_i = one(0x4d, "ldind.i", Next, InlineNone, Popi, Pushi)
  private val ldind_r4 = one(0x4e, "ldind.r4", Next, InlineNone, Popi, Pushr4)
  private val ldind_r8 = one(0x4f, "ldind.r8", Next, InlineNone, Popi, Pushr8)
  private val ldind_ref = one(0x50, "ldind.ref", Next, InlineNone, Popi, Pushref)
  private val stind_ref = one(0x51, "stind.ref", Next, InlineNone, Popi_popi, Push0)
  private val stind_i1 = one(0x52, "stind.i1", Next, InlineNone, Popi_popi, Push0)
  private val stind_i2 = one(0x53, "stind.i2", Next, InlineNone, Popi_popi, Push0)
  private val stind_i4 = one(0x54, "stind.i4", Next, InlineNone, Popi_popi, Push0)
  private val stind_i8 = one(0x55, "stind.i8", Next, InlineNone, Popi_popi, Push0)
  private val stind_r4 = one(0x56, "stind.r4", Next, InlineNone, Popi_popi, Push0)
  private val stind_r8 = one(0x57, "stind.r8", Next, InlineNone, Popi_popi, Push0)
  private val add = one(0x58, "add", Next, InlineNone, Pop1_pop1, Push1)
  private val sub = one(0x59, "sub", Next, InlineNone, Pop1_pop1, Push1)
  private val mul = one(0x5a, "mul", Next, InlineNone, Pop1_pop1, Push1)
  private val div = one(0x5b, "div", Next, InlineNone, Pop1_pop1, Push1)
  private val div_un = one(0x5c, "div.un", Next, InlineNone, Pop1_pop1, Push1)
  private val rem = one(0x5d, "rem", Next, InlineNone, Pop1_pop1, Push1)
  private val rem_un = one(0x5e, "rem.un", Next, InlineNone, Pop1_pop1, Push1)
  private val and = one(0x5f, "and", Next, InlineNone, Pop1_pop1, Push1)
  private val or = one(0x60, "or", Next, InlineNone, Pop1_pop1, Push1)
  private val xor = one(0x61, "xor", Next, InlineNone, Pop1_pop1, Push1)
  private val shl = one(0x62, "shl", Next, InlineNone, Pop1_pop1, Push1)
  private val shr = one(0x63, "shr", Next, InlineNone, Pop1_pop1, Push1)
  private val shr_un = one(0x64, "shr.un", Next, InlineNone, Pop1_pop1, Push1)
  private val neg = one(0x65, "neg", Next, InlineNone, Pop1, Push1)
  private val not = one(0x66, "not", Next, InlineNone, Pop1, Push1)
  private val conv_i1 = one(0x67, "conv.i1", Next, InlineNone, Pop1, Pushi)
  private val conv_i2 = one(0x68, "conv.i2", Next, InlineNone, Pop1, Pushi)
  private val conv_i4 = one(0x69, "conv.i4", Next, InlineNone, Pop1, Pushi)
  private val conv_i8 = one(0x6a, "conv.i8", Next, InlineNone, Pop1, Pushi8)
  private val conv_r4 = one(0x6b, "conv.r4", Next, InlineNone, Pop1, Pushr4)
  private val conv_r8 = one(0x6c, "conv.r8", Next, InlineNone, Pop1, Pushr8)
  private val conv_u4 = one(0x6d, "conv.u4", Next, InlineNone, Pop1, Pushi)
  private val conv_u8 = one(0x6e, "conv.u8", Next, InlineNone, Pop1, Pushi8)
  private val callvirt = one(0x6f, "callvirt", Call, InlineMethod, Varpop, Varpush)
  private val cpobj = one(0x70, "cpobj", Next, InlineType, Popi_popi, Push0)
  private val ldobj = one(0x71, "ldobj", Next, InlineType, Popi, Push1)
  private val ldstr = one(0x72, "ldstr", Next, InlineString, Pop0, Pushref)
  private val newobj = one(0x73, "newobj", Call, InlineMethod, Varpop, Pushref)
  private val castclass = one(0x74, "castclass", Next, InlineType, Popref, Pushref)
  private val isinst = one(0x75, "isinst", Next, InlineType, Popref, Pushi)
  private val conv_r_un = one(0x76, "conv.r.un", Next, InlineNone, Pop1, Pushr8)
  private val unbox = one(0x79, "unbox", Next, InlineType, Popref, Pushi)
  private val throwOp = one(0x7a, "throw", Throw, InlineNone, Popref, Push0)
  private val ldfld = one(0x7b, "ldfld", Next, InlineField, Popref, Push1)
  private val ldflda = one(0x7c, "ldflda", Next, InlineField, Popref, Push1)
  private val stfld = one(0x7d, "stfld", Next, InlineField, Pop1_pop1, Push0)
  private val ldsfld = one(0x7e, "ldsfld", Next, InlineField, Pop0, Push1)
  private val ldsflda = one(0x7f, "ldsflda", Next, InlineField, Pop0, Push1)
  private val stsfld = one(0x80, "stsfld", Next, InlineField, Pop1, Push0)
  private val stobj = one(0x81, "stobj", Next, InlineType, Pop1_pop1, Push0)
  private val conv_ovf_i1_un = one(0x82, "conv.ovf.i1.un", Next, InlineNone, Pop1, Pushi)
  private val conv_ovf_i2_un = one(0x83, "conv.ovf.i2.un", Next, InlineNone, Pop1, Pushi)
  private val conv_ovf_i4_un = one(0x84, "conv.ovf.i4.un", Next, InlineNone, Pop1, Pushi)
  private val conv_ovf_i8_un = one(0x85, "conv.ovf.i8.un", Next, InlineNone, Pop1, Pushi8)
  private val conv_ovf_u1_un = one(0x86, "conv.ovf.u1.un", Next, InlineNone, Pop1, Pushi)
  private val conv_ovf_u2_un = one(0x87, "conv.ovf.u2.un", Next, InlineNone, Pop1, Pushi)
  private val conv_ovf_u4_un = one(0x88, "conv.ovf.u4.un", Next, InlineNone, Pop1, Pushi)
  private val conv_ovf_u8_un = one(0x89, "conv.ovf.u8.un", Next, InlineNone, Pop1, Pushi8)
  private val conv_ovf_i_un = one(0x8a, "conv.ovf.i.un", Next, InlineNone, Pop1, Pushi)
  private val conv_ovf_u_un = one(0x8b, "conv.ovf.u.un", Next, InlineNone, Pop1, Pushi)
  private val box = one(0x8c, "box", Next, InlineType, Pop1, Pushref)
  private val newarr = one(0x8d, "newarr", Next, InlineType, Popi, Pushref)
  private val ldlen = one(0x8e, "ldlen", Next, InlineNone, Pop1, Pushi)
  private val ldelema = one(0x8f, "ldelema", Next, InlineType, Popref_popi, Pushi)
  private val ldelem_i1 = one(0x90, "ldelem.i1", Next, InlineNone, Popref_popi, Pushi)
  private val ldelem_u1 = one(0x91, "ldelem.u1", Next, InlineNone, Popref_popi, Pushi)
  private val ldelem_i2 = one(0x92, "ldelem.i2", Next, InlineNone, Popref_popi, Pushi)
  private val ldelem_u2 = one(0x93, "ldelem.u2", Next, InlineNone, Popref_popi, Pushi)
  private val ldelem_i4 = one(0x94, "ldelem.i4", Next, InlineNone, Popref_popi, Pushi)
  private val ldelem_u4 = one(0x95, "ldelem.u4", Next, InlineNone, Popref_popi, Pushi)
  private val ldelem_i8 = one(0x96, "ldelem.i8", Next, InlineNone, Popref_popi, Pushi8)
  private val ldelem_i = one(0x97, "ldelem.i", Next, InlineNone, Popref_popi, Pushi)
  private val ldelem_r4 = one(0x98, "ldelem.r4", Next, InlineNone, Popref_popi, Pushr4)
  private val ldelem_r8 = one(0x99, "ldelem.r8", Next, InlineNone, Popref_popi, Pushr8)
  private val ldelem_ref = one(0x9a, "ldelem.ref", Next, InlineNone, Popref_popi, Pushref)
  private val stelem_i = one(0x9b, "stelem.i", Next, InlineNone, Popref_popi_popi, Push0)
  private val stelem_i1 = one(0x9c, "stelem.i1", Next, InlineNone, Popref_popi_popi, Push0)
  private val stelem_i2 = one(0x9d, "stelem.i2", Next, InlineNone, Popref_popi_popi, Push0)
  private val stelem_i4 = one(0x9e, "stelem.i4", Next, InlineNone, Popref_popi_popi, Push0)
  private val stelem_i8 = one(0x9f, "stelem.i8", Next, InlineNone, Popref_popi_popi, Push0)
  private val stelem_r4 = one(0xa0, "stelem.r4", Next, InlineNone, Popref_popi_popi, Push0)
  private val stelem_r8 = one(0xa1, "stelem.r8", Next, InlineNone, Popref_popi_popi, Push0)
  private val stelem_ref = one(0xa2, "stelem.ref", Next, InlineNone, Popref_popi_popi, Push0)
  private val ldelem = one(0xa3, "ldelem.any", Next, InlineType, Popref_popi, Push1)
  private val stelem = one(0xa4, "stelem.any", Next, InlineType, Popref_popi_popi, Push0)
  private val unbox_any = one(0xa5, "unbox.any", Next, InlineType, Popref, Push1)
  private val conv_ovf_i1 = one(0xb3, "conv.ovf.i1", Next, InlineNone, Pop1, Pushi)
  private val conv_ovf_u1 = one(0xb4, "conv.ovf.u1", Next, InlineNone, Pop1, Pushi)
  private val conv_ovf_i2 = one(0xb5, "conv.ovf.i2", Next, InlineNone, Pop1, Pushi)
  private val conv_ovf_u2 = one(0xb6, "conv.ovf.u2", Next, InlineNone, Pop1, Pushi)
  private val conv_ovf_i4 = one(0xb7, "conv.ovf.i4", Next, InlineNone, Pop1, Pushi)
  private val conv_ovf_u4 = one(0xb8, "conv.ovf.u4", Next, InlineNone, Pop1, Pushi)
  private val conv_ovf_i8 = one(0xb9, "conv.ovf.i8", Next, InlineNone, Pop1, Pushi8)
  private val conv_ovf_u8 = one(0xba, "conv.ovf.u8", Next, InlineNone, Pop1, Pushi8)
  private val refanyval = one(0xbb, "refanyval", Next, InlineType, Pop1, Pushi)
  private val ckfinite = one(0xbc, "ckfinite", Next, InlineNone, Pop1, Pushr8)
  private val mkrefany = one(0xbd, "mkrefany", Next, InlineType, Popi, Push1)
  private val ldftn = one(0xc2, "ldftn", Next, InlineMethod, Pop0, Push1)
  private val ldvirtftn = one(0xc3, "ldvirtftn", Next, InlineMethod, Popref, Push1)
  private val ldarg = one(0xc5, "ldarg", Next, InlineArg, Pop0, Push1)
  private val ldarga = one(0xc6, "ldarga", Next, InlineArg, Pop0, Pushi)
  private val starg = one(0xc7, "starg", Next, InlineArg, Pop1, Push0)
  private val ldloc = one(0xc8, "ldloc", Next, InlineVar, Pop0, Push1)
  private val ldloca = one(0xc9, "ldloca", Next, InlineVar, Pop0, Pushi)
  private val stloc = one(0xca, "stloc", Next, InlineVar, Pop1, Push0)
  private val localloc = one(0xcb, "localloc", Next, InlineNone, Popi, Pushi)
  private val ldtoken = one(0xd0, "ldtoken", Next, InlineTok, Pop0, Pushi)
  private val conv_u2 = one(0xd1, "conv.u2", Next, InlineNone, Pop1, Pushi)
  private val conv_u1 = one(0xd2, "conv.u1", Next, InlineNone, Pop1, Pushi)
  private val conv_i = one(0xd3, "conv.i", Next, InlineNone, Pop1, Pushi)
  private val conv_ovf_i = one(0xd4, "conv.ovf.i", Next, InlineNone, Pop1, Pushi)
  private val conv_ovf_u = one(0xd5, "conv.ovf.u", Next, InlineNone, Pop1, Pushi)
  private val add_ovf = one(0xd6, "add.ovf", Next, InlineNone, Pop1_pop1, Push1)
  private val add_ovf_un = one(0xd7, "add.ovf.un", Next, InlineNone, Pop1_pop1, Push1)
  private val mul_ovf = one(0xd8, "mul.ovf", Next, InlineNone, Pop1_pop1, Push1)
  private val mul_ovf_un = one(0xd9, "mul.ovf.un", Next, InlineNone, Pop1_pop1, Push1)
  private val sub_ovf = one(0xda, "sub.ovf", Next, InlineNone, Pop1_pop1, Push1)
  private val sub_ovf_un = one(0xdb, "sub.ovf.un", Next, InlineNone, Pop1_pop1, Push1)
  private val endfinally = one(0xdc, "endfinally", Return, InlineNone, Pop0, Push0)
  private val leave = one(0xdd, "leave", Branch, InlineBr, Pop0, Push0)
  private val leave_s = one(0xde, "leave.s", Branch, ShortInlineBr, Pop0, Push0)
  private val stind_i = one(0xdf, "stind.i", Next, InlineNone, Popi_popi, Push0)
  private val conv_u = one(0xe0, "conv.u", Next, InlineNone, Pop1, Pushi)

  // ------------------------------------------------------------- two-byte

  private val arglist = two(0x00, "arglist", Next, InlineNone, Pop0, Pushi)
  private val ceq = two(0x01, "ceq", Next, InlineNone, Pop1_pop1, Pushi)
  private val cgt = two(0x02, "cgt", Next, InlineNone, Pop1_pop1, Pushi)
  private val cgt_un = two(0x03, "cgt.un", Next, InlineNone, Pop1_pop1, Pushi)
  private val clt = two(0x04, "clt", Next, InlineNone, Pop1_pop1, Pushi)
  private val clt_un = two(0x05, "clt.un", Next, InlineNone, Pop1_pop1, Pushi)
  private val ldftn2 = two(0x06, "ldftn", Next, InlineMethod, Pop0, Push1)
  private val ldvirtftn2 = two(0x07, "ldvirtftn", Next, InlineMethod, Popref, Push1)
  private val ldarg2 = two(0x09, "ldarg", Next, InlineArg, Pop0, Push1)
  private val ldarga2 = two(0x0a, "ldarga", Next, InlineArg, Pop0, Pushi)
  private val starg2 = two(0x0b, "starg", Next, InlineArg, Pop1, Push0)
  private val ldloc2 = two(0x0c, "ldloc", Next, InlineVar, Pop0, Push1)
  private val ldloca2 = two(0x0d, "ldloca", Next, InlineVar, Pop0, Pushi)
  private val stloc2 = two(0x0e, "stloc", Next, InlineVar, Pop1, Push0)
  private val localloc2 = two(0x0f, "localloc", Next, InlineNone, Popi, Pushi)
  private val endfilter = two(0x11, "endfilter", Return, InlineNone, Popi, Push0)
  private val unaligned = two(0x12, "unaligned.", Meta, ShortInlineI, Pop0, Push0)
  private val volatileOp = two(0x13, "volatile.", Meta, InlineNone, Pop0, Push0)
  private val tail = two(0x14, "tail.", Meta, InlineNone, Pop0, Push0)
  private val initobj = two(0x15, "initobj", Next, InlineType, Popi, Push0)
  private val constrained = two(0x16, "constrained.", Meta, InlineType, Pop0, Push0)
  private val cpblk = two(0x17, "cpblk", Next, InlineNone, Popi_popi_popi, Push0)
  private val initblk = two(0x18, "initblk", Next, InlineNone, Popi_popi_popi, Push0)
  private val no = two(0x19, "no.", Meta, ShortInlineI, Pop0, Push0)
  private val rethrow = two(0x1a, "rethrow", Throw, InlineNone, Pop0, Push0)
  private val sizeof = two(0x1c, "sizeof", Next, InlineType, Pop0, Pushi)
  private val refanytype = two(0x1d, "refanytype", Next, InlineNone, Pop1, Pushi)
  private val readonly = two(0x1e, "readonly.", Meta, InlineNone, Pop0, Push0)

  // The registration order above is the canonical order of `OpCodes.all`.

  def forCode(code: Int): Option[OpCode] = {
    val c1 = code & 0xff
    val c2 = (code >> 8) & 0xff
    if (c1 == 0xfe) {
      twoByteTable(c2)
    } else {
      oneByteTable(c1)
    }
  }

  def all: Vector[OpCode] = registered.toVector
}
