// OptionAccessorTests — C0-06.
//
// Why these tests exist:
//   The Phase 0 plan (00_style_and_ground_rules.md) requires every nullable
//   accessor on the public model to return Option: absent values are None,
//   present values are Some, and there is no NPE path. These tests pin the
//   Option-ness of the accessors Goat Rodeo and future phases consume.
//
// Requirement traced: Phase 0 item 3 "Public entry points become Try/Option
//   (C0-05/06)" and the C0-06 row of the enforcement table.
//
// Theory of the test:
//   - Type ascriptions make the Option return types part of the compiled
//     contract (a reversion to a raw type breaks compilation).
//   - A detached TypeReference created via the public constructor has no
//     declaring type, no module and no scope: all must be None.
//   - A real assembly must have a Some name and Some mainModule.
//
// LLM-friendly notes:
//   - No nulls, no throws; Option/for-comprehension only.

package io.spicelabs.cilantro.metadata

import io.spicelabs.cilantro.*
import java.nio.file.Paths

class OptionAccessorTests extends munit.FunSuite {

  test("C0-06: TypeReference accessors are Option and None when detached") {
    val tr = TypeReference("", "")
    val declaringType: Option[TypeReference] = tr.declaringType
    val module: Option[ModuleDefinition] = tr.module
    val scope: Option[MetadataScope] = tr.scope
    assert(declaringType.isEmpty)
    assert(module.isEmpty)
    assert(scope.isEmpty)
    assert(tr.projection.isEmpty)
    assert(tr.metadataToken.isEmpty)
  }

  test("C0-06: AssemblyDefinition accessors are Option") {
    val path = Paths.get("../../test-files/smoke/Smoke.dll").toString
    val assembly = AssemblyDefinition.readAssembly(path).get
    val name: Option[AssemblyNameDefinition] = assembly.name
    val mainModule: Option[ModuleDefinition] = assembly.mainModule
    assert(name.isDefined)
    assert(mainModule.isDefined)
    val fullName: String = assembly.fullName
    assert(fullName.nonEmpty)
  }

  test("C0-06: absent optional accessors never throw") {
    val assembly = AssemblyDefinition.readAssembly("/nonexistent/nope.dll")
    assert(assembly.isFailure)
    // A plain TypeReference has no image, no metadata token and no reader
    // path; all accessors must degrade to None instead of throwing.
    val tr = TypeReference("", "")
    val element = for {
      m <- tr.module
      img <- m.image
    } yield img
    assert(element.isEmpty)
  }
}
