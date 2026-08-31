// MethodBodyReader — end-to-end method-body reading.
//
// Wires the Phase 2 decoder and the Phase 3 EH reader to a
// MethodDefinition: RVA -> file offset via the PE section mapping,
// header parse, body-size cap (64 MB from plan 04), code decode, EH
// section walk (4-byte alignment relative to the body start, MoreSects
// chains), clause validation, and target resolution into instruction
// indices.
//
// Contract: Try[Option[MethodBody]] — Success(None) for RVA == 0
// (abstract / P/Invoke) or non-body methods (internal call, runtime,
// native, unmanaged); Success(Some(body)) otherwise; Failure for every
// malformed body, never a throw.

package io.spicelabs.cilantro.cil

import scala.collection.mutable.ArrayBuffer
import scala.util.{Success, Try}
import io.spicelabs.cilantro.{MethodDefinition, MetadataToken, ModuleDefinition, TypeReference}
import io.spicelabs.cilantro.PE.BinaryStreamReader

object MethodBodyReader {

  // Resource cap from plan 04: method bodies are bounded at 64 MB.
  val MaxBodySize: Int = 64 * 1024 * 1024

  private final case class DecodeError(message: String) extends RuntimeException(message)

  private def fail(message: String): Nothing = throw DecodeError(message)

  def readBody(method: MethodDefinition): Try[Option[MethodBody]] = {
    if (method.RVA == 0 || !method.hasBody) {
      Success(None)
    } else {
      Try { Some(readFromImage(method)) }
    }
  }

  private def readFromImage(method: MethodDefinition): MethodBody = {
    val module = method.module.getOrElse(fail("method has no module"))
    val image = module.image.getOrElse(fail("module has no image"))
    val reader = image.getReaderAt(method.RVA).getOrElse(
      fail(f"method RVA 0x${method.RVA}%x is not mapped to any section")
    )
    val bodyStart = reader.position
    readBodyBytes(reader, bodyStart, module, method)
  }

  private def readBodyBytes(
      reader: BinaryStreamReader,
      bodyStart: Int,
      module: ModuleDefinition,
      method: MethodDefinition
  ): MethodBody = {
    val first = reader.readByte().toInt & 0xff
    reader.moveTo(bodyStart)
    (first & 0x3) match {
      case 0x2 =>
        // Tiny: header + code, no extra sections. The Phase 2 decoder
        // re-parses the header from the full body bytes.
        val full = reader.readBytes(1 + (first >> 2))
        decodeFull(module, full, 8, initLocals = false, Vector.empty, 0, method)

      case 0x3 =>
        if (bodyStart + 12 > reader.length) {
          fail("truncated body: fat header needs 12 bytes")
        }
        val header = reader.readBytes(12)
        val flags = (header(0).toInt & 0xff) | ((header(1).toInt & 0xff) << 8)
        val maxStack = (header(2).toInt & 0xff) | ((header(3).toInt & 0xff) << 8)
        val codeSize = (header(4).toInt & 0xff) | ((header(5).toInt & 0xff) << 8) |
          ((header(6).toInt & 0xff) << 16) | ((header(7).toInt & 0xff) << 24)
        val localSigToken = (header(8).toInt & 0xff) | ((header(9).toInt & 0xff) << 8) |
          ((header(10).toInt & 0xff) << 16) | ((header(11).toInt & 0xff) << 24)
        if (codeSize < 0) {
          fail("invalid body: negative code size " + codeSize)
        }
        if (codeSize > MaxBodySize) {
          fail(s"method body exceeds the 64 MB cap: $codeSize bytes")
        }
        val code = reader.readBytes(codeSize)
        val sections = readSections(reader, bodyStart, flags)
        decodeFull(module, header ++ code, maxStack, (flags & 0x10) != 0, sections, localSigToken, method)

      case other =>
        fail(f"invalid body: method header format bits $other%x (expected 0x2 tiny or 0x3 fat)")
    }
  }

  // Walks the extra sections following the code. Each section is 4-byte
  // aligned relative to the body start; kind bit 0x40 selects the fat
  // section shape (3-byte data size), bit 0x80 chains the next section.
  // Each section is captured verbatim (kind + size + data) for EhReader.
  private def readSections(
      reader: BinaryStreamReader,
      bodyStart: Int,
      headerFlags: Int
  ): Vector[Array[Byte]] = {
    val sections = ArrayBuffer.empty[Array[Byte]]
    var more = (headerFlags & 0x08) != 0
    while (more) {
      val relative = reader.position - bodyStart
      reader.moveTo(bodyStart + ((relative + 3) & ~3))
      if (reader.position >= reader.length) {
        fail("truncated body: section kind byte missing")
      }
      val kind = reader.readByte().toInt & 0xff
      if ((kind & 0x40) != 0) {
        if (reader.position + 3 > reader.length) {
          fail("truncated body: fat section header needs 4 bytes")
        }
        // Cecil: Advance(-1); the 4-byte read covers kind + 3 size bytes.
        reader.moveTo(reader.position - 1)
        val combined = reader.readInt32()
        val size = (combined >> 8) & 0x1fffffff
        val data = reader.readBytes(size)
        sections.addOne(Array(kind.toByte) ++ int24Bytes(combined >> 8) ++ data)
      } else {
        val size = reader.readByte().toInt & 0xff
        val data = reader.readBytes(size)
        sections.addOne(Array(kind.toByte, size.toByte) ++ data)
      }
      more = (kind & 0x80) != 0
    }
    sections.toVector
  }

  private def int24Bytes(value: Int): Array[Byte] = Array(
    (value & 0xff).toByte,
    ((value >> 8) & 0xff).toByte,
    ((value >> 16) & 0xff).toByte
  )

  private def decodeFull(
      module: ModuleDefinition,
      full: Array[Byte],
      maxStack: Int,
      initLocals: Boolean,
      sections: Vector[Array[Byte]],
      localSigToken: Int,
      method: MethodDefinition
  ): MethodBody = {
    val resolveCatchType: MetadataToken => Option[TypeReference] = token => {
      module.read(token, (_, reader) => reader.getTypeDefOrRef(token))
    }
    val resolveString: Int => Option[String] = token => {
      // The operand is the full user-string token (0x70 << 24 | RID);
      // the #US heap is addressed by the RID (byte offset into the heap).
      val rid = token & 0x00ffffff
      module.read(rid, (_, reader) => reader.readUserString(rid))
    }

    // Token operands resolve under the method's generic context (the
    // oracle resolves the MethodSpec arguments with the containing method
    // as context), so the canonical names are captured at decode time.
    val resolveToken: MetadataToken => Option[String] = token => {
      module.read(token, (t: MetadataToken, reader: io.spicelabs.cilantro.MetadataReader) => {
        reader._context = Some(method)
        reader.lookupToken(t).map {
          case m: io.spicelabs.cilantro.MethodReference => m.fullName
          case f: io.spicelabs.cilantro.FieldReference => f.fullName
          case ty: TypeReference => ty.fullName
          case m: io.spicelabs.cilantro.MemberReference => m.fullName
          case other => other.toString
        }
      })
    }

    val decoded = CodeReader.readBody(full, resolveString, resolveToken) match {
      case Success(body) => body
      case scala.util.Failure(t) => throw t
    }

    val clauses = sections.flatMap { section =>
      EhReader.parseSection(section, resolveCatchType) match {
        case Success(found) => found
        case scala.util.Failure(t) => throw t
      }
    }
    val handlers = resolveHandlers(clauses, decoded)

    val locals = if (localSigToken != 0) {
      val varsOpt = module.read(localSigToken, (token: Int, reader: io.spicelabs.cilantro.MetadataReader) => {
        reader._context = Some(method)
        reader.readLocalVariables(token)
      })
      varsOpt.map(_.toVector)
    } else {
      None
    }

    new MethodBody(decoded.instructions, maxStack, initLocals, decoded.codeSize, locals, handlers)
  }

  // Resolves raw clause offsets to instruction indices and enforces the
  // plan-03 sanity rules: offsets and ends within [0, codeSize], lengths
  // non-negative, and no partially-overlapping try ranges (nested and
  // identical ranges are legal — multi-catch shares one try range).
  // Endpoints that land on non-instruction boundaries resolve to None,
  // matching the oracle's null TryEnd/HandlerEnd semantics.
  private[cil] def resolveHandlers(
      clauses: Seq[EhReader.RawExceptionClause],
      body: MethodBody
  ): Vector[ExceptionHandler] = {
    val codeSize = body.codeSize
    val offsetToIndex: Map[Int, Int] = body.instructions.zipWithIndex.map { case (instr, idx) =>
      (instr.offset, idx)
    }.toMap

    def resolveStart(offset: Int, what: String): Option[Int] = {
      if (offset < 0 || offset >= codeSize) {
        fail(s"invalid EH clause: $what offset $offset outside [0, $codeSize)")
      }
      offsetToIndex.get(offset)
    }

    def resolveEnd(startOffset: Int, length: Int, what: String): Option[Int] = {
      if (length < 0) {
        fail(s"invalid EH clause: negative $what length $length")
      }
      val end = startOffset + length
      if (end < startOffset || end > codeSize) {
        fail(s"invalid EH clause: $what range [$startOffset, $end) outside [0, $codeSize)")
      }
      if (end == codeSize) {
        None
      } else {
        offsetToIndex.get(end)
      }
    }

    val handlers = clauses.toVector.map { clause =>
      val tryStart = resolveStart(clause.tryOffset, "try")
      val tryEnd = resolveEnd(clause.tryOffset, clause.tryLength, "try")
      val handlerStart = resolveStart(clause.handlerOffset, "handler")
      val handlerEnd = resolveEnd(clause.handlerOffset, clause.handlerLength, "handler")
      // Cecil tolerates a filter offset that does not resolve to an
      // instruction (null FilterStart): mono ilasm emits catch clauses
      // whose type token lands in this slot (see ADR-0005). Out-of-range
      // offsets resolve to None, not Failure.
      val filterStart = clause.filterOffset.flatMap { offset =>
        if (offset < 0 || offset >= codeSize) {
          None
        } else {
          offsetToIndex.get(offset)
        }
      }
      new ExceptionHandler(
        clause.handlerType,
        tryStart,
        tryEnd,
        handlerStart,
        handlerEnd,
        clause.catchType,
        filterStart
      )
    }

    checkTryOverlaps(handlers)
    handlers
  }

  // Partial overlap (A starts inside B's try but ends outside it) is
  // garbage per plan 03; nesting and identical ranges are legal.
  private def checkTryOverlaps(handlers: Vector[ExceptionHandler]): Unit = {
    for i <- handlers.indices do {
      for j <- (i + 1) until handlers.length do {
        (handlers(i).tryStart, handlers(i).tryEnd, handlers(j).tryStart, handlers(j).tryEnd) match {
          case (Some(aStart), aEnd, Some(bStart), bEnd) =>
            val aEndIdx = aEnd.getOrElse(Int.MaxValue)
            val bEndIdx = bEnd.getOrElse(Int.MaxValue)
            val aContainsB = aStart <= bStart && bEndIdx <= aEndIdx
            val bContainsA = bStart <= aStart && aEndIdx <= bEndIdx
            val intersect = aStart < bEndIdx && bStart < aEndIdx
            if (intersect && !aContainsB && !bContainsA) {
              fail(s"invalid EH clause: overlapping try ranges [$aStart, ${aEnd.getOrElse(-1)}) and [$bStart, ${bEnd.getOrElse(-1)})")
            }
          case _ => ()
        }
      }
    }
  }
}
