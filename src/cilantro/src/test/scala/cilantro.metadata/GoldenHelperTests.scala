// GoldenHelperTests — C1-03.
//
// Why this test exists:
//   The Mono.Cecil-based golden dumper is an implementation, not the spec.
//   The corpus plan (doc 01) therefore requires the helper to be verified
//   against a hand-authored ilasm fixture whose expected bytes are
//   hand-decoded HERE, in the test. If the oracle drifts (bug in raw-byte
//   mapping, opcode naming, signature canonicalization), this test catches
//   it without depending on the oracle itself.
//
// Hand-decoded expectations (see scripts/fixtures/ilasm_fixture.il):
//   Fixture.Add:      ldarg.1(0x03) ldarg.2(0x04) add(0x58) ret(0x2A)
//   Fixture.Varargs:  ldarg.0(0x02) ldc.i4.7(0x1D) add(0x58) ret(0x2A)
//   Fixture.ArgListCall: arglist(0xFE 0x00) pop(0x26) ret(0x2A)
//   Fixture.ModReqOpt params carry modreq(IsLong)/modopt(IsConst)
//   Fixture.Varargs calling convention 0x5 (VarArg)
//   Attributed type carries ObsoleteAttribute("Hello")
//
// Theory of the test:
//   The tier2 golden for the fixture is parsed and each hand-decoded
//   instruction sequence is asserted exactly (offset, opcode name, raw
//   bytes). The tier1 golden pins the vararg convention, the modreq/modopt
//   parameter shapes and the custom attribute value. Every instruction in
//   the fixture must also carry non-empty raw bytes (the trust guard:
//   "raw bytes are dumped alongside interpretation").

package io.spicelabs.cilantro.metadata

import org.json4s._
import org.json4s.native.JsonMethods

class GoldenHelperTests extends munit.FunSuite {
  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  implicit val formats: DefaultFormats.type = DefaultFormats

  private def fixtureJson(name: String): JValue = {
    val root = CorpusProvisioner.ensureCorpus()
    val path = root.resolve(s"golden/fixtures/$name")
    JsonMethods.parse(CorpusHelpers.readGzipJson(path))
  }

  private def bodyFor(tier2: JValue, methodName: String): JValue = {
    val bodies = (tier2 \ "bodies").extract[List[JValue]]
    bodies
      .find(b => (b \ "method").extract[String].contains(methodName))
      .getOrElse(fail(s"fixture body not found: $methodName"))
  }

  private def instructionList(body: JValue): List[(Int, String, String)] = {
    (body \ "instructions").extract[List[JValue]].map { instr =>
      (
        (instr \ "offset").extract[Int],
        (instr \ "opcode").extract[String],
        (instr \ "rawBytes").extract[String]
      )
    }
  }

  test("C1-03: Fixture.Add decodes to hand-decoded bytes") {
    val tier2 = fixtureJson("ilasm_fixture.tier2.json")
    val body = bodyFor(tier2, "Fixture::Add")
    assertEquals(
      instructionList(body),
      List(
        (0, "ldarg.1", "03"),
        (1, "ldarg.2", "04"),
        (2, "add", "58"),
        (3, "ret", "2a")
      )
    )
  }

  test("C1-03: Fixture.ArgListCall decodes to hand-decoded bytes") {
    val tier2 = fixtureJson("ilasm_fixture.tier2.json")
    val body = bodyFor(tier2, "Fixture::ArgListCall")
    assertEquals(
      instructionList(body),
      List(
        (0, "arglist", "fe00"),
        (2, "pop", "26"),
        (3, "ret", "2a")
      )
    )
  }

  test("C1-03: Fixture.Varargs decodes to hand-decoded bytes") {
    val tier2 = fixtureJson("ilasm_fixture.tier2.json")
    val body = bodyFor(tier2, "Fixture::Varargs")
    assertEquals(
      instructionList(body),
      List(
        (0, "ldarg.0", "02"),
        (1, "ldc.i4.7", "1d"),
        (2, "add", "58"),
        (3, "ret", "2a")
      )
    )
  }

  test("C1-03: vararg convention and modreq/modopt parameters are pinned") {
    val tier1 = fixtureJson("ilasm_fixture.tier1.json")
    val fixtureType = (tier1 \ "types").extract[List[JValue]]
      .find(t => (t \ "fullName").extract[String] == "Fixture")
      .getOrElse(fail("Fixture type missing from tier1"))

    val varargs = (fixtureType \ "methods").extract[List[JValue]]
      .find(m => (m \ "name").extract[String] == "Varargs")
      .getOrElse(fail("Varargs method missing"))
    assertEquals((varargs \ "callingConvention").extract[String], "5")

    val modReqOpt = (fixtureType \ "methods").extract[List[JValue]]
      .find(m => (m \ "name").extract[String] == "ModReqOpt")
      .getOrElse(fail("ModReqOpt method missing"))
    val params = (modReqOpt \ "parameters").extract[List[JValue]]
      .map(p => (p \ "type").extract[String])
    assertEquals(params.length, 2)
    assert(params(0).contains("modreq(System.Runtime.CompilerServices.IsLong)"))
    assert(params(1).contains("modopt(System.Runtime.CompilerServices.IsConst)"))
  }

  test("C1-03: custom attribute value is decoded") {
    val tier1 = fixtureJson("ilasm_fixture.tier1.json")
    val attributed = (tier1 \ "types").extract[List[JValue]]
      .find(t => (t \ "fullName").extract[String] == "Attributed")
      .getOrElse(fail("Attributed type missing from tier1"))
    val attrs = (attributed \ "customAttributes").extract[List[JValue]]
    assertEquals(attrs.length, 1)
    assertEquals((attrs.head \ "type").extract[String], "System.ObsoleteAttribute")
    val args = (attrs.head \ "constructorArgs").extract[List[JValue]]
    assertEquals(args.length, 1)
    assertEquals((args.head \ "value").extract[String], "Hello")
  }

  test("C1-03: raw bytes are present for every fixture instruction") {
    val tier2 = fixtureJson("ilasm_fixture.tier2.json")
    val bodies = (tier2 \ "bodies").extract[List[JValue]]
    assert(bodies.nonEmpty)
    bodies.foreach { body =>
      (body \ "instructions").extract[List[JValue]].foreach { instr =>
        val raw = (instr \ "rawBytes").extract[String]
        assert(raw.nonEmpty, s"raw bytes missing for ${(body \ "method")}")
      }
    }
  }
}
