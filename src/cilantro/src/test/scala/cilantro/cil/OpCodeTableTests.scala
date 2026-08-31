// OpCodeTableTests — C2-01.
//
// Why this test exists:
//   The opcode table is the foundation of the decoder: a wrong name, code
//   value, operand type or stack behaviour silently corrupts every decoded
//   body. This test re-transcribes the ECMA-335 table (Partition III,
//   including the 0xFE two-byte forms and Cecil's prefixes) independently
//   of the implementation and pins the full enumeration — not a sample.
//
// Theory of the test:
//   - Every line of the transcription is looked up by code value and all
//     six properties are compared.
//   - A reverse pass walks OpCodes.all and asserts every registered opcode
//     appears in the transcription, so extra or missing entries fail.
//   - Undefined code values (one-byte 0x24, two-byte 0x08) yield None.
//   - The dotted prefix names and the two-byte size rule are pinned.

package io.spicelabs.cilantro.cil

class OpCodeTableTests extends munit.FunSuite {

  // code1 code2(-1 = one-byte) name flowControl operandType pop push
  private val table: String =
    """00 -1 nop Next InlineNone Pop0 Push0
      |01 -1 break Break InlineNone Pop0 Push0
      |02 -1 ldarg.0 Next InlineNone Pop0 Push1
      |03 -1 ldarg.1 Next InlineNone Pop0 Push1
      |04 -1 ldarg.2 Next InlineNone Pop0 Push1
      |05 -1 ldarg.3 Next InlineNone Pop0 Push1
      |06 -1 ldloc.0 Next InlineNone Pop0 Push1
      |07 -1 ldloc.1 Next InlineNone Pop0 Push1
      |08 -1 ldloc.2 Next InlineNone Pop0 Push1
      |09 -1 ldloc.3 Next InlineNone Pop0 Push1
      |0a -1 stloc.0 Next InlineNone Pop1 Push0
      |0b -1 stloc.1 Next InlineNone Pop1 Push0
      |0c -1 stloc.2 Next InlineNone Pop1 Push0
      |0d -1 stloc.3 Next InlineNone Pop1 Push0
      |0e -1 ldarg.s Next ShortInlineArg Pop0 Push1
      |0f -1 ldarga.s Next ShortInlineArg Pop0 Pushi
      |10 -1 starg.s Next ShortInlineArg Pop1 Push0
      |11 -1 ldloc.s Next ShortInlineVar Pop0 Push1
      |12 -1 ldloca.s Next ShortInlineVar Pop0 Pushi
      |13 -1 stloc.s Next ShortInlineVar Pop1 Push0
      |14 -1 ldnull Next InlineNone Pop0 Pushref
      |15 -1 ldc.i4.m1 Next InlineNone Pop0 Pushi
      |16 -1 ldc.i4.0 Next InlineNone Pop0 Pushi
      |17 -1 ldc.i4.1 Next InlineNone Pop0 Pushi
      |18 -1 ldc.i4.2 Next InlineNone Pop0 Pushi
      |19 -1 ldc.i4.3 Next InlineNone Pop0 Pushi
      |1a -1 ldc.i4.4 Next InlineNone Pop0 Pushi
      |1b -1 ldc.i4.5 Next InlineNone Pop0 Pushi
      |1c -1 ldc.i4.6 Next InlineNone Pop0 Pushi
      |1d -1 ldc.i4.7 Next InlineNone Pop0 Pushi
      |1e -1 ldc.i4.8 Next InlineNone Pop0 Pushi
      |1f -1 ldc.i4.s Next ShortInlineI Pop0 Pushi
      |20 -1 ldc.i4 Next InlineI Pop0 Pushi
      |21 -1 ldc.i8 Next InlineI8 Pop0 Pushi8
      |22 -1 ldc.r4 Next ShortInlineR Pop0 Pushr4
      |23 -1 ldc.r8 Next InlineR Pop0 Pushr8
      |25 -1 dup Next InlineNone Pop1 Push1_push1
      |26 -1 pop Next InlineNone Pop1 Push0
      |27 -1 jmp Call InlineMethod Pop0 Push0
      |28 -1 call Call InlineMethod Varpop Varpush
      |29 -1 calli Call InlineSig Varpop Varpush
      |2a -1 ret Return InlineNone Pop1 Push0
      |2b -1 br.s Branch ShortInlineBrTarget Pop0 Push0
      |2c -1 brfalse.s Cond_Branch ShortInlineBrTarget Popi Push0
      |2d -1 brtrue.s Cond_Branch ShortInlineBrTarget Popi Push0
      |2e -1 beq.s Cond_Branch ShortInlineBrTarget Pop1_pop1 Push0
      |2f -1 bge.s Cond_Branch ShortInlineBrTarget Pop1_pop1 Push0
      |30 -1 bgt.s Cond_Branch ShortInlineBrTarget Pop1_pop1 Push0
      |31 -1 ble.s Cond_Branch ShortInlineBrTarget Pop1_pop1 Push0
      |32 -1 blt.s Cond_Branch ShortInlineBrTarget Pop1_pop1 Push0
      |33 -1 bne.un.s Cond_Branch ShortInlineBrTarget Pop1_pop1 Push0
      |34 -1 bge.un.s Cond_Branch ShortInlineBrTarget Pop1_pop1 Push0
      |35 -1 bgt.un.s Cond_Branch ShortInlineBrTarget Pop1_pop1 Push0
      |36 -1 ble.un.s Cond_Branch ShortInlineBrTarget Pop1_pop1 Push0
      |37 -1 blt.un.s Cond_Branch ShortInlineBrTarget Pop1_pop1 Push0
      |38 -1 br Branch InlineBrTarget Pop0 Push0
      |39 -1 brfalse Cond_Branch InlineBrTarget Popi Push0
      |3a -1 brtrue Cond_Branch InlineBrTarget Popi Push0
      |3b -1 beq Cond_Branch InlineBrTarget Pop1_pop1 Push0
      |3c -1 bge Cond_Branch InlineBrTarget Pop1_pop1 Push0
      |3d -1 bgt Cond_Branch InlineBrTarget Pop1_pop1 Push0
      |3e -1 ble Cond_Branch InlineBrTarget Pop1_pop1 Push0
      |3f -1 blt Cond_Branch InlineBrTarget Pop1_pop1 Push0
      |40 -1 bne.un Cond_Branch InlineBrTarget Pop1_pop1 Push0
      |41 -1 bge.un Cond_Branch InlineBrTarget Pop1_pop1 Push0
      |42 -1 bgt.un Cond_Branch InlineBrTarget Pop1_pop1 Push0
      |43 -1 ble.un Cond_Branch InlineBrTarget Pop1_pop1 Push0
      |44 -1 blt.un Cond_Branch InlineBrTarget Pop1_pop1 Push0
      |45 -1 switch Cond_Branch InlineSwitch Popi Push0
      |46 -1 ldind.i1 Next InlineNone Popi Pushi
      |47 -1 ldind.u1 Next InlineNone Popi Pushi
      |48 -1 ldind.i2 Next InlineNone Popi Pushi
      |49 -1 ldind.u2 Next InlineNone Popi Pushi
      |4a -1 ldind.i4 Next InlineNone Popi Pushi
      |4b -1 ldind.u4 Next InlineNone Popi Pushi
      |4c -1 ldind.i8 Next InlineNone Popi Pushi8
      |4d -1 ldind.i Next InlineNone Popi Pushi
      |4e -1 ldind.r4 Next InlineNone Popi Pushr4
      |4f -1 ldind.r8 Next InlineNone Popi Pushr8
      |50 -1 ldind.ref Next InlineNone Popi Pushref
      |51 -1 stind.ref Next InlineNone Popi_popi Push0
      |52 -1 stind.i1 Next InlineNone Popi_popi Push0
      |53 -1 stind.i2 Next InlineNone Popi_popi Push0
      |54 -1 stind.i4 Next InlineNone Popi_popi Push0
      |55 -1 stind.i8 Next InlineNone Popi_popi Push0
      |56 -1 stind.r4 Next InlineNone Popi_popi Push0
      |57 -1 stind.r8 Next InlineNone Popi_popi Push0
      |58 -1 add Next InlineNone Pop1_pop1 Push1
      |59 -1 sub Next InlineNone Pop1_pop1 Push1
      |5a -1 mul Next InlineNone Pop1_pop1 Push1
      |5b -1 div Next InlineNone Pop1_pop1 Push1
      |5c -1 div.un Next InlineNone Pop1_pop1 Push1
      |5d -1 rem Next InlineNone Pop1_pop1 Push1
      |5e -1 rem.un Next InlineNone Pop1_pop1 Push1
      |5f -1 and Next InlineNone Pop1_pop1 Push1
      |60 -1 or Next InlineNone Pop1_pop1 Push1
      |61 -1 xor Next InlineNone Pop1_pop1 Push1
      |62 -1 shl Next InlineNone Pop1_pop1 Push1
      |63 -1 shr Next InlineNone Pop1_pop1 Push1
      |64 -1 shr.un Next InlineNone Pop1_pop1 Push1
      |65 -1 neg Next InlineNone Pop1 Push1
      |66 -1 not Next InlineNone Pop1 Push1
      |67 -1 conv.i1 Next InlineNone Pop1 Pushi
      |68 -1 conv.i2 Next InlineNone Pop1 Pushi
      |69 -1 conv.i4 Next InlineNone Pop1 Pushi
      |6a -1 conv.i8 Next InlineNone Pop1 Pushi8
      |6b -1 conv.r4 Next InlineNone Pop1 Pushr4
      |6c -1 conv.r8 Next InlineNone Pop1 Pushr8
      |6d -1 conv.u4 Next InlineNone Pop1 Pushi
      |6e -1 conv.u8 Next InlineNone Pop1 Pushi8
      |6f -1 callvirt Call InlineMethod Varpop Varpush
      |70 -1 cpobj Next InlineType Popi_popi Push0
      |71 -1 ldobj Next InlineType Popi Push1
      |72 -1 ldstr Next InlineString Pop0 Pushref
      |73 -1 newobj Call InlineMethod Varpop Pushref
      |74 -1 castclass Next InlineType Popref Pushref
      |75 -1 isinst Next InlineType Popref Pushi
      |76 -1 conv.r.un Next InlineNone Pop1 Pushr8
      |79 -1 unbox Next InlineType Popref Pushi
      |7a -1 throw Throw InlineNone Popref Push0
      |7b -1 ldfld Next InlineField Popref Push1
      |7c -1 ldflda Next InlineField Popref Push1
      |7d -1 stfld Next InlineField Pop1_pop1 Push0
      |7e -1 ldsfld Next InlineField Pop0 Push1
      |7f -1 ldsflda Next InlineField Pop0 Push1
      |80 -1 stsfld Next InlineField Pop1 Push0
      |81 -1 stobj Next InlineType Pop1_pop1 Push0
      |82 -1 conv.ovf.i1.un Next InlineNone Pop1 Pushi
      |83 -1 conv.ovf.i2.un Next InlineNone Pop1 Pushi
      |84 -1 conv.ovf.i4.un Next InlineNone Pop1 Pushi
      |85 -1 conv.ovf.i8.un Next InlineNone Pop1 Pushi8
      |86 -1 conv.ovf.u1.un Next InlineNone Pop1 Pushi
      |87 -1 conv.ovf.u2.un Next InlineNone Pop1 Pushi
      |88 -1 conv.ovf.u4.un Next InlineNone Pop1 Pushi
      |89 -1 conv.ovf.u8.un Next InlineNone Pop1 Pushi8
      |8a -1 conv.ovf.i.un Next InlineNone Pop1 Pushi
      |8b -1 conv.ovf.u.un Next InlineNone Pop1 Pushi
      |8c -1 box Next InlineType Pop1 Pushref
      |8d -1 newarr Next InlineType Popi Pushref
      |8e -1 ldlen Next InlineNone Pop1 Pushi
      |8f -1 ldelema Next InlineType Popref_popi Pushi
      |90 -1 ldelem.i1 Next InlineNone Popref_popi Pushi
      |91 -1 ldelem.u1 Next InlineNone Popref_popi Pushi
      |92 -1 ldelem.i2 Next InlineNone Popref_popi Pushi
      |93 -1 ldelem.u2 Next InlineNone Popref_popi Pushi
      |94 -1 ldelem.i4 Next InlineNone Popref_popi Pushi
      |95 -1 ldelem.u4 Next InlineNone Popref_popi Pushi
      |96 -1 ldelem.i8 Next InlineNone Popref_popi Pushi8
      |97 -1 ldelem.i Next InlineNone Popref_popi Pushi
      |98 -1 ldelem.r4 Next InlineNone Popref_popi Pushr4
      |99 -1 ldelem.r8 Next InlineNone Popref_popi Pushr8
      |9a -1 ldelem.ref Next InlineNone Popref_popi Pushref
      |9b -1 stelem.i Next InlineNone Popref_popi_popi Push0
      |9c -1 stelem.i1 Next InlineNone Popref_popi_popi Push0
      |9d -1 stelem.i2 Next InlineNone Popref_popi_popi Push0
      |9e -1 stelem.i4 Next InlineNone Popref_popi_popi Push0
      |9f -1 stelem.i8 Next InlineNone Popref_popi_popi Push0
      |a0 -1 stelem.r4 Next InlineNone Popref_popi_popi Push0
      |a1 -1 stelem.r8 Next InlineNone Popref_popi_popi Push0
      |a2 -1 stelem.ref Next InlineNone Popref_popi_popi Push0
      |a3 -1 ldelem.any Next InlineType Popref_popi Push1
      |a4 -1 stelem.any Next InlineType Popref_popi_popi Push0
      |a5 -1 unbox.any Next InlineType Popref Push1
      |b3 -1 conv.ovf.i1 Next InlineNone Pop1 Pushi
      |b4 -1 conv.ovf.u1 Next InlineNone Pop1 Pushi
      |b5 -1 conv.ovf.i2 Next InlineNone Pop1 Pushi
      |b6 -1 conv.ovf.u2 Next InlineNone Pop1 Pushi
      |b7 -1 conv.ovf.i4 Next InlineNone Pop1 Pushi
      |b8 -1 conv.ovf.u4 Next InlineNone Pop1 Pushi
      |b9 -1 conv.ovf.i8 Next InlineNone Pop1 Pushi8
      |ba -1 conv.ovf.u8 Next InlineNone Pop1 Pushi8
      |bb -1 refanyval Next InlineType Pop1 Pushi
      |bc -1 ckfinite Next InlineNone Pop1 Pushr8
      |bd -1 mkrefany Next InlineType Popi Push1
      |c2 -1 ldftn Next InlineMethod Pop0 Push1
      |c3 -1 ldvirtftn Next InlineMethod Popref Push1
      |c5 -1 ldarg Next InlineArg Pop0 Push1
      |c6 -1 ldarga Next InlineArg Pop0 Pushi
      |c7 -1 starg Next InlineArg Pop1 Push0
      |c8 -1 ldloc Next InlineVar Pop0 Push1
      |c9 -1 ldloca Next InlineVar Pop0 Pushi
      |ca -1 stloc Next InlineVar Pop1 Push0
      |cb -1 localloc Next InlineNone Popi Pushi
      |d0 -1 ldtoken Next InlineTok Pop0 Pushi
      |d1 -1 conv.u2 Next InlineNone Pop1 Pushi
      |d2 -1 conv.u1 Next InlineNone Pop1 Pushi
      |d3 -1 conv.i Next InlineNone Pop1 Pushi
      |d4 -1 conv.ovf.i Next InlineNone Pop1 Pushi
      |d5 -1 conv.ovf.u Next InlineNone Pop1 Pushi
      |d6 -1 add.ovf Next InlineNone Pop1_pop1 Push1
      |d7 -1 add.ovf.un Next InlineNone Pop1_pop1 Push1
      |d8 -1 mul.ovf Next InlineNone Pop1_pop1 Push1
      |d9 -1 mul.ovf.un Next InlineNone Pop1_pop1 Push1
      |da -1 sub.ovf Next InlineNone Pop1_pop1 Push1
      |db -1 sub.ovf.un Next InlineNone Pop1_pop1 Push1
      |dc -1 endfinally Return InlineNone Pop0 Push0
      |dd -1 leave Branch InlineBrTarget Pop0 Push0
      |de -1 leave.s Branch ShortInlineBrTarget Pop0 Push0
      |df -1 stind.i Next InlineNone Popi_popi Push0
      |e0 -1 conv.u Next InlineNone Pop1 Pushi
      |fe 00 arglist Next InlineNone Pop0 Pushi
      |fe 01 ceq Next InlineNone Pop1_pop1 Pushi
      |fe 02 cgt Next InlineNone Pop1_pop1 Pushi
      |fe 03 cgt.un Next InlineNone Pop1_pop1 Pushi
      |fe 04 clt Next InlineNone Pop1_pop1 Pushi
      |fe 05 clt.un Next InlineNone Pop1_pop1 Pushi
      |fe 06 ldftn Next InlineMethod Pop0 Push1
      |fe 07 ldvirtftn Next InlineMethod Popref Push1
      |fe 09 ldarg Next InlineArg Pop0 Push1
      |fe 0a ldarga Next InlineArg Pop0 Pushi
      |fe 0b starg Next InlineArg Pop1 Push0
      |fe 0c ldloc Next InlineVar Pop0 Push1
      |fe 0d ldloca Next InlineVar Pop0 Pushi
      |fe 0e stloc Next InlineVar Pop1 Push0
      |fe 0f localloc Next InlineNone Popi Pushi
      |fe 11 endfilter Return InlineNone Popi Push0
      |fe 12 unaligned. Meta ShortInlineI Pop0 Push0
      |fe 13 volatile. Meta InlineNone Pop0 Push0
      |fe 14 tail. Meta InlineNone Pop0 Push0
      |fe 15 initobj Next InlineType Popi Push0
      |fe 16 constrained. Meta InlineType Pop0 Push0
      |fe 17 cpblk Next InlineNone Popi_popi_popi Push0
      |fe 18 initblk Next InlineNone Popi_popi_popi Push0
      |fe 19 no. Meta ShortInlineI Pop0 Push0
      |fe 1a rethrow Throw InlineNone Pop0 Push0
      |fe 1c sizeof Next InlineType Pop0 Pushi
      |fe 1d refanytype Next InlineNone Pop1 Pushi
      |fe 1e readonly. Meta InlineNone Pop0 Push0
      |""".stripMargin

  private final case class Expected(
      code: Int,
      name: String,
      operandType: String,
      flowControl: String,
      pop: String,
      push: String
  )

  private def parseTable(): Seq[Expected] = {
    table.linesIterator.filter(_.trim.nonEmpty).map { line =>
      val parts = line.trim.split("\\s+")
      assertEquals(parts.length, 7, s"table line must have 7 columns: $line")
      val code1 = Integer.parseInt(parts(0), 16)
      val code2 = Integer.parseInt(parts(1), 16)
      // Cecil's OpCode.Code convention: one-byte opcodes carry 0xFF in the
      // high byte (0xff00 | code1); two-byte opcodes are 0xfe | (code2 << 8).
      val code = if (code2 < 0) (0xff00 | code1) else (0xfe | (code2 << 8))
      Expected(code, parts(2), parts(4), parts(3), parts(5), parts(6))
    }.toSeq
  }

  test("C2-01: every transcribed opcode matches name/code/operand/flow/stack") {
    parseTable().foreach { expected =>
      val op = OpCodes.forCode(expected.code).getOrElse(
        fail(s"opcode not found: 0x${expected.code.toHexString} (${expected.name})")
      )
      assertEquals(op.name, expected.name, s"name for ${expected.name}")
      assertEquals(op.code, expected.code, s"code for ${expected.name}")
      assertEquals(op.operandType.toString, expected.operandType, s"operandType for ${expected.name}")
      assertEquals(op.flowControl.toString, expected.flowControl, s"flowControl for ${expected.name}")
      assertEquals(op.stackBehaviourPop.toString, expected.pop, s"pop for ${expected.name}")
      assertEquals(op.stackBehaviourPush.toString, expected.push, s"push for ${expected.name}")
      val isTwoByte = (expected.code & 0xff) == 0xfe
    assertEquals(op.size, if (isTwoByte) 2 else 1, s"size for ${expected.name}")
    }
  }

  test("C2-01: the table has no entries the transcription does not list") {
    val expectedCodes = parseTable().map(_.code).toSet
    OpCodes.all.foreach { op =>
      assert(
        expectedCodes.contains(op.code),
        s"registered opcode not in the transcription: ${op.name} (0x${op.code.toHexString})"
      )
    }
    assertEquals(OpCodes.all.length, expectedCodes.size)
  }

  test("C2-01: undefined code values yield None") {
    assertEquals(OpCodes.forCode(0x24), None)
    assertEquals(OpCodes.forCode(0x08fe), None)
    assertEquals(OpCodes.forCode(0x1bfe), None)
    assertEquals(OpCodes.forCode(0xff), None)
  }

  test("C2-01: dotted prefix names are pinned") {
    assertEquals(OpCodes.forCode(0x12fe).map(_.name), Some("unaligned."))
    assertEquals(OpCodes.forCode(0x13fe).map(_.name), Some("volatile."))
    assertEquals(OpCodes.forCode(0x14fe).map(_.name), Some("tail."))
    assertEquals(OpCodes.forCode(0x16fe).map(_.name), Some("constrained."))
    assertEquals(OpCodes.forCode(0x19fe).map(_.name), Some("no."))
    assertEquals(OpCodes.forCode(0x1efe).map(_.name), Some("readonly."))
    assertEquals(OpCodes.forCode(0x00fe).map(_.name), Some("arglist"))
  }
}
