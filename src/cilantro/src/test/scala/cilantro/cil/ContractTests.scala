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

    val embeddedPdbShape: io.spicelabs.cilantro.MetadataReader =>
      (Option[java.nio.file.Path]) =>
        ((scala.util.Try[Option[io.spicelabs.cilantro.PDBView]]) => Vector[Int]) =>
          scala.util.Try[Option[Vector[Int]]] =
      (r: io.spicelabs.cilantro.MetadataReader) => spool => f => r.withEmbeddedPdb[Vector[Int]](spool)(f)

    val securityDirShape: io.spicelabs.cilantro.PE.Image => Option[io.spicelabs.cilantro.PE.DataDirectory] =
      (i: io.spicelabs.cilantro.PE.Image) => i.securityDirectory

    // Plan 2026_09_02 phase B (D-3/D-8): the payload-bearing model
    // classes are PayloadSources — payloads are processStream views,
    // never whole arrays. The certificate metadata (revision/type)
    // stays on the entry.
    val certificateEntryIsPayload: io.spicelabs.cilantro.CertificateEntry => io.spicelabs.cilantro.PayloadSource =
      (e: io.spicelabs.cilantro.CertificateEntry) => e
    val certificateEntryShape: io.spicelabs.cilantro.CertificateEntry => (Int, Int) =
      (e: io.spicelabs.cilantro.CertificateEntry) => (e.revision, e.certificateType)
    val win32EntryIsPayload: io.spicelabs.cilantro.Win32Resource => io.spicelabs.cilantro.PayloadSource =
      (r: io.spicelabs.cilantro.Win32Resource) => r
    val debugEntryIsPayload: io.spicelabs.cilantro.DebugEntryData => io.spicelabs.cilantro.PayloadSource =
      (d: io.spicelabs.cilantro.DebugEntryData) => d
    val processStreamShape: (io.spicelabs.cilantro.PayloadSource, java.io.InputStream => String) => String =
      (p, f) => p.processStream[String](f)
    val sourceShape: io.spicelabs.cilantro.EmbeddedSourceFile => io.spicelabs.cilantro.PayloadSource =
      (e: io.spicelabs.cilantro.EmbeddedSourceFile) => e
    val sourceNameShape: io.spicelabs.cilantro.EmbeddedSourceFile => String =
      (e: io.spicelabs.cilantro.EmbeddedSourceFile) => e.name
    val pdbViewShape: io.spicelabs.cilantro.PDBView => Vector[io.spicelabs.cilantro.EmbeddedSourceFile] =
      (v: io.spicelabs.cilantro.PDBView) => v.sources

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
    assertEquals(certificateEntryIsPayload.hashCode() != 0, true)
    assertEquals(certificateEntryShape.hashCode() != 0, true)
    assertEquals(win32EntryIsPayload.hashCode() != 0, true)
    assertEquals(debugEntryIsPayload.hashCode() != 0, true)
    assertEquals(processStreamShape.hashCode() != 0, true)
    assertEquals(sourceShape.hashCode() != 0, true)
    assertEquals(sourceNameShape.hashCode() != 0, true)
    assertEquals(pdbViewShape.hashCode() != 0, true)
  }
}
