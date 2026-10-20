// CustomAttributeTotalityTests — reading custom attributes never throws.
//
// Why these tests exist:
//   goatrodeo#209: consumers had to wrap `customAttributes` and
//   `constructorArguments` in Try. Reading attributes must be total:
//   an attribute whose constructor can't be read is skipped (the others
//   on the owner survive), and a value blob that fails to decode leaves
//   empty arguments, never a partial list.
//
// Theory of the test:
//   Synthetic assemblies (MinimalPeBuilder) with module-level
//   customAttribute rows whose constructors are memberRefs on a single
//   typeRef `System.Type` (scope = module). The memberRef signature
//   blobs and attribute value blobs are hand-built per test.
//
// LLM notes:
//   - Strings heap = [0, 'A', 0] + extraStrings, so "System" is at 3
//     and "Type" at 10.
//   - typeRef row (6 bytes): ResolutionScope (module = 4), Name,
//     Namespace. memberRef row (6 bytes): MemberRefParent (typeRef 1 =
//     9), Name, Signature. customAttribute row (6 bytes): Parent
//     (module = 39), Type (memberRef rid r = (r << 3) | 3), Value.
//   - Blob heap index base is 2.
//   - Signature bytes: 0x20 HASTHIS, param count, 0x01 void return,
//     then params: 0x0e string, 0x1c object, 0x12 class + TypeDefOrRef
//     coded (typeRef 1 = 5). 0x7f is not a valid element type.

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import io.spicelabs.cilantro.{ModuleDefinition, TypeReference, ArrayType, TypeParser}
import java.io.FileOutputStream

class CustomAttributeTotalityTests extends munit.FunSuite {

  private def i2(v: Int): Array[Byte] = Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte)

  private def prefixed(blob: Array[Byte]): Array[Byte] = Array(blob.length.toByte) ++ blob

  private def serString(s: String): Array[Byte] = prefixed(s.getBytes("UTF-8"))

  private val prolog = i2(1)
  private val noNamedArgs = i2(0)

  // One memberRef and one customAttribute row per (signature, value)
  // pair, all attached to the module.
  private def attributesFile(attributes: Seq[(Array[Byte], Array[Byte])]): java.io.File = {
    val blobs = attributes.flatMap { case (sig, value) => Seq(prefixed(sig), prefixed(value)) }
    val offsets = blobs.scanLeft(2)(_ + _.length)
    val typeRefRow = i2(4) ++ i2(10) ++ i2(3)
    val memberRefRows = attributes.indices.flatMap(i => i2(9) ++ i2(0) ++ i2(offsets(2 * i))).toArray
    val caRows = attributes.indices.flatMap(i => i2(39) ++ i2(((i + 1) << 3) | 3) ++ i2(offsets(2 * i + 1))).toArray
    val file = java.io.File.createTempFile("cattr", ".dll")
    val out = new FileOutputStream(file)
    out.write(new MinimalPeBuilder(
      extraTables = Seq((1, 6, typeRefRow), (10, 6, memberRefRows), (12, 6, caRows)),
      extraBlobs = blobs,
      extraStrings = "System\u0000Type\u0000".getBytes("ASCII")
    ).build())
    out.close()
    file
  }

  private def withModule(attributes: Seq[(Array[Byte], Array[Byte])])(check: ModuleDefinition => Unit): Unit = {
    val file = attributesFile(attributes)
    try {
      ModuleDefinition.readModule(file.getAbsolutePath) match {
        case Success(module) => check(module)
        case Failure(e) => fail(s"the synthetic assembly must read: $e")
      }
    } finally {
      file.delete()
    }
  }

  private val stringCtor = Array[Byte](0x20, 0x01, 0x01, 0x0e)

  test("an attribute whose constructor can't be read is skipped, not thrown") {
    val badCtor = Array[Byte](0x20, 0x01, 0x01, 0x7f)
    withModule(Seq(
      (badCtor, prolog ++ serString("ignored") ++ noNamedArgs),
      (stringCtor, prolog ++ serString("hello") ++ noNamedArgs)
    )) { module =>
      val attrs = module.customAttributes
      assertEquals(attrs.length, 1, "only the readable attribute survives")
      assertEquals(attrs(0).attributeType.map(_.fullName), Some("System.Type"))
      assertEquals(attrs(0).constructorArguments.map(_.value).toList, List("hello"))
    }
  }

  test("a value blob that fails part-way leaves no constructor arguments") {
    // string, then a boxed object whose type tag is invalid
    val ctor = Array[Byte](0x20, 0x02, 0x01, 0x0e, 0x1c)
    withModule(Seq((ctor, prolog ++ serString("hello") ++ Array(0x7f.toByte) ++ noNamedArgs))) { module =>
      val attrs = module.customAttributes
      assertEquals(attrs.length, 1)
      assert(attrs(0).constructorArguments.isEmpty, "a failed decode must not expose a partial argument list")
      assert(!attrs(0).hasConstructorArguments)
    }
  }

  test("a System.Type argument's value is a TypeReference") {
    val typeCtor = Array[Byte](0x20, 0x01, 0x01, 0x12, 0x05)
    withModule(Seq((typeCtor, prolog ++ serString("System.Type") ++ noNamedArgs))) { module =>
      module.customAttributes(0).constructorArguments.map(_.value).toList match {
        case List(t: TypeReference) => assertEquals(t.fullName, "System.Type")
        case other => fail(s"expected one TypeReference value, got $other")
      }
    }
  }

  test("parsing an array of a type defined in the module wraps it once") {
    val path = java.nio.file.Paths.get("../../test-files/Newtonsoft.Json/net20/Newtonsoft.Json.dll").toString
    ModuleDefinition.readModule(path) match {
      case Success(module) =>
        TypeParser.parseType(Some(module), "Newtonsoft.Json.JsonConverter[]") match {
          case Some(array: ArrayType) =>
            assertEquals(array.fullName, "Newtonsoft.Json.JsonConverter[]")
            assert(!array.elementType.isInstanceOf[ArrayType], "the element type must not itself be an array")
          case other => fail(s"expected an ArrayType, got $other")
        }
      case Failure(e) => fail(s"the net20 assembly must read: $e")
    }
  }
}
