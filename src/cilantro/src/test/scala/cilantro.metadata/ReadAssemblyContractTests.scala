// ReadAssemblyContractTests — C0-05.
//
// Why these tests exist:
//   The Phase 0 plan (00_style_and_ground_rules.md) requires the public entry
//   point AssemblyDefinition.readAssembly to return Try[AssemblyDefinition]:
//   missing files and garbage inputs must produce a Failure, valid assemblies
//   a Success, and no exception may escape the call. These tests pin that
//   contract so downstream consumers (Goat Rodeo) can rely on it.
//
// Requirement traced: Phase 0 item 3 "Public entry points become Try/Option
//   (C0-05/06)" and the C0-05 row of the enforcement table.
//
// Theory of the test:
//   - The type ascription (val typed: Try[AssemblyDefinition] = ...) makes
//     the return type part of the compiled contract.
//   - A nonexistent path must yield Failure, not a thrown exception.
//   - A file of garbage bytes must yield Failure.
//   - A real assembly (../../test-files/smoke/Smoke.dll) must yield Success
//     and its identity must match the known fixture.
//
// LLM-friendly notes:
//   - Uses java.nio.Files.write to create the garbage fixture in the OS
//     temp directory; no nulls, no throws, Try match only.

package io.spicelabs.cilantro.metadata

import io.spicelabs.cilantro.*
import java.nio.file.{Files, Path, Paths}
import scala.util.{Failure, Success, Try}

class ReadAssemblyContractTests extends munit.FunSuite {

  private def smokePath: Path =
    Paths.get("../../test-files/smoke/Smoke.dll")

  private def garbageTempFile: Path = {
    val tmp = Files.createTempFile("cilantro-c005", ".dll")
    Files.write(tmp, Array[Byte](0x4d, 0x5a, 0x00, 0x01, 0x7f, 0x7f, 0x7f, 0x7f))
    tmp
  }

  test("C0-05: readAssembly(String) returns Try") {
    val result = AssemblyDefinition.readAssembly(smokePath.toString)
    // Compile-time contract: the ascription binds result to
    // Try[AssemblyDefinition]. Runtime: the valid fixture must be a Success.
    val typed: Try[AssemblyDefinition] = result
    assert(typed.isSuccess)
  }

  test("C0-05: missing file yields Failure, no exception escapes") {
    val result = AssemblyDefinition.readAssembly("/nonexistent/definitely-not-here.dll")
    result match {
      case Failure(_) => ()
      case Success(_) => fail("missing file must produce Failure")
    }
  }

  test("C0-05: garbage bytes yield Failure, no exception escapes") {
    val tmp = garbageTempFile
    try {
      val result = AssemblyDefinition.readAssembly(tmp.toString)
      result match {
        case Failure(_) => ()
        case Success(_) => fail("garbage file must produce Failure")
      }
    } finally {
      Files.deleteIfExists(tmp)
    }
  }

  test("C0-05: valid assembly yields Success with correct identity") {
    val result = AssemblyDefinition.readAssembly(smokePath.toString)
    result match {
      case Failure(t) => fail(s"valid assembly must produce Success: $t")
      case Success(assembly) =>
        assertEquals(assembly.name.map(_.name), Some("Smoke"))
        assert(assembly.mainModule.isDefined)
    }
  }
}
