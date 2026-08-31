// EhBuilder — builds synthetic EH section bytes for the C3 reader tests.
//
// Why this exists:
//   The EH reader tests construct raw sections by hand so the
//   expectations are independent of the reader. The layouts follow the
//   oracle (Mono.Cecil 0.11.6) as recovered from its source and pinned by
//   the ilasm fixture goldens:
//     small: [kind 0x01][size][2 skipped bytes][12-byte clauses]
//     fat:   [kind 0x41][3 size bytes][24-byte clauses]
//
// LLM-friendly notes:
//   - small clause: flags u16, tryOff u16, tryLen u8, handOff u16,
//     handLen u8, extra 4 bytes (catch token / filter offset / padding).
//   - fat clause: same fields as i32s.
//   - The size byte/word encodes the clause count: count = size / 12
//     (small) or size / 24 (fat).

package io.spicelabs.cilantro.cil

object EhBuilder {

  def smallClause(
      flags: Int,
      tryOff: Int,
      tryLen: Int,
      handOff: Int,
      handLen: Int,
      extra: Array[Byte]
  ): Array[Byte] =
    BodyBuilder.i2(flags) ++ BodyBuilder.i2(tryOff) ++ BodyBuilder.i1(tryLen) ++
      BodyBuilder.i2(handOff) ++ BodyBuilder.i1(handLen) ++ extra

  def fatClause(
      flags: Int,
      tryOff: Int,
      tryLen: Int,
      handOff: Int,
      handLen: Int,
      extra: Array[Byte]
  ): Array[Byte] =
    BodyBuilder.i4(flags) ++ BodyBuilder.i4(tryOff) ++ BodyBuilder.i4(tryLen) ++
      BodyBuilder.i4(handOff) ++ BodyBuilder.i4(handLen) ++ extra

  def smallSection(clauses: Array[Byte]): Array[Byte] = {
    // Cecil: count = sizeByte / 12, then 2 bytes are skipped.
    Array(0x01.toByte, (clauses.length + 2).toByte, 0x00.toByte, 0x00.toByte) ++ clauses
  }

  def fatSection(clauses: Array[Byte]): Array[Byte] = {
    // Cecil: combined i32 = kind | (size << 8); count = size / 24.
    val size = clauses.length
    Array(0x41.toByte, (size & 0xff).toByte, ((size >> 8) & 0xff).toByte, ((size >> 16) & 0xff).toByte) ++ clauses
  }

  def catchToken(rid: Int): Array[Byte] = BodyBuilder.i4(0x01000000 | rid)

  def filterOffset(offset: Int): Array[Byte] = BodyBuilder.i4(offset)

  def padding: Array[Byte] = BodyBuilder.i4(0)
}
