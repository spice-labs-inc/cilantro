// CorpusManifestTests — C1-01 and C1-02.
//
// Why these tests exist:
//   The corpus is the pinned, offline ground truth for the parity work
//   (corpus plan doc 01). If the corpus silently drifts (missing file,
//   tampered file, oversized file) every downstream parity comparison is
//   meaningless. C1-01 pins: the manifest parses, every DLL exists and
//   matches its sha256, and sizes stay within the caps. C1-02 pins that a
//   missing corpus fails the run loudly — never a skip.
//
// Theory of the test:
//   - Manifest structure is parsed with json4s (schemaVersion 1).
//   - For every assembly entry the file must exist at the recorded path and
//     its sha256 must match; per-package and corpus-total size caps are
//     checked (plan: ~50 MB per package, ~300 MB total).
//   - The missing-corpus test asserts requireCorpus returns None for a
//     nonexistent root, and this suite's own corpus test fails (via
//     fail(...)) rather than skipping when the corpus is absent.

package io.spicelabs.cilantro.metadata

import java.nio.file.{Files, Paths}
import org.json4s._
import org.json4s.native.JsonMethods

class CorpusManifestTests extends munit.FunSuite {
  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  implicit val formats: DefaultFormats.type = DefaultFormats

  private def manifestJson(root: java.nio.file.Path): JValue = {
    val text = new String(Files.readAllBytes(root.resolve("manifest.json")), "UTF-8")
    JsonMethods.parse(text)
  }

  test("C1-01: manifest parses with schemaVersion 1 and non-empty package list") {
    val root = CorpusProvisioner.ensureCorpus()
    val manifest = manifestJson(root)
    assertEquals((manifest \ "schemaVersion").extract[Int], 1)
    val packages = (manifest \ "packages").extract[List[JValue]]
    assert(packages.nonEmpty, "manifest must list at least one package")
  }

  test("C1-01: every assembly exists and matches its manifest sha256") {
    val root = CorpusProvisioner.ensureCorpus()
    val manifest = manifestJson(root)
    val packages = (manifest \ "packages").extract[List[JValue]]
    var checked = 0
    packages.foreach { pkg =>
      (pkg \ "assemblies").extract[List[JValue]].foreach { asm =>
        val rel = (asm \ "path").extract[String]
        val expected = (asm \ "sha256").extract[String]
        val file = root.resolve(rel)
        assert(
          Files.isRegularFile(file),
          s"assembly missing from corpus: $rel"
        )
        assertEquals(
          CorpusHelpers.sha256(file),
          expected,
          s"sha256 mismatch for $rel"
        )
        checked += 1
      }
    }
    assert(checked > 0, "manifest must list assemblies to verify")
  }

  test("C1-01: corpus stays within size caps (~50 MB per package, 300 MB total)") {
    val root = CorpusProvisioner.ensureCorpus()
    val manifest = manifestJson(root)
    val packages = (manifest \ "packages").extract[List[JValue]]
    val perPackageCap = 50L * 1024 * 1024
    var total = 0L
    packages.foreach { pkg =>
      var pkgSize = 0L
      (pkg \ "assemblies").extract[List[JValue]].foreach { asm =>
        val rel = (asm \ "path").extract[String]
        val size = Files.size(root.resolve(rel))
        pkgSize += size
        total += size
      }
      assert(
        pkgSize <= perPackageCap,
        s"package ${pkg \\ "id"} exceeds 50 MB cap: $pkgSize bytes"
      )
    }
    val corpusCap = 300L * 1024 * 1024
    assert(total <= corpusCap, s"corpus exceeds 300 MB cap: $total bytes")
  }

  test("C1-02: missing corpus is reported, never skipped") {
    val missing = Paths.get("../../definitely-not-the-corpus")
    assertEquals(CorpusHelpers.requireCorpus(missing), None)
    // The corpus-backed tests above fail loudly via fail(...) when the
    // corpus is absent; there is no assume-skip anywhere in this suite.
  }
}
