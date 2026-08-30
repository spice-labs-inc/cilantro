// CanonicalJson — frozen canonical serialization for a single type.
//
// This is the public, contract-frozen per-class serializer (plan 13,
// C5-01): Goat Rodeo will hash these bytes as the content identity of a
// class inside a DLL. The emission code is the same code the tier1
// parity dump uses (ParityDumper delegates here), so the bytes are
// golden-pinned: the C4-01 parity suite fails if anything here drifts.
//
// Format: {"format":"cilantro-type","version":1,"type":{...}}
// The type object itself is byte-identical to the type's range inside
// a cilantro-tier1 dump.
//
// Determinism: metadata order everywhere, fixed key order, hex
// attribute values, System.Text.Json-compatible escaping (the golden
// escape set), C# "R" round-trip float formatting.

package io.spicelabs.cilantro.dump

import java.io.PrintWriter
import java.io.StringWriter
import scala.util.Try
import io.spicelabs.cilantro.{
  CustomAttribute, MethodDefinition, MethodSemanticsAttributes, TypeDefinition, TypeReference
}

object CanonicalJson {

  def typeToJson(typeDef: TypeDefinition): Try[String] = Try {
    val writer = new StringWriter()
    val pw = new PrintWriter(writer)
    pw.print("{\"format\":\"cilantro-type\",\"version\":1,\"type\":")
    writeType(pw, typeDef)
    pw.print("}")
    writer.toString
  }

  def writeType(writer: PrintWriter, typeDef: TypeDefinition): Unit = {
    writer.print("{\"fullName\":" + q(typeDef.fullName) + ",")
    writer.print("\"attributes\":" + q(f"${typeDef.attributes}%x") + ",")
    writer.print("\"baseType\":")
    writeOptionalCanonical(writer, typeDef.baseType)
    writer.print(",\"interfaces\":[")
    typeDef.interfaces.zipWithIndex.foreach { case (iface, i) =>
      if (i > 0) writer.print(",")
      writer.print(q(iface.interfaceType.fullName))
    }
    writer.print("],\"genericParameters\":[")
    typeDef.genericParameters.zipWithIndex.foreach { case (gp, i) =>
      if (i > 0) writer.print(",")
      writer.print("{\"name\":" + q(gp.name) + ",")
      writer.print("\"attributes\":" + q(f"${gp.attributes & 0xffff}%x") + ",")
      writer.print("\"constraints\":[")
      gp.constraints.zipWithIndex.foreach { case (constraint, j) =>
        if (j > 0) writer.print(",")
        writer.print(q(constraint.constraintType.fullName))
      }
      writer.print("]}")
    }
    writer.print("],\"customAttributes\":[")
    typeDef.customAttributes.zipWithIndex.foreach { case (attr, i) =>
      if (i > 0) writer.print(",")
      writeCustomAttribute(writer, attr)
    }
    writer.print("],\"fields\":[")
    typeDef.fields.zipWithIndex.foreach { case (field, i) =>
      if (i > 0) writer.print(",")
      writer.print("{\"name\":" + q(field.name) + ",")
      writer.print("\"type\":" + q(field.fieldType.fullName) + ",")
      writer.print("\"attributes\":" + q(f"${field.attributes & 0xffff}%x"))
      if (field.hasConstant) {
        writer.print(",\"constant\":")
        writeConstant(writer, field.constant)
      }
      writer.print("}")
    }
    writer.print("],\"methods\":[")
    typeDef.methods.zipWithIndex.foreach { case (method, i) =>
      if (i > 0) writer.print(",")
      writeMethod(writer, method)
    }
    writer.print("],\"properties\":[")
    typeDef.properties.zipWithIndex.foreach { case (property, i) =>
      if (i > 0) writer.print(",")
      writer.print("{\"name\":" + q(property.name) + ",")
      writer.print("\"type\":" + q(property.propertyType.fullName) + ",")
      writer.print("\"attributes\":" + q(f"${property.attributes & 0xffff}%x"))
      property.getMethod.foreach(m => writer.print(",\"get\":" + q(m.fullName)))
      property.setMethod.foreach(m => writer.print(",\"set\":" + q(m.fullName)))
      writer.print("}")
    }
    writer.print("],\"events\":[")
    typeDef.events.zipWithIndex.foreach { case (event, i) =>
      if (i > 0) writer.print(",")
      writer.print("{\"name\":" + q(event.name) + ",")
      writer.print("\"type\":" + q(event.eventType.fullName) + ",")
      writer.print("\"attributes\":" + q(f"${event.attributes & 0xffff}%x"))
      event.addMethod.foreach(m => writer.print(",\"add\":" + q(m.fullName)))
      event.removeMethod.foreach(m => writer.print(",\"remove\":" + q(m.fullName)))
      event.invokeMethod.foreach(m => writer.print(",\"invoke\":" + q(m.fullName)))
      writer.print("}")
    }
    writer.print("],\"nestedTypes\":[")
    typeDef.nestedTypes.zipWithIndex.foreach { case (nested, i) =>
      if (i > 0) writer.print(",")
      writeType(writer, nested)
    }
    writer.print("]}")
  }

  private def writeMethod(writer: PrintWriter, method: MethodDefinition): Unit = {
    writer.print("{\"name\":" + q(method.name) + ",")
    writer.print("\"returnType\":" + q(method.returnType.fullName) + ",")
    writer.print("\"callingConvention\":" + q(f"${method.callingConvention.value}%x") + ",")
    writer.print("\"hasThis\":" + method.hasThis + ",")
    writer.print("\"explicitThis\":" + method.explicitThis + ",")
    writer.print("\"attributes\":" + q(f"${method.attributes & 0xffff}%x") + ",")
    writer.print("\"implAttributes\":" + q(f"${method.implAttributes & 0xffff}%x"))
    val semantics = method.semanticAttributes
    if (semantics != 0) {
      writer.print(",\"semantics\":" + q(semanticsName(semantics)))
    }
    writer.print(",\"parameters\":[")
    method.parameters.zipWithIndex.foreach { case (parameter, i) =>
      if (i > 0) writer.print(",")
      writer.print("{\"name\":" + q(parameter.name) + ",")
      writer.print("\"type\":" + q(parameter.parameterType.fullName) + ",")
      writer.print("\"attributes\":" + q(f"${parameter.attributes & 0xffff}%x"))
      if (parameter.hasConstant) {
        writer.print(",\"constant\":")
        writeConstant(writer, parameter.constant)
      }
      writer.print("}")
    }
    writer.print("],\"genericParameters\":[")
    method.genericParameters.zipWithIndex.foreach { case (gp, i) =>
      if (i > 0) writer.print(",")
      writer.print("{\"name\":" + q(gp.name) + ",")
      writer.print("\"attributes\":" + q(f"${gp.attributes & 0xffff}%x") + "}")
    }
    writer.print("],\"customAttributes\":[")
    method.customAttributes.zipWithIndex.foreach { case (attr, i) =>
      if (i > 0) writer.print(",")
      writeCustomAttribute(writer, attr)
    }
    writer.print("]}")
  }

  private def semanticsName(attrs: Char): String = {
    val value = attrs.toInt
    val parts = List(
      (MethodSemanticsAttributes.setter.value, "Setter"),
      (MethodSemanticsAttributes.getter.value, "Getter"),
      (MethodSemanticsAttributes.other.value, "Other"),
      (MethodSemanticsAttributes.addOn.value, "AddOn"),
      (MethodSemanticsAttributes.removeOn.value, "RemoveOn"),
      (MethodSemanticsAttributes.fire.value, "Fire")
    ).collect { case (bit, name) if (value & bit) != 0 => name }
    parts.mkString(", ")
  }

  private def writeCustomAttribute(writer: PrintWriter, attr: CustomAttribute): Unit = {
    writer.print("{\"type\":" + q(attr.attributeType.map(_.fullName).getOrElse("")) + ",")
    writer.print("\"constructorArgs\":[")
    attr.constructorArguments.zipWithIndex.foreach { case (arg, i) =>
      if (i > 0) writer.print(",")
      writeCustomAttributeArgument(writer, arg)
    }
    writer.print("],\"namedFields\":[")
    attr.fields.zipWithIndex.foreach { case (named, i) =>
      if (i > 0) writer.print(",")
      writeNamed(writer, named)
    }
    writer.print("],\"namedProperties\":[")
    attr.properties.zipWithIndex.foreach { case (named, i) =>
      if (i > 0) writer.print(",")
      writeNamed(writer, named)
    }
    writer.print("]}")
  }

  private def writeNamed(writer: PrintWriter, named: io.spicelabs.cilantro.CustomAttributeNamedArgument): Unit = {
    writer.print("{\"name\":" + q(named.name) + ",\"argument\":")
    writeCustomAttributeArgument(writer, named.argument)
    writer.print("}")
  }

  private def writeCustomAttributeArgument(writer: PrintWriter, arg: io.spicelabs.cilantro.CustomAttributeArgument): Unit = {
    writer.print("{\"type\":" + q(arg.`type`.fullName) + ",\"value\":")
    writeCustomAttributeValue(writer, arg.value)
    writer.print("}")
  }

  private def writeCustomAttributeValue(writer: PrintWriter, value: Any): Unit = value match {
    case io.spicelabs.cilantro.CilNullConstant => writer.print("\"null\"")
    case s: String => writer.print(q(s))
    case t: TypeReference => writer.print("\"typeof(" + jsonEscape(t.fullName) + ")\"")
    case Some(t: TypeReference) => writer.print("\"typeof(" + jsonEscape(t.fullName) + ")\"")
    case bytes: Array[Byte] => writer.print(q(bytes.map(b => f"$b%02x").mkString))
    case nested: io.spicelabs.cilantro.CustomAttributeArgument =>
      writer.print("{\"type\":" + q(nested.`type`.fullName) + ",\"value\":")
      writeCustomAttributeValue(writer, nested.value)
      writer.print("}")
    case array: Array[io.spicelabs.cilantro.CustomAttributeArgument] =>
      writer.print("[")
      array.zipWithIndex.foreach { case (item, i) =>
        if (i > 0) writer.print(",")
        writeCustomAttributeArgument(writer, item)
      }
      writer.print("]")
    case b: Byte => writer.print(q(b.toString))
    case s: Short => writer.print(q(s.toString))
    case i: Int => writer.print(q(i.toString))
    case l: Long => writer.print(q(l.toString))
    case b: java.math.BigInteger => writer.print(q(b.toString))
    case b2: Boolean => writer.print(q(if (b2) "True" else "False"))
    case c: Char => writer.print(q(c.toString))
    case f: Float => writer.print(q(csRoundTripFloat(f)))
    case d: Double => writer.print(q(csRoundTrip(d)))
    case other => writer.print(q(other.toString))
  }

  private def writeConstant(writer: PrintWriter, constant: Any): Unit = {
    if (constant == io.spicelabs.cilantro.CilNullConstant) {
      writer.print("\"null\"")
    } else {
      writer.print("{\"value\":")
      writeCustomAttributeValue(writer, constant)
      writer.print("}")
    }
  }

  private def writeOptionalCanonical(writer: PrintWriter, typeRef: Option[TypeReference]): Unit = {
    typeRef match {
      case Some(t) => writer.print(q(t.fullName))
      case None => writer.print("null")
    }
  }

  def q(value: String): String = "\"" + jsonEscape(value) + "\""

  def csRoundTripFloat(value: Float): String = {
    if (java.lang.Float.isNaN(value)) {
      "NaN"
    } else if (value == Float.PositiveInfinity) {
      "Infinity"
    } else if (value == Float.NegativeInfinity) {
      "-Infinity"
    } else if (value == Math.rint(value) && Math.abs(value) < 1e15f) {
      if (java.lang.Float.floatToRawIntBits(value) == 0x80000000) {
        "-0"
      } else {
        value.toLong.toString
      }
    } else {
      val exact = BigDecimal(java.lang.Float.toString(value))
      var candidate = exact
      var keepGoing = true
      while (keepGoing && candidate.precision > 1) {
        val reduced = candidate.round(new java.math.MathContext(candidate.precision - 1, java.math.RoundingMode.HALF_EVEN))
        if (reduced.floatValue == value) {
          candidate = reduced
        } else {
          keepGoing = false
        }
      }
      val exponent = candidate.precision - candidate.scale - 1
      if (exponent >= -4 && exponent < 15) {
        candidate.bigDecimal.toPlainString
      } else {
        val digits = candidate.bigDecimal.unscaledValue.abs.toString
        val mantissa = if (digits.length == 1) digits else digits.substring(0, 1) + "." + digits.substring(1)
        val sign = if (candidate.signum < 0) "-" else ""
        sign + mantissa + "E" + (if (exponent >= 0) "+" else "-") + f"${Math.abs(exponent)}%02d"
      }
    }
  }

  def csRoundTrip(value: Double): String = {
    if (java.lang.Double.isNaN(value)) {
      "NaN"
    } else if (value == Double.PositiveInfinity) {
      "Infinity"
    } else if (value == Double.NegativeInfinity) {
      "-Infinity"
    } else if (value == Math.rint(value) && Math.abs(value) < 1e15) {
      if (java.lang.Double.doubleToRawLongBits(value) == 0x8000000000000000L) {
        "-0"
      } else {
        value.toLong.toString
      }
    } else {
      val exact = BigDecimal(java.lang.Double.toString(value))
      var candidate = exact
      var keepGoing = true
      while (keepGoing && candidate.precision > 1) {
        val reduced = candidate.round(new java.math.MathContext(candidate.precision - 1, java.math.RoundingMode.HALF_EVEN))
        if (reduced.doubleValue == value) {
          candidate = reduced
        } else {
          keepGoing = false
        }
      }
      val exponent = candidate.precision - candidate.scale - 1
      if (exponent >= -4 && exponent < 15) {
        candidate.bigDecimal.toPlainString
      } else {
        val digits = candidate.bigDecimal.unscaledValue.abs.toString
        val mantissa = if (digits.length == 1) digits else digits.substring(0, 1) + "." + digits.substring(1)
        val sign = if (candidate.signum < 0) "-" else ""
        sign + mantissa + "E" + (if (exponent >= 0) "+" else "-") + f"${Math.abs(exponent)}%02d"
      }
    }
  }

  private def jsonEscape(value: String): String = {
    val sb = new StringBuilder
    var i = 0
    while (i < value.length) {
      val c = value.charAt(i)
      if (Character.isHighSurrogate(c)) {
        if (i + 1 < value.length && Character.isLowSurrogate(value.charAt(i + 1))) {
          sb.append(f"\\u${c.toInt}%04X")
          i += 1
          sb.append(f"\\u${value.charAt(i).toInt}%04X")
        } else {
          sb.append("\\uFFFD")
        }
      } else if (Character.isLowSurrogate(c)) {
        sb.append("\\uFFFD")
      } else {
        c match {
          case '"' => sb.append("\\u0022")
          case '\\' => sb.append("\\\\")
          case '\b' => sb.append("\\b")
          case '\f' => sb.append("\\f")
          case '\n' => sb.append("\\n")
          case '\r' => sb.append("\\r")
          case '\t' => sb.append("\\t")
          case '<' => sb.append("\\u003C")
          case '>' => sb.append("\\u003E")
          case '&' => sb.append("\\u0026")
          case '\'' => sb.append("\\u0027")
          case '`' => sb.append("\\u0060")
          case '+' => sb.append("\\u002B")
          case c2 if c2 < 0x20 || c2 > 0x7e => sb.append(f"\\u${c2.toInt}%04X")
          case c2 => sb.append(c2)
        }
      }
      i += 1
    }
    sb.toString
  }
}
