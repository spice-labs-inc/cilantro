// RvaMappingTests — C3-05.
//
// Why this test exists:
//   readBody resolves a method RVA to a file offset through the PE
//   section table; errors here (off-by-one at section edges, PE32+ vs
//   PE32 math, wrong delta) silently decode the wrong bytes. This test
//   pins the mapping on REAL images — the ilasm fixture (PE32) and the
//   x64 fixture (PE32+) — including section-start, section-end and
//   unmapped RVAs, and pins the RVA == 0 -> None-shaped body rule.
//
// Theory of the test:
//   For every section of a loaded Image, resolveVirtualAddress at the
//   section start must equal PointerToRawData and start+size-1 must map
//   one byte before the end; RVAs beyond the image map to None. The
//   x64 fixture must load as PE32+ (amd64). A synthetic method with
//   RVA 0 yields Success(None) from readBody.

package io.spicelabs.cilantro.cil

import scala.util.{Failure, Success}
import io.spicelabs.cilantro.{AssemblyDefinition, MethodDefinition, TargetArchitecture, TypeReference}
import io.spicelabs.cilantro.metadata.CorpusProvisioner

class RvaMappingTests extends munit.FunSuite {
  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  private def loadAssembly(rel: String): AssemblyDefinition = {
    val root = CorpusProvisioner.ensureCorpus()
    AssemblyDefinition.readAssembly(root.resolve(rel).toString) match {
      case Success(assembly) => assembly
      case Failure(t) => fail(s"failed to load $rel: $t")
    }
  }

  test("C3-05: PE32 section start and end map exactly") {
    val assembly = loadAssembly("fixtures/ilasm_fixture.dll")
    val image = assembly.mainModule.get.image.get
    assert(image.sections.nonEmpty)
    image.sections.foreach { section =>
      assertEquals(
        image.resolveVirtualAddress(section.virtualAddress),
        Some(section.pointerToRawData),
        s"section ${section.name.getOrElse("?")} start must map to its raw data pointer"
      )
      assertEquals(
        image.resolveVirtualAddress(section.virtualAddress + section.sizeOfRawData - 1),
        Some(section.pointerToRawData + section.sizeOfRawData - 1),
        s"section ${section.name.getOrElse("?")} last byte must map"
      )
    }
  }

  test("C3-05: RVAs beyond every section map to None") {
    val assembly = loadAssembly("fixtures/ilasm_fixture.dll")
    val image = assembly.mainModule.get.image.get
    assertEquals(image.resolveVirtualAddress(Int.MaxValue), None)
  }

  test("C3-05: the x64 fixture loads as PE32+") {
    val assembly = loadAssembly("fixtures/x64_fixture.dll")
    val image = assembly.mainModule.get.image.get
    assertEquals(image.architecture, TargetArchitecture.amd64)
    // And its sections map through the same math.
    image.sections.foreach { section =>
      assertEquals(
        image.resolveVirtualAddress(section.virtualAddress),
        Some(section.pointerToRawData)
      )
    }
  }

  test("C3-05: RVA == 0 yields a None-shaped body, no bogus read") {
    val method = MethodDefinition("M", 0, TypeReference("", ""))
    MethodBodyReader.readBody(method) match {
      case Success(result) => assertEquals(result, None)
      case Failure(t) => fail(s"RVA 0 must be Success(None), got $t")
    }
  }

  test("C3-05: an RVA outside the file yields a clean Failure") {
    val method = MethodDefinition("M", 0, TypeReference("", ""))
    method._rva = 0x7fffff00
    val assembly = loadAssembly("fixtures/ilasm_fixture.dll")
    method.declaringType = assembly.mainModule.get.types.head
    MethodBodyReader.readBody(method) match {
      case Success(_) => fail("unmapped RVA must be a Failure")
      case Failure(_) => ()
    }
  }
}
