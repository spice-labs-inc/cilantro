// ParityDumper — canonicalized JSON dump of the model, byte-compatible
// with the golden dumper's shapes (scripts/GoldenDumper/GoldenWriter.cs).
//
// Tier 1: assembly/module/types/members/signatures/custom attributes.
// Tier 2: per-method instruction dump with raw operand bytes (read from
// the original file via the section mapping) and resolved operands.
//
// Determinism: metadata order everywhere, fixed key order, hex attribute
// values, invariant culture. The golden comparison lives in the parity
// harness (C4-01).

package io.spicelabs.cilantro.cil

import java.io.PrintWriter
import io.spicelabs.cilantro.{
  AssemblyDefinition, FieldReference, MetadataToken, MethodDefinition,
  MethodReference, TypeDefinition, TypeReference
}
import io.spicelabs.cilantro.dump.CanonicalJson

object ParityDumper {

  private def q(value: String): String = CanonicalJson.q(value)

  def dumpTier1(writer: PrintWriter, assembly: AssemblyDefinition, label: String): Unit = {
    val module = assembly.mainModule.get
    writer.print("{\"format\":\"cilantro-tier1\",\"version\":1,\"assemblyLabel\":" + q(label) + ",")
    writer.print("\"assembly\":{\"name\":" + q(assembly.name.map(_.name).getOrElse("")) + ",")
    writer.print("\"version\":" + q(assembly.name.map(_.version.toString).getOrElse("")) + ",")
    writer.print("\"culture\":" + q(assembly.name.map(_.culture).getOrElse("")))
    val pkToken = assembly.name.map(_.publicKeyToken).getOrElse(Array.emptyByteArray)
    if (pkToken.nonEmpty) {
      writer.print(",\"publicKeyToken\":" + q(pkToken.map(b => f"$b%02x").mkString))
    }
    writer.print("},")
    writer.print("\"module\":{\"name\":" + q(module.name) + ",")
    writer.print("\"kind\":" + q(kindName(module.kind)) + ",")
    writer.print("\"architecture\":" + q(architectureName(module.targetArchitecture)) + ",")
    writer.print("\"runtimeVersion\":" + q(module.runtime_version) + ",")
    writer.print("\"mvid\":" + q(module.mvid.toString))
    writer.print("},")
    writer.print("\"assemblyReferences\":[")
    module.assemblyReferences.zipWithIndex.foreach { case (reference, i) =>
      if (i > 0) writer.print(",")
      writer.print("{\"name\":" + q(reference.name) + ",\"version\":" + q(reference.version.toString))
      val token = reference.publicKeyToken
      if (token.nonEmpty) {
        writer.print(",\"publicKeyToken\":" + q(token.map(b => f"$b%02x").mkString))
      }
      writer.print("}")
    }
    writer.print("],\"moduleReferences\":[")
    module.moduleReferences.zipWithIndex.foreach { case (reference, i) =>
      if (i > 0) writer.print(",")
      writer.print(q(reference.name))
    }
    writer.print("],\"types\":[")
    module.types.zipWithIndex.foreach { case (typeDef, i) =>
      if (i > 0) writer.print(",")
      CanonicalJson.writeType(writer, typeDef)
    }
    writer.print("]}")
  }

  def dumpTier2(writer: PrintWriter, assembly: AssemblyDefinition, label: String): Unit = {
    val module = assembly.mainModule.get
    val image = module.image.get
    writer.print("{\"format\":\"cilantro-tier2\",\"version\":1,\"assemblyLabel\":" + q(label) + ",\"bodies\":[")
    var first = true

    def bodyBytes(rva: Int): Array[Byte] = {
      image.getReaderAt(rva) match {
        case None => Array.emptyByteArray
        case Some(reader) =>
          val firstByte = reader.readByte().toInt & 0xff
          val bodyStart = reader.position - 1
          reader.moveTo(bodyStart)
          val headerSize = if ((firstByte & 0x3) == 0x3) 12 else 1
          val codeSize = if ((firstByte & 0x3) == 0x3) {
            reader.moveTo(bodyStart + 4)
            val size = reader.readInt32()
            reader.moveTo(bodyStart)
            size
          } else {
            firstByte >> 2
          }
          reader.readBytes(headerSize + codeSize)
      }
    }

    def writeBody(method: MethodDefinition): Unit = {
      method.readBody() match {
        case scala.util.Success(Some(body)) =>
          if (!first) writer.print(",") else first = false
          writer.print("{\"method\":" + q(method.fullName) + ",")
          writer.print("\"rva\":" + method.RVA + ",")
          writer.print("\"initLocals\":" + body.initLocals + ",")
          writer.print("\"maxStack\":" + body.maxStackSize + ",")
          writer.print("\"codeSize\":" + body.codeSize + ",")
          writer.print("\"locals\":[")
          body.locals.getOrElse(Vector.empty).zipWithIndex.foreach { case (local, i) =>
            if (i > 0) writer.print(",")
            writer.print("{\"type\":" + q(local.variableType.fullName) + ",\"pinned\":" + local.isPinned + "}")
          }
          writer.print("],\"instructions\":[")
          val raw = bodyBytes(method.RVA)
          val headerSize = if ((raw.length > 0 && (raw(0) & 0x3) == 0x3)) 12 else 1
          body.instructions.zipWithIndex.foreach { case (instr, idx) =>
            if (idx > 0) writer.print(",")
            val nextOffset = if (idx + 1 < body.instructions.length) body.instructions(idx + 1).offset else body.codeSize
            val length = nextOffset - instr.offset
            val start = headerSize + instr.offset
            val rawBytes = if (start + length <= raw.length) raw.slice(start, start + length) else Array.emptyByteArray
            writer.print("{\"offset\":" + instr.offset + ",")
            writer.print("\"opcode\":" + q(instr.opcode.name) + ",")
            writer.print("\"operandType\":" + q(instr.opcode.operandType.toString) + ",")
            writer.print("\"rawBytes\":" + q(rawBytes.map(b => f"${b & 0xff}%02x").mkString) + ",")
            writer.print("\"operand\":")
            writeOperand(writer, instr.operand, body, module, method.hasThis)
            writer.print("}")
          }
          writer.print("],\"exceptionHandlers\":[")
          body.exceptionHandlers.zipWithIndex.foreach { case (handler, idx) =>
            if (idx > 0) writer.print(",")
            writer.print("{\"type\":" + q(handler.handlerType.toString) + ",")
            writer.print("\"tryStart\":" + handler.tryStart.map(body.instructions(_).offset).getOrElse(-1) + ",")
            writer.print("\"tryEnd\":" + handler.tryEnd.map(body.instructions(_).offset).getOrElse(-1) + ",")
            writer.print("\"handlerStart\":" + handler.handlerStart.map(body.instructions(_).offset).getOrElse(-1) + ",")
            writer.print("\"handlerEnd\":" + handler.handlerEnd.map(body.instructions(_).offset).getOrElse(-1))
            handler.catchType.foreach(t => writer.print(",\"catchType\":" + q(t.fullName)))
            handler.filterStart.foreach(idx => writer.print(",\"filterStart\":" + body.instructions(idx).offset))
            writer.print("}")
          }
          writer.print("]}")
        case _ => ()
      }
    }

    def walk(t: TypeDefinition): Unit = {
      t.methods.foreach(writeBody)
      t.nestedTypes.foreach(walk)
    }
    module.types.foreach(walk)
    writer.print("]}")
  }

  private def writeOperand(
      writer: PrintWriter,
      operand: Option[Operand],
      body: MethodBody,
      module: io.spicelabs.cilantro.ModuleDefinition,
      hasThis: Boolean
  ): Unit = operand match {
    case None => writer.print("\"null\"")
    case Some(Operand.Tok(token, resolved)) =>
      val name = resolved.orElse(resolveTokenName(module, token)).getOrElse("")
      writer.print(q(name))
    case Some(Operand.Branch(targetIndex)) => writer.print(body.instructions(targetIndex).offset.toString)
    case Some(Operand.Switch(targets)) =>
      writer.print("[" + targets.map(body.instructions(_).offset.toString).mkString(",") + "]")
    case Some(Operand.StringLit(value)) => writer.print(q(value))
    case Some(Operand.Sig(blobIndex)) =>
      writer.print(q(readCallSiteName(module, blobIndex)))
    case Some(Operand.I4(value)) => writer.print(value.toString)
    case Some(Operand.I8(value)) => writer.print(value.toString)
    case Some(Operand.R4(value)) => writer.print(q(csRoundTripFloat(value)))
    case Some(Operand.R8(value)) => writer.print(q(csRoundTrip(value)))
    case Some(Operand.Var(index)) => writer.print(q("local." + index))
    case Some(Operand.Arg(index)) =>
      // Cecil's ParameterDefinition.Index excludes the implicit `this`
      // argument for instance methods.
      writer.print(q("param." + (if (hasThis) index - 1 else index)))
  }

  private def resolveTokenName(
      module: io.spicelabs.cilantro.ModuleDefinition,
      token: MetadataToken
  ): Option[String] = {
    val provider = module.read(token, (t: MetadataToken, reader: io.spicelabs.cilantro.MetadataReader) =>
      reader.lookupToken(t))
    provider.map {
      case m: MethodReference => m.fullName
      case f: FieldReference => f.fullName
      case t: TypeReference => t.fullName
      case m: io.spicelabs.cilantro.MemberReference => m.fullName
      case other => other.toString
    }
  }

  private def readCallSiteName(module: io.spicelabs.cilantro.ModuleDefinition, blobIndex: Int): String = {
    module.read(blobIndex, (index: Int, reader: io.spicelabs.cilantro.MetadataReader) => {
      val method = MethodReference("", io.spicelabs.cilantro.TypeReference("", ""))
      reader.readMethodSignature(index, method)
      method.returnType.fullName + " method" + method.methodSignatureFullName(StringBuilder()).toString
    })
  }

  private def kindName(kind: io.spicelabs.cilantro.ModuleKind): String = kind match {
    case io.spicelabs.cilantro.ModuleKind.dll => "Dll"
    case io.spicelabs.cilantro.ModuleKind.console => "Console"
    case io.spicelabs.cilantro.ModuleKind.windows => "Windows"
    case io.spicelabs.cilantro.ModuleKind.netModule => "NetModule"
  }

  private def architectureName(arch: io.spicelabs.cilantro.TargetArchitecture): String = arch match {
    case io.spicelabs.cilantro.TargetArchitecture.i386 => "I386"
    case io.spicelabs.cilantro.TargetArchitecture.amd64 => "AMD64"
    case io.spicelabs.cilantro.TargetArchitecture.ia64 => "IA64"
    case io.spicelabs.cilantro.TargetArchitecture.arm => "ARM"
    case io.spicelabs.cilantro.TargetArchitecture.armv7 => "ARMv7"
    case io.spicelabs.cilantro.TargetArchitecture.arm64 => "ARM64"
  }

  // Mirrors System.Text.Json's JavaScriptEncoder.Default: the four HTML-
  // sensitive characters escape as \u00XX (uppercase hex).
  // Emulates the C# "R" round-trip format for the golden comparison:
  // 48.0 -> "48", 1.0E20 -> "1E+20", 1.5E-5 -> "1.5E-05".
  private def csRoundTripFloat(value: Float): String = CanonicalJson.csRoundTripFloat(value)

  private def csRoundTrip(value: Double): String = CanonicalJson.csRoundTrip(value)

}
