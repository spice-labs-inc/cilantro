// BodyBuilder — builds raw ECMA-335 method-body byte arrays for the C2
// decoder tests.
//
// Why this exists:
//   The decoder consumes raw bytes; the tests must produce them by hand so
//   the expectations are independent of the decoder itself. tiny() emits
//   the 1-byte header format (code size in the high 6 bits), fat() the
//   12-byte header (flags, max stack, code size, local-sig token).
//
// LLM-friendly notes:
//   - Little-endian helpers mirror ECMA-335 II.25.4.
//   - No nulls, no throws; assertions live in the test suites.

package io.spicelabs.cilantro.cil

import java.io.ByteArrayOutputStream

object BodyBuilder {

  def i1(v: Int): Array[Byte] = Array(v.toByte)

  def i2(v: Int): Array[Byte] =
    Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte)

  def i4(v: Int): Array[Byte] =
    Array(
      (v & 0xff).toByte,
      ((v >> 8) & 0xff).toByte,
      ((v >> 16) & 0xff).toByte,
      ((v >> 24) & 0xff).toByte
    )

  def i8(v: Long): Array[Byte] =
    i4((v & 0xffffffffL).toInt) ++ i4(((v >> 32) & 0xffffffffL).toInt)

  def f4(v: Float): Array[Byte] = i4(java.lang.Float.floatToIntBits(v))

  def f8(v: Double): Array[Byte] = i8(java.lang.Double.doubleToLongBits(v))

  def concat(parts: Seq[Array[Byte]]): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    parts.foreach(out.write(_))
    out.toByteArray
  }

  // Tiny format: 1-byte header, 6-bit code size, max stack 8, no locals.
  def tiny(insns: Array[Byte]): Array[Byte] = {
    val codeSize = insns.length
    require(codeSize <= 63, "tiny format holds at most 63 bytes of code")
    Array((0x02 | (codeSize << 2)).toByte) ++ insns
  }

  // Fat format: 12-byte header.
  def fat(
      insns: Array[Byte],
      maxStack: Int = 8,
      initLocals: Boolean = true,
      moreSects: Boolean = false,
      localSigToken: Int = 0
  ): Array[Byte] = {
    val flags = 0x03 | (if initLocals then 0x10 else 0) | (if moreSects then 0x08 else 0)
    concat(Seq(i2(flags), i2(maxStack), i4(insns.length), i4(localSigToken), insns))
  }
}
