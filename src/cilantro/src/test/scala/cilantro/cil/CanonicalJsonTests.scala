// CanonicalJsonTests — C5-01: canonical per-class bytes.
//
// Why these tests exist:
//   Plan 13 makes the per-class canonical serializer the content
//   identity Goat Rodeo will hash for every class in a DLL. That
//   contract is only trustworthy if the per-class bytes are (a)
//   byte-identical to the golden-pinned tier1 dump for the same type,
//   (b) deterministic across runs, (c) safe for hostile names, and
//   (d) versioned. These tests pin all four.
//
// Theory of the test:
//   - Slice-exactness: the tier1 dump is byte-identical to the pinned
//     golden (C4-01), and CanonicalJson.writeType is the same code the
//     tier1 loop uses — so the per-type output must equal the exact
//     byte range of the dump covering that type. The range is found by
//     a JSON-aware scanner: the type object starts at
//     {"fullName":"<escaped>" and ends at the brace that closes it.
//     The scanner tracks string state because '{'/'}' inside string
//     values are legal and raw (quotes are \u0022-escaped, so string
//     tracking is exact).
//   - Hostile names: names are attacker-controlled bytes; the escape
//     set must neutralize ESC/bidi/lone surrogates and the result must
//     remain parseable JSON.
//
// Requirements traced:
//   13_dll_container_traversal.md C5-01 (a-d).
//
// LLM notes:
//   - The type-object shape is exactly the tier1 slice; the marker is
//     added only by typeToJson, not by writeType.
//   - The Slow test covers every type of the net20 assembly; the fast
//     test pins three representative types (plain, generic, nested).

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import java.io.{PrintWriter, StringWriter}
import io.spicelabs.cilantro.AssemblyDefinition
import io.spicelabs.cilantro.TypeDefinition
import io.spicelabs.cilantro.dump.CanonicalJson

class CanonicalJsonTests extends munit.FunSuite {

  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  private val Slow = new munit.Tag("Slow")

  private def loadNet20(): AssemblyDefinition = {
    AssemblyDefinition.readAssembly("../../corpus/bin/Newtonsoft.Json/12.0.3/net20/Newtonsoft.Json.dll") match {
      case Success(a) => a
      case Failure(t) => fail(s"failed to load net20: $t")
    }
  }

  private def tier1Dump(assembly: AssemblyDefinition): String = {
    val w = new StringWriter()
    ParityDumper.dumpTier1(new PrintWriter(w), assembly, "bin/Newtonsoft.Json/12.0.3/net20/Newtonsoft.Json.dll")
    w.toString
  }

  private def writeTypeString(t: TypeDefinition): String = {
    val w = new StringWriter()
    CanonicalJson.writeType(new PrintWriter(w), t)
    w.toString
  }

  // Finds the exact byte range of a type object inside a tier1 dump.
  // Returns (start, endExclusive).
  private def typeSlice(dump: String, t: TypeDefinition): (Int, Int) = {
    val needle = "{\"fullName\":" + CanonicalJson.q(t.fullName)
    val start = dump.indexOf(needle)
    assert(start >= 0, s"type ${t.fullName} not found in the dump")
    var depth = 0
    var inString = false
    var i = start
    while (i < dump.length) {
      val c = dump.charAt(i)
      if (inString) {
        if (c == '"') inString = false
      } else if (c == '"') {
        inString = true
      } else if (c == '{') {
        depth += 1
      } else if (c == '}') {
        depth -= 1
        if (depth == 0) {
          return (start, i + 1)
        }
      }
      i += 1
    }
    fail(s"unterminated type object for ${t.fullName}")
  }

  test("C5-01a: per-type bytes are the exact tier1 golden slice (three pinned types)") {
    val assembly = loadNet20()
    val dump = tier1Dump(assembly)
    val module = assembly.mainModule.get
    val names = List(
      "Newtonsoft.Json.JsonReader", // huge type with nested compiler-generated types
      "Newtonsoft.Json.Linq.JObject", // plain type
      "Newtonsoft.Json.JsonConvert" // generic methods, delegates
    )
    names.foreach { name =>
      val t = module.getType(name).getOrElse(fail(s"type $name missing"))
      val (start, end) = typeSlice(dump, t)
      assertEquals(writeTypeString(t), dump.substring(start, end), s"slice mismatch for $name")
    }
  }

  test("C5-01a (Slow): every type of the net20 assembly matches its tier1 slice".tag(Slow)) {
    val assembly = loadNet20()
    val dump = tier1Dump(assembly)
    val module = assembly.mainModule.get
    var checked = 0
    module.types.foreach { t =>
      val (start, end) = typeSlice(dump, t)
      assertEquals(writeTypeString(t), dump.substring(start, end), s"slice mismatch for ${t.fullName}")
      checked += 1
    }
    assert(checked > 100, s"expected a substantial type count, got $checked")
  }

  test("C5-01b: the per-type dump is deterministic across runs") {
    val assembly = loadNet20()
    val t = assembly.mainModule.get.getType("Newtonsoft.Json.Linq.JObject").get
    val first = CanonicalJson.typeToJson(t)
    val second = CanonicalJson.typeToJson(t)
    assertEquals(first, second)
    assertEquals(writeTypeString(t), writeTypeString(t))
  }

  test("C5-01c: hostile type names serialize safely and stay valid JSON") {
    val hostile = "Evil\u001b[2J\u202E\uD800Type"
    val t = new TypeDefinition("", hostile, io.spicelabs.cilantro.TypeAttributes.public.value)
    val json = CanonicalJson.typeToJson(t)
    json match {
      case Success(text) =>
        assert(!text.contains('\u001b'), "ESC must be escaped")
        assert(!text.contains('\u202E'), "bidi override must be escaped")
        assert(!text.contains('\uD800'), "lone surrogate must become U+FFFD")
        assert(org.json4s.native.JsonMethods.parse(text).isInstanceOf[org.json4s.JValue], "output must parse as JSON")
      case Failure(e) => fail(s"hostile name must serialize: $e")
    }
  }

  test("C5-01d: the frozen format marker and version are present; the signature is Try-shaped") {
    // Compile-time contract: the accessor shape GR will depend on.
    val writeTypeFn: TypeDefinition => scala.util.Try[String] = CanonicalJson.typeToJson
    val t = new TypeDefinition("", "ContractType", io.spicelabs.cilantro.TypeAttributes.public.value)
    writeTypeFn(t) match {
      case Success(text) =>
        assert(text.startsWith("{\"format\":\"cilantro-type\",\"version\":1,\"type\":"), "frozen marker/version prefix")
      case Failure(e) => fail(s"contract type must serialize: $e")
    }
  }
}
