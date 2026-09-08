// StreamingContractTests — CP-7: the streaming surface frozen from a
// consumer package (plans 2026_09_02 + 2026_09_04; ADR-0014 +
// byte-faithful amendment B-1..B-11).
//
// Why these tests exist:
//   The handoff freezes the public API — AssemblyWalker's byte-
//   faithful walk (no spool, entries carry length), the AssemblyEntry
//   kinds with their cilantro-owned MIME hints, the PayloadSource
//   .processStream contract, DotnetNameSanitizer, the callback-owned
//   PDB readers (PortablePdbFile.withPdb, MetadataReader
//   .withEmbeddedPdb), and the PDBView/EmbeddedSourceFile shapes — so
//   a signature change breaks the build, not the integrator. This
//   suite lives in a consumer-shaped package (the same visibility
//   Goat Rodeo sees).
//
// Theory of the test:
//   - Compile-time type ascriptions pin every public shape;
//   - a real walk over the smoke assembly exercises the entry surface
//     (kinds, hints, lengths, hardened names, stream content);
//   - the DebugBlob type-17 hint override is a per-entry property
//     pinned in the fixture-walking suites (the smoke assembly has no
//     type-17 entry, so here only the enum defaults are pinned and the
//     entry-hint-equals-kind-hint equality holds);
//   - the sanitizer and the PDB reader shapes are pinned.
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
    // Shape pins (compile-time ascriptions): the walk takes NO spool
    // directory (2026_09_04, B-2).
    val fileWalk: (File, Vector[AssemblyEntry] => Int) => Option[Int] =
      (f, body) => AssemblyWalker.withinAssemblyStream[Int](f)(body)
    val pathWalk: (Path, Vector[AssemblyEntry] => Int) => Option[Int] =
      (p, body) => AssemblyWalker.withinAssemblyStream[Int](p)(body)
    val kindHint: AssemblyEntryKind => Option[String] = (k: AssemblyEntryKind) => k.mimeHint
    val sanitize: String => String = (s: String) => DotnetNameSanitizer.sanitize(s)
    val maxUnits: Int = DotnetNameSanitizer.maxNameUnits
    val kindOf: AssemblyEntry => AssemblyEntryKind = (e: AssemblyEntry) => e.kind
    val nameOf: AssemblyEntry => String = (e: AssemblyEntry) => e.name
    val lengthOf: AssemblyEntry => Long = (e: AssemblyEntry) => e.length

    // The cilantro-owned hint constants (D-4/B-7). The kind set is
    // byte-faithful: no EmbeddedSource (2026_09_04, B-3).
    assertEquals(AssemblyEntryKind.Class.mimeHint, Some("cilantro/type"))
    assertEquals(AssemblyEntryKind.Win32Resource.mimeHint, Some("pe/resource"))
    assertEquals(AssemblyEntryKind.DebugBlob.mimeHint, Some("pe/debug"))
    assertEquals(AssemblyEntryKind.EmbeddedResource.mimeHint, None: Option[String])
    assertEquals(AssemblyEntryKind.AuthenticodeCertificate.mimeHint, None: Option[String])
    assertEquals(
      AssemblyEntryKind.values.map(_.toString).toSet,
      Set("Class", "EmbeddedResource", "AuthenticodeCertificate", "Win32Resource", "DebugBlob"),
      "the walk kind set is byte-faithful (no EmbeddedSource)"
    )

    // A real walk through the consumer entry surface.
    val outcome = AssemblyWalker.withinAssemblyStream[Int](new File("../../test-files/smoke/Smoke.dll")) { entries =>
      assert(entries.nonEmpty, "the smoke assembly has entries")
      val kinds = entries.map(_.kind).toSet
      assert(kinds.contains(AssemblyEntryKind.Class), s"classes present: $kinds")
      entries.foreach { e =>
        // The entry hint matches its kind EXCEPT the DebugBlob type-17
        // override (pe/debug; format=mpdb), which is pinned in the
        // fixture-walking suites; Smoke.dll carries no type-17 entry,
        // so the equality holds here by construction.
        assertEquals(e.mimeHint, kindHint(e.kind), "the entry hint matches its kind (no type-17 in Smoke.dll)")
        assert(e.length >= 0, "entries carry a length")
        assert(e.name.nonEmpty, "names are non-empty")
      }
      entries.length
    }
    assertEquals(outcome.isDefined, true, "the smoke assembly walks")
    assert(outcome.get > 0)

    // Kind-specific carriers expose their data through the trait.
    val seenCert = AssemblyWalker.withinAssemblyStream[Boolean](new File("../../test-files/smoke/Smoke.dll")) { entries =>
      entries.exists {
        case c: AuthenticodeCertificateEntry => c.certificateRevision >= 0
        case _ => false
      }
    }
    assertEquals(seenCert, Some(false), "Smoke.dll is unsigned")
  }

  test("CP-7: the callback-owned portable PDB reader surface is frozen") {
    // 2026-09-04 (B-8): withPdb — f always runs with the outcome;
    // cleanup automatic at the end of the call.
    val withPdbShape: (File, Option[Path], scala.util.Try[Option[PDBView]] => Int) => scala.util.Try[Option[Int]] =
      (f, spool, body) => PortablePdbFile.withPdb[Int](f, spool)(body)
    val withPdbPathShape: (Path, Option[Path], scala.util.Try[Option[PDBView]] => Int) => scala.util.Try[Option[Int]] =
      (p, spool, body) => PortablePdbFile.withPdb[Int](p, spool)(body)
    val probeFile: File => Boolean = (f: File) => PortablePdbFile.isPortablePdb(f)
    val probeName: String => Boolean = (n: String) => PortablePdbFile.isPortablePdb(n)
    assertEquals(withPdbShape.hashCode() != 0, true)
    assertEquals(withPdbPathShape.hashCode() != 0, true)
    assertEquals(probeFile.hashCode() != 0, true)
    assertEquals(probeName.hashCode() != 0, true)
  }

  test("CP-7: the PDB view shapes are frozen") {
    val sourcesOf: PDBView => Vector[EmbeddedSourceFile] = (v: PDBView) => v.sources
    val nameOfSource: EmbeddedSourceFile => String = (s: EmbeddedSourceFile) => s.name
    val sourceIsPayload: EmbeddedSourceFile => PayloadSource = (s: EmbeddedSourceFile) => s
    val processStreamShape: (PayloadSource, InputStream => Int) => Int =
      (p, f) => p.processStream[Int](f)
    // PDBView has no public close() — liveness is callback-owned
    // (B-8); its absence is structural (no closeOf ascription).
    assertEquals(sourcesOf.hashCode() != 0, true)
    assertEquals(nameOfSource.hashCode() != 0, true)
    assertEquals(sourceIsPayload.hashCode() != 0, true)
    assertEquals(processStreamShape.hashCode() != 0, true)
    assertEquals(sanitizeShape.hashCode() != 0, true)
  }

  private val sanitizeShape: String => String = (s: String) => DotnetNameSanitizer.sanitize(s)
}
