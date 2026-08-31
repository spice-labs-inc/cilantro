// EhReader — SEH exception-handler section parsing (ECMA-335 II.25.4.6).
//
// Faithful port of Mono.Cecil 0.11.6's CodeReader section handling (the
// pinned oracle), verified against the ilasm fixture goldens:
//
//   small section: [kind u8][size u8][...]  count = size / 12, then two
//                  bytes are skipped, then 12-byte clauses:
//                  flags u16 (masked with 0x7), tryOffset u16, tryLength
//                  u8, handlerOffset u16, handlerLength u8, then 4 bytes:
//                  catch -> type token, filter -> i32 offset, otherwise
//                  skipped.
//   fat section:   [kind u8][size u24]     count = size / 24, then
//                  24-byte clauses with the same fields as i32s.
//   offsets:       interpreted as IL offsets relative to the start of the
//                  IL stream in BOTH formats — Cecil applies no
//                  section-relative base (see ADR-0005).
//   MoreSects:     a section whose kind has bit 0x80 set is followed by
//                  another section.
//
// The reader is pure over byte arrays and returns raw clauses; target
// resolution into instruction indices happens in MethodBodyReader where
// the decoded instructions are known.

package io.spicelabs.cilantro.cil

import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import io.spicelabs.cilantro.{MetadataToken, TypeReference}

object EhReader {

  private final case class DecodeError(message: String) extends RuntimeException(message)

  sealed case class RawExceptionClause(
      handlerType: ExceptionHandlerType,
      tryOffset: Int,
      tryLength: Int,
      handlerOffset: Int,
      handlerLength: Int,
      catchType: Option[TypeReference],
      filterOffset: Option[Int]
  )

  // Parses ONE section (kind byte + size bytes + clause bytes) exactly as
  // Cecil's ReadSection/ReadSmallSection/ReadFatSection/ReadExceptionHandlers.
  def parseSection(
      section: Array[Byte],
      resolveCatchType: MetadataToken => Option[TypeReference]
  ): Try[Vector[RawExceptionClause]] = {
    Try { new SectionReader(section, resolveCatchType).parse() }
  }

  private final class SectionReader(section: Array[Byte], resolveCatchType: MetadataToken => Option[TypeReference]) {
    private var position = 0

    private def fail(message: String): Nothing = throw DecodeError(message)

    private def available(n: Int): Boolean = position + n <= section.length

    private def readU1(): Int = {
      if (!available(1)) {
        fail("truncated EH section: expected 1 byte at offset " + position)
      }
      val b = section(position)
      position += 1
      b.toInt & 0xff
    }

    private def readU2(): Int = {
      if (!available(2)) {
        fail("truncated EH section: expected 2 bytes at offset " + position)
      }
      val low = section(position).toInt & 0xff
      val high = section(position + 1).toInt & 0xff
      position += 2
      low | (high << 8)
    }

    private def readI4(): Int = {
      if (!available(4)) {
        fail("truncated EH section: expected 4 bytes at offset " + position)
      }
      val b0 = section(position).toInt & 0xff
      val b1 = section(position + 1).toInt & 0xff
      val b2 = section(position + 2).toInt & 0xff
      val b3 = section(position + 3).toInt & 0xff
      position += 4
      b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)
    }

    def parse(): Vector[RawExceptionClause] = {
      val kind = readU1()
      val fat = (kind & 0x40) != 0
      val count = if (fat) {
        // Cecil: Advance(-1) then count = (ReadInt32() >> 8) / 24.
        position -= 1
        val combined = readI4()
        val size = (combined >> 8) & 0x1fffffff
        if (size > section.length - position) {
          fail("truncated EH section: fat size exceeds section bytes")
        }
        size / 24
      } else {
        val size = readU1()
        if (size > section.length - position) {
          fail("truncated EH section: small size exceeds section bytes")
        }
        position += 2 // Cecil: Advance(2)
        size / 12
      }
      if (count > 0 && count * (if fat then 24 else 12) > section.length - position) {
        fail(s"invalid EH section: clause count $count exceeds the section data")
      }
      val clauses = ArrayBuffer.empty[RawExceptionClause]
      var i = 0
      while (i < count) {
        clauses.addOne(readClause(fat))
        i += 1
      }
      clauses.toVector
    }

    private def readClause(fat: Boolean): RawExceptionClause = {
      val flags = (if (fat) readI4() else readU2()) & 0x7
      val handlerType = flags match {
        case 0 => ExceptionHandlerType.Catch
        case 1 => ExceptionHandlerType.Filter
        case 2 => ExceptionHandlerType.Finally
        case 4 => ExceptionHandlerType.Fault
        case other => fail(f"invalid exception handler flags: 0x$other%x")
      }
      val tryOffset = if (fat) readI4() else readU2()
      val tryLength = if (fat) readI4() else readU1()
      val handlerOffset = if (fat) readI4() else readU2()
      val handlerLength = if (fat) readI4() else readU1()

      val (catchType, filterOffset) = handlerType match {
        case ExceptionHandlerType.Catch =>
          (resolveCatchType(MetadataToken(readI4())), None)
        case ExceptionHandlerType.Filter =>
          (None, Some(readI4()))
        case _ =>
          position += 4
          (None, None)
      }
      RawExceptionClause(handlerType, tryOffset, tryLength, handlerOffset, handlerLength, catchType, filterOffset)
    }
  }
}
