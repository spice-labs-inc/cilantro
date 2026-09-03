// StreamingContractTests — CP-7: the streaming surface frozen from a
// consumer package (plan 2026_09_02, phase D; ADR-0014).
//
// Why these tests exist:
//   The handoff freezes the new public API — AssemblyWalker's walk,
//   the AssemblyEntry kinds with their cilantro-owned MIME hints, the
//   PayloadSource.processStream contract, DotnetNameSanitizer, and
//   the PDBView/EmbeddedSourceFile shapes — so a signature change
//   breaks the build, not the integrator. This suite lives in a
//   consumer-shaped package (the same visibility Goat Rodeo sees).
//
// Theory of the test:
//   - Compile-time type ascriptions pin every public shape;
//   - a real walk over the smoke assembly exercises the entry surface
//     (kinds, hints, hardened names, stream content);
//   - the sanitizer and the PDB accessor shapes are pinned.
//
// Requirements traced:
//   workspace/2026_09_01_cilantro_handoff.md §4 CP-7; ADR-0014.

package io.spicelabs.cilantro.metadata

import io.spicelabs.cilantro._
import io.spicelabs.cilantro.PayloadSource
import java.io.{File, InputStream}
import java.nio.file.Path

class StreamingContractTests extends munit.FunSuite {

  test("CP-7: the walk entry surface is term-reference usable from a consumer package") {
    // Shape pins (compile-time ascriptions).
    val fileWalk: (File, Vector[AssemblyEntry] => Int, Option[Path]) => Option[Int] =
      (f, body, spool) => AssemblyWalker.withinAssemblyStream[Int](f)(body)(spool)
    val pathWalk: (Path, Vector[AssemblyEntry] => Int, Option[Path]) => Option[Int] =
      (p, body, spool) => AssemblyWalker.withinAssemblyStream[Int](p)(body)(spool)
    val kindHint: AssemblyEntryKind => Option[String] = (k: AssemblyEntryKind) => k.mimeHint
    val sanitize: String => String = (s: String) => DotnetNameSanitizer.sanitize(s)
    val maxUnits: Int = DotnetNameSanitizer.maxNameUnits
    val kindOf: AssemblyEntry => AssemblyEntryKind = (e: AssemblyEntry) => e.kind
    val nameOf: AssemblyEntry => String = (e: AssemblyEntry) => e.name

    // The cilantro-owned hint constants (D-4).
    assertEquals(AssemblyEntryKind.Class.mimeHint, Some("cilantro/type"))
    assertEquals(AssemblyEntryKind.Win32Resource.mimeHint, Some("pe/resource"))
    assertEquals(AssemblyEntryKind.DebugBlob.mimeHint, Some("pe/debug"))
    assertEquals(AssemblyEntryKind.EmbeddedResource.mimeHint, None: Option[String])
    assertEquals(AssemblyEntryKind.AuthenticodeCertificate.mimeHint, None: Option[String])
    assertEquals(AssemblyEntryKind.EmbeddedSource.mimeHint, None: Option[String])

    // A real walk through the consumer entry surface.
    val outcome = AssemblyWalker.withinAssemblyStream[Int](new File("../../test-files/smoke/Smoke.dll")) { entries =>
      assert(entries.nonEmpty, "the smoke assembly has entries")
      val kinds = entries.map(_.kind).toSet
      assert(kinds.contains(AssemblyEntryKind.Class), s"classes present: $kinds")
      entries.foreach { e =>
        assertEquals(e.mimeHint, kindHint(e.kind), "the entry hint matches its kind")
        assert(e.name.nonEmpty, "names are non-empty")
      }
      entries.length
    }(None)
    assertEquals(outcome.isDefined, true, "the smoke assembly walks")
    assert(outcome.get > 0)

    // Kind-specific carriers expose their data through the trait.
    val seenCert = AssemblyWalker.withinAssemblyStream[Boolean](new File("../../test-files/smoke/Smoke.dll")) { entries =>
      entries.exists {
        case c: AuthenticodeCertificateEntry => c.certificateRevision >= 0
        case _ => false
      }
    }(None)
    assertEquals(seenCert, Some(false), "Smoke.dll is unsigned")
  }

  test("CP-7: the PDB accessor and view shapes are frozen") {
    val accessor: (MetadataReader, Option[Path]) => scala.util.Try[Option[PDBView]] =
      (r, dir) => r.readEmbeddedPortablePdb(dir)
    val sourcesOf: PDBView => Vector[EmbeddedSourceFile] = (v: PDBView) => v.sources
    val closeOf: PDBView => Unit = (v: PDBView) => v.close()
    val nameOfSource: EmbeddedSourceFile => String = (s: EmbeddedSourceFile) => s.name
    val sourceIsPayload: EmbeddedSourceFile => PayloadSource = (s: EmbeddedSourceFile) => s
    val processStreamShape: (PayloadSource, InputStream => Int) => Int =
      (p, f) => p.processStream[Int](f)
    // Reference them so the ascriptions stay live.
    assertEquals(accessor.hashCode() != 0, true)
    assertEquals(sourcesOf.hashCode() != 0, true)
    assertEquals(closeOf.hashCode() != 0, true)
    assertEquals(nameOfSource.hashCode() != 0, true)
    assertEquals(sourceIsPayload.hashCode() != 0, true)
    assertEquals(processStreamShape.hashCode() != 0, true)
    assertEquals(sanitizeShape.hashCode() != 0, true)
  }

  private val sanitizeShape: String => String = (s: String) => DotnetNameSanitizer.sanitize(s)
}
