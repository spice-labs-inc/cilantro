// ParityHarnessTests — C4-01 (the Phase 4 gate) and C4-10.
//
// Why this test exists:
//   The corpus goldens are the machine-checkable ground truth produced by
//   Mono.Cecil 0.11.6. This harness dumps our model in the SAME
//   canonicalized shapes and diffs byte-for-byte against the goldens:
//   tier 1 (full model) for every corpus assembly, tier 2 (instructions
//   with raw operand bytes + resolved operands + EH handlers) for every
//   non-mixed-mode assembly. The full-corpus run is tagged Slow and runs
//   explicitly in every phase exit review; the default regression keeps a
//   fast fixture-level diff (C4-10 pins the tag).
//
// Theory of the test:
//   ParityDumper emits compact JSON with fixed key order; the golden is
//   the gzip JSON from the oracle. Byte equality means identical models.

package io.spicelabs.cilantro.cil

import java.io.{PrintWriter, StringWriter}
import org.json4s._
import org.json4s.native.JsonMethods
import io.spicelabs.cilantro.{AssemblyDefinition, LogSanitizer}
import io.spicelabs.cilantro.metadata.CorpusHelpers

class ParityHarnessTests extends munit.FunSuite {

  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  implicit val formats: DefaultFormats.type = DefaultFormats

  val Slow = new munit.Tag("Slow")

  private def manifest: JValue = {
    CorpusHelpers.requireCorpus(CorpusHelpers.corpusRoot) match {
      case None => fail("corpus missing at ../../corpus — run scripts/ensure_corpus.sh")
      case Some(root) =>
        JsonMethods.parse(new String(java.nio.file.Files.readAllBytes(root.resolve("manifest.json")), "UTF-8"))
    }
  }

  private def corpusRoot = CorpusHelpers.corpusRoot

  private def dumpTier1(assembly: AssemblyDefinition, label: String): String = {
    val writer = new StringWriter()
    ParityDumper.dumpTier1(new PrintWriter(writer), assembly, label)
    writer.toString
  }

  private def dumpTier2(assembly: AssemblyDefinition, label: String): String = {
    val writer = new StringWriter()
    ParityDumper.dumpTier2(new PrintWriter(writer), assembly, label)
    writer.toString
  }

  private def goldenText(rel: String): String = {
    CorpusHelpers.readGzipJson(corpusRoot.resolve(rel))
  }

  private def diffAssembly(rel: String, mixedMode: Boolean, corrupt: Boolean): Unit = {
    // Corpus-derived strings are attacker-controlled names: everything
    // that lands in a message goes through the log sanitizer.
    val safeRel = LogSanitizer.sanitize(rel)
    val full = "../../corpus/" + rel
    AssemblyDefinition.readAssembly(full) match {
      case scala.util.Success(a) =>
        // The oracle names fixture goldens after the file base name
        // (without the .dll extension); corpus assembly goldens keep
        // the full path.
        val goldenRel = if (rel.startsWith("fixtures/") && rel.endsWith(".dll")) rel.stripSuffix(".dll") else rel

        val tier1 = dumpTier1(a, rel)
        val expected1 = goldenText("golden/" + goldenRel + ".tier1.json")
        assertEquals(tier1, expected1, s"tier1 diff for $safeRel")

        if (!mixedMode && !corrupt) {
          val tier2 = dumpTier2(a, rel)
          val expected2 = goldenText("golden/" + goldenRel + ".tier2.json")
          assertEquals(tier2, expected2, s"tier2 diff for $safeRel")
        }
      case scala.util.Failure(t) =>
        if (!corrupt) {
          // corrupt fixtures legitimately fail to load
          fail(s"failed to load $safeRel: $t")
        }
    }
  }

  private def packageAssemblies(pkg: JValue): List[(String, Boolean, Boolean)] = {
    val mixedMode = (pkg \ "mixedMode").extract[Boolean]
    val corrupt = (pkg \ "tags").children.exists(_.extract[String] == "corrupt")
    (pkg \ "assemblies").children.map { asm =>
      ((asm \ "path").extract[String], mixedMode, corrupt)
    }
  }

  test("C4-01: fixture assemblies diff against the goldens (fast gate)") {
    val assemblies = List(
      ("fixtures/ilasm_fixture.dll", false, false),
      ("fixtures/x64_fixture.dll", false, false),
      ("bin/Newtonsoft.Json/12.0.3/net20/Newtonsoft.Json.dll", false, false)
    )
    assemblies.foreach { case (rel, mixed, corrupt) => diffAssembly(rel, mixed, corrupt) }
  }

  test("C4-01: full-corpus tier1/tier2 diff passes against pinned goldens".tag(Slow)) {
    val packages = (manifest \ "packages").children
    packages.foreach { pkg =>
      packageAssemblies(pkg).foreach { case (rel, mixed, corrupt) =>
        diffAssembly(rel, mixed, corrupt)
      }
    }
  }
}
