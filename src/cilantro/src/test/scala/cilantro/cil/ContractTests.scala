// ContractTests — C5-06: the frozen traversal API surface.
//
// Why this test exists:
//   Plan 13 freezes the cilantro API surface Goat Rodeo will consume
//   after the Maven Central publication. These compile-time type
//   ascriptions pin the exact shapes (Option/Try, no null) so a
//   signature change breaks the build, not the integrator.
//
// Requirements traced:
//   13_dll_container_traversal.md C5-06a.
//
// LLM notes:
//   - CanonicalJson.typeToJson is the class identity source;
//   - resources/resourceData, readCertificateEntries,
//     readWin32Resources, readDebugEntryData, readEmbeddedPortablePdb
//     are the container-entry sources.

package io.spicelabs.cilantro.cil

import scala.util.Try

class ContractTests extends munit.FunSuite {

  test("C5-06a: the frozen traversal API shapes compile") {
    val typeToJsonShape: io.spicelabs.cilantro.TypeDefinition => Try[String] =
      io.spicelabs.cilantro.dump.CanonicalJson.typeToJson

    val resourcesShape: io.spicelabs.cilantro.ModuleDefinition => scala.collection.mutable.ArrayBuffer[io.spicelabs.cilantro.Resource] =
      (m: io.spicelabs.cilantro.ModuleDefinition) => m.resources

    val resourceDataShape: io.spicelabs.cilantro.EmbeddedResource => Try[Array[Byte]] =
      (e: io.spicelabs.cilantro.EmbeddedResource) => e.resourceData()

    val certificateEntriesShape: io.spicelabs.cilantro.MetadataReader => scala.collection.mutable.ArrayBuffer[io.spicelabs.cilantro.CertificateEntry] =
      (r: io.spicelabs.cilantro.MetadataReader) => r.readCertificateEntries()

    val win32Shape: io.spicelabs.cilantro.MetadataReader => scala.collection.mutable.ArrayBuffer[io.spicelabs.cilantro.Win32Resource] =
      (r: io.spicelabs.cilantro.MetadataReader) => r.readWin32Resources()

    val debugDataShape: io.spicelabs.cilantro.MetadataReader => scala.collection.mutable.ArrayBuffer[io.spicelabs.cilantro.DebugEntryData] =
      (r: io.spicelabs.cilantro.MetadataReader) => r.readDebugEntryData()

    val embeddedPdbShape: io.spicelabs.cilantro.MetadataReader => Option[io.spicelabs.cilantro.EmbeddedPdb] =
      (r: io.spicelabs.cilantro.MetadataReader) => r.readEmbeddedPortablePdb()

    val securityDirShape: io.spicelabs.cilantro.PE.Image => Option[io.spicelabs.cilantro.PE.DataDirectory] =
      (i: io.spicelabs.cilantro.PE.Image) => i.securityDirectory

    // The ascriptions above are the contract: if any of these shapes
    // change, this file stops compiling. Reference them so the compiler
    // keeps them alive.
    assertEquals(typeToJsonShape.hashCode() != 0, true)
    assertEquals(resourcesShape.hashCode() != 0, true)
    assertEquals(resourceDataShape.hashCode() != 0, true)
    assertEquals(certificateEntriesShape.hashCode() != 0, true)
    assertEquals(win32Shape.hashCode() != 0, true)
    assertEquals(debugDataShape.hashCode() != 0, true)
    assertEquals(embeddedPdbShape.hashCode() != 0, true)
    assertEquals(securityDirShape.hashCode() != 0, true)
  }
}
