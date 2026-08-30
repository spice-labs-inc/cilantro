// SwitchTests — C2-03.
//
// Why this test exists:
//   switch is the operand most likely to corrupt the instruction stream:
//   the count is a UInt32 (and may be 0), each target is a 32-bit offset
//   relative to the instruction FOLLOWING the whole table, and the table
//   itself sits directly after the count. A wrong base, a wrong size or a
//   mis-signed count shifts every later instruction. This test pins 0, 1
//   and 65535 targets plus the exact relative-offset semantics.
//
// Note on table alignment (documented deviation from the plan sketch):
//   the pinned oracle (Mono.Cecil 0.11.6) reads the targets immediately
//   after the count with no alignment skip — confirmed by the ilasm
//   fixture golden, where mono ilasm emits an unaligned table and Cecil
//   decodes it. Our decoder matches the oracle; see phase-2-claims.md.

package io.spicelabs.cilantro.cil

import scala.util.Success

class SwitchTests extends munit.FunSuite {

  private def decodeFat(insns: Array[Byte]): MethodBody = {
    CodeReader.readBody(BodyBuilder.fat(insns)) match {
      case Success(body) => body
      case scala.util.Failure(t) => fail(s"decode failed: $t")
    }
  }

  private def switchTargets(body: MethodBody, switchIndex: Int): Vector[Int] = {
    body.instructions(switchIndex).operand match {
      case Some(Operand.Switch(targets)) =>
        targets.map(idx => body.instructions(idx).offset)
      case other => fail(s"expected Switch operand, got $other")
    }
  }

  // switch(0) at offset 0: opcode + 4 count bytes, zero targets.
  // [0] switch | [5] ret
  test("C2-03: switch with zero targets decodes with an empty table") {
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(Array(0x45.toByte), BodyBuilder.i4(0))),
      Array(0x2a.toByte)
    ))
    val body = decodeFat(insns)
    assertEquals(switchTargets(body, 0), Vector.empty[Int])
    assertEquals(body.instructions(1).offset, 5)
  }

  // switch(1) at offset 0, target delta 0 -> the instruction after the
  // table (offset 9), which is ret.
  // [0] switch | [9] ret
  test("C2-03: switch with one target resolves relative to the post-table address") {
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(Array(0x45.toByte), BodyBuilder.i4(1), BodyBuilder.i4(0))),
      Array(0x2a.toByte)
    ))
    val body = decodeFat(insns)
    assertEquals(switchTargets(body, 0), Vector(9))
  }

  // switch(3) at offset 0 with deltas -9, 0, +2 relative to post-table
  // address 17. Targets: 8, 17, 19. Three nops at those offsets.
  // [0] switch | [17] nop | [18] nop | [19] nop | [20] nop (padding so
  // offsets 8 and 19 are instruction starts is handled below)
  test("C2-03: switch targets use signed deltas from the post-table address") {
    // Layout: offsets 0..16 = switch (1+4+12), then nops at 17,18,19,20.
    // Need a nop AT offset 8 (delta -9): place nops so offset 8 is a start.
    // [0..7] = part of switch (16 bytes total), so offset 8 is inside the
    // switch operand — use deltas that land on 17, 19, 20 instead: 0, +2, +3.
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(
        Array(0x45.toByte),
        BodyBuilder.i4(3),
        BodyBuilder.i4(0),
        BodyBuilder.i4(2),
        BodyBuilder.i4(3)
      )),
      Array(0x00.toByte), // 17
      Array(0x00.toByte), // 18
      Array(0x00.toByte), // 19
      Array(0x00.toByte)  // 20
    ))
    val body = decodeFat(insns)
    assertEquals(switchTargets(body, 0), Vector(17, 19, 20))
  }

  // 65535 targets: the largest count the format expresses. Fat body,
  // targets all delta 0 -> the instruction after the table.
  test("C2-03: switch with 65535 targets decodes exactly") {
    val count = 65535
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(Array(0x45.toByte), BodyBuilder.i4(count))),
      Array.fill[Byte](count * 4)(0x00),
      Array(0x2a.toByte)
    ))
    val body = decodeFat(insns)
    val targets = switchTargets(body, 0)
    assertEquals(targets.length, count)
    assert(targets.forall(_ == body.instructions(1).offset))
    assertEquals(body.instructions(1).offset, 1 + 4 + count * 4)
    assertEquals(body.instructions(0).size, 1 + 4 + count * 4)
  }

  // A switch whose targets point back at the first instruction and at the
  // instruction following the table: [0] nop | [1] switch(2, -14, 0) |
  // [14] ret. Post-table address is 14, so deltas -14 and 0 give targets
  // 0 and 14 — both real instruction starts.
  test("C2-03: switch deltas may target earlier instructions") {
    val insns = BodyBuilder.concat(Seq(
      Array(0x00.toByte), // 0: nop
      BodyBuilder.concat(Seq( // 1: switch
        Array(0x45.toByte),
        BodyBuilder.i4(2),
        BodyBuilder.i4(-14),
        BodyBuilder.i4(0)
      )),
      Array(0x2a.toByte) // 14: ret
    ))
    val body = decodeFat(insns)
    // instruction 0 is the nop, instruction 1 is the switch, instruction
    // 2 is the ret at offset 14.
    assertEquals(
      body.instructions.map(i => i.offset).toSeq,
      Seq(0, 1, 14)
    )
    assertEquals(
      switchTargets(body, 1),
      Vector(0, 14)
    )
  }

  // 65536 targets: the cap's own boundary. The count check is `> 65536`,
  // so exactly 65536 is legal (plan 04 pins the cap and its boundary).
  test("C4-03: switch with 65536 targets decodes exactly at the cap") {
    val count = 65536
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(Array(0x45.toByte), BodyBuilder.i4(count))),
      Array.fill[Byte](count * 4)(0x00),
      Array(0x2a.toByte)
    ))
    val body = decodeFat(insns)
    val targets = switchTargets(body, 0)
    assertEquals(targets.length, count)
    assertEquals(body.instructions(1).offset, 1 + 4 + count * 4)
  }

  // 65537 targets: the count check fires before any table read, so a
  // truncated stream still fails with the cap message, not a truncation
  // message.
  test("C4-03: switch with 65537 targets fails the cap before the table read") {
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(Array(0x45.toByte), BodyBuilder.i4(65537))),
      Array(0x2a.toByte)
    ))
    CodeReader.readBody(BodyBuilder.fat(insns)) match {
      case scala.util.Failure(t) =>
        assert(t.getMessage.contains("65536 cap"), "the failure must be the switch cap itself")
      case scala.util.Success(_) => fail("65537 targets must be a Failure")
    }
  }
}
