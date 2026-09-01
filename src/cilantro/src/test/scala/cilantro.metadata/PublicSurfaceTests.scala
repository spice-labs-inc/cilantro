// PublicSurfaceTests — H7-xx: the frozen CanonicalJson API surface.
//
// Why these tests exist:
//   The suggestions doc reported that
//   classOf[io.spicelabs.cilantro.dump.CanonicalJson] fails for
//   consumers while the term-reference form works. Planning verified
//   (by compilation) that this is a general Scala 3 restriction —
//   classOf on ANY object fails ("type X is not a member of ...") —
//   NOT a TASTy-visibility defect. The API is public and usable; this
//   suite pins the compilable consumer forms so the GR_INTEGRATION
//   contract ("frozen API") cannot silently regress, and documents the
//   class-literal story in GR_INTEGRATION.md.
//
// Theory of the test:
//   - This suite lives in a DIFFERENT package than `dump`, so it is a
//     consumer-shaped compilation unit (the same visibility the Goat
//     Rodeo consumer sees).
//   - H7-01 exercises the term-reference forms the contract lists
//     (typeToJson, writeType, q) against a real fixture assembly.
//   - H7-02 pins the class-object forms that DO compile:
//     classOf[CanonicalJson.type] and CanonicalJson.getClass, both of
//     which yield the module class (CanonicalJson$). The failing form
//     (classOf[CanonicalJson]) is a language restriction, documented
//     in GR_INTEGRATION.md, not asserted at runtime.
//
// Requirements traced:
//   plans/2026_09_01_cilantro_hardening_and_dotnet_probe/phase-07.md
//   H7-01..H7-03 (suggestion cilantro #7; GR_INTEGRATION contract).
//
// LLM notes:
//   - Scala 3.7.1: `classOf[X]` requires a class type; objects are
//     module values whose type is X.type. `classOf[X.type]` compiles
//     and returns the module class.
//   - The JSON envelope is byte-fixed: {"format":"cilantro-type",
//     "version":1,"type":...}.

package io.spicelabs.cilantro.metadata

import scala.util.{Success, Failure}
import io.spicelabs.cilantro.{AssemblyDefinition, TypeDefinition}
import io.spicelabs.cilantro.dump.CanonicalJson
import java.io.PrintWriter
import java.io.StringWriter

class PublicSurfaceTests extends munit.FunSuite {

  private def loadSmoke(): AssemblyDefinition = {
    AssemblyDefinition.readAssembly("../../test-files/smoke/Smoke.dll").getOrElse(fail("Smoke.dll must read"))
  }

  test("H7-01: the frozen API is term-reference usable from a consumer package") {
    val assembly = loadSmoke()
    val module = assembly.mainModule.getOrElse(fail("the smoke assembly must have a main module"))
    val t: TypeDefinition = module.types(0)
    // typeToJson: the documented Try[String] entry with the frozen envelope.
    CanonicalJson.typeToJson(t) match {
      case Success(json) =>
        assert(json.startsWith("{\"format\":\"cilantro-type\",\"version\":1,\"type\":"), "the frozen envelope")
        assert(json.endsWith("}"))
      case Failure(e) => fail(s"typeToJson must succeed on a real type: $e")
    }
    // writeType: the direct writer form (used by ParityDumper).
    val w = new StringWriter()
    CanonicalJson.writeType(new PrintWriter(w), t)
    assert(w.toString.startsWith("{\"fullName\":"), "writeType emits the type object")
    // q: the escape helper.
    assertEquals(CanonicalJson.q("a\"b"), "\"a\\u0022b\"")
  }

  test("H7-02: class-object access forms compile and agree") {
    // The compilable class-literal form for a Scala 3 object.
    val viaClassOf: Class[?] = classOf[io.spicelabs.cilantro.dump.CanonicalJson.type]
    val viaGetClass: Class[?] = io.spicelabs.cilantro.dump.CanonicalJson.getClass
    assertEquals(viaClassOf, viaGetClass, "classOf[CanonicalJson.type] is the module class")
    assertEquals(viaClassOf.getName, "io.spicelabs.cilantro.dump.CanonicalJson$")
    // The term form resolves (the actual API consumers use).
    val fn: TypeDefinition => scala.util.Try[String] = CanonicalJson.typeToJson
    val t: TypeDefinition = loadSmoke().mainModule.getOrElse(fail("no main module")).types(0)
    assert(fn(t).isSuccess, "the term reference is callable")
  }

  test("H7-03: the documented consumer pattern (module.read) still compiles and runs") {
    // GR_INTEGRATION.md documents the established pattern:
    // module.read(value, (_, reader) => reader.xxx). Pin it here so the
    // contract doc's primary claim has a test.
    val assembly = loadSmoke()
    val module = assembly.mainModule.getOrElse(fail("no main module"))
    val result: Int = module.read("unused", (_, _: io.spicelabs.cilantro.MetadataReader) => 42)
    assertEquals(result, 42)
  }
}
