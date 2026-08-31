// MixedModeManifestTests — C1-06.
//
// Why this test exists:
//   C++/CLI and native-entrypoint assemblies have bodies that are not
//   comparable to pure-managed output. The corpus plan (doc 01) pins the
//   policy: such assemblies participate in metadata-only parity — tier1
//   goldens exist, tier2 body goldens are excluded — and the manifest tags
//   drive the harness ("mixedMode": true). This test pins both the tag and
//   the golden layout so the Phase 4 harness cannot silently try to compare
//   bodies of mixed-mode assemblies.
//
// Theory of the test:
//   System.Data.SQLite.Core is the curated mixed-mode specimen (its managed
//   System.Data.SQLite.dll is a C++/CLI assembly). The manifest must mark
//   the package mixedMode: true; its goldens must have a tier1 file and no
//   tier2 file; a non-mixed package (Newtonsoft.Json) must have both.

package io.spicelabs.cilantro.metadata

import java.nio.file.Files
import org.json4s._
import org.json4s.native.JsonMethods

class MixedModeManifestTests extends munit.FunSuite {
  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  implicit val formats: DefaultFormats.type = DefaultFormats

  private def manifest(root: java.nio.file.Path): JValue = {
    val text = new String(Files.readAllBytes(root.resolve("manifest.json")), "UTF-8")
    JsonMethods.parse(text)
  }

  test("C1-06: mixed-mode package is tagged and excluded from body goldens") {
    val root = CorpusProvisioner.ensureCorpus()
    val manifestJson = manifest(root)
    val packages = (manifestJson \ "packages").extract[List[JValue]]
    val sqlite = packages
      .find(p => (p \ "id").extract[String] == "Stub.System.Data.SQLite.Core.NetFramework")
      .getOrElse(fail("Stub.System.Data.SQLite.Core.NetFramework missing from manifest"))

    assertEquals(
      (sqlite \ "mixedMode").extract[Boolean],
      true,
      "Stub.System.Data.SQLite.Core.NetFramework must be tagged mixedMode"
    )

    val assemblies = (sqlite \ "assemblies").extract[List[JValue]]
    assert(assemblies.nonEmpty, "mixed-mode package must list assemblies")

    assemblies.foreach { asm =>
      val rel = (asm \ "path").extract[String]
      val tier1 = root.resolve(s"golden/$rel.tier1.json")
      val tier2 = root.resolve(s"golden/$rel.tier2.json")
      assert(Files.isRegularFile(tier1), s"tier1 golden missing for $rel")
      assert(
        !Files.exists(tier2),
        s"mixed-mode assembly must not have a tier2 body golden: $rel"
      )
    }
  }

  test("C1-06: pure-managed packages keep both golden tiers") {
    val root = CorpusProvisioner.ensureCorpus()
    val manifestJson = manifest(root)
    val packages = (manifestJson \ "packages").extract[List[JValue]]
    val newtonsoft = packages
      .find(p => (p \ "id").extract[String] == "Newtonsoft.Json")
      .getOrElse(fail("Newtonsoft.Json missing from manifest"))
    assertEquals((newtonsoft \ "mixedMode").extract[Boolean], false)
    val assemblies = (newtonsoft \ "assemblies").extract[List[JValue]]
    assert(assemblies.nonEmpty)
    assemblies.take(2).foreach { asm =>
      val rel = (asm \ "path").extract[String]
      assert(Files.isRegularFile(root.resolve(s"golden/$rel.tier1.json")))
      assert(Files.isRegularFile(root.resolve(s"golden/$rel.tier2.json")))
    }
  }
}
