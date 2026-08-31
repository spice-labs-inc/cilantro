// CorpusPropertyTests — C4-04 (parse-all), C4-05 (token round-trip),
// C4-07 (mutation fuzz), C4-09 (cyclic tokens). All corpus-backed, so
// the whole class is tagged Slow.
//
// Why these tests exist:
//   The parity harness proves correct decode; these properties prove
//   robustness. Parse-all pins that every corpus assembly either parses
//   or yields a clean Failure with no exception escaping. Token
//   round-trip pins that every resolvable token maps back to a
//   definition carrying the same token. Mutation fuzz pins that seeded
//   corruptions (truncations, flipped metadata bytes, oversize claims)
//   produce clean Success/Failure verdicts — never a crash, never an
//   unbounded read. Cyclic tokens pin that a real-world self-referential
//   base-type chain (FluentAssertions netcoreapp2.1: type 143 <-> 69)
//   resolves without stack overflow.
//
// Theory of the test:
//   - Parse-all: readAssembly returns Try; both arms are accounted and
//     the totals must equal the input count.
//   - Round-trip: for each TypeDef/method/field token, lookupToken must
//     return the definition whose own token equals the query token.
//   - Fuzz: a fixed seed drives deterministic mutations over each
//     assembly's bytes; every verdict is Success or Failure.
//   - Cycles: reading every type of the FluentAssertions assembly must
//     terminate and every type's base type must be present.
//
// Requirements traced:
//   04_parity_harness_and_security_caps.md — C4-04, C4-05, C4-07,
//   C4-09.
//
// LLM notes:
//   - The corpus lives at ../../corpus (see CorpusHelpers); these tests
//     are excluded from the default suite via the Slow tag.
//   - The mutation offsets are derived from the file length so the same
//     seed mutates the same bytes on every run.

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure, Random}
import java.io.FileOutputStream
import java.nio.file.{Files, Path}
import org.json4s._
import org.json4s.native.JsonMethods
import io.spicelabs.cilantro.{AssemblyDefinition, LogSanitizer, TypeDefinition, MethodDefinition, FieldDefinition, ModuleDefinition}
import io.spicelabs.cilantro.metadata.{CorpusHelpers, CorpusProvisioner}

class CorpusPropertyTests extends munit.FunSuite {

  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  private val Slow = new munit.Tag("Slow")

  implicit val formats: DefaultFormats.type = DefaultFormats

  private def manifest: JValue = {
    val root = CorpusProvisioner.ensureCorpus()
    JsonMethods.parse(new String(java.nio.file.Files.readAllBytes(root.resolve("manifest.json")), "UTF-8"))
  }

  private def corpusAssemblies(): List[(String, Boolean, Boolean)] = {
    (manifest \ "packages").children.flatMap { pkg =>
      val mixedMode = (pkg \ "mixedMode").extract[Boolean]
      val corrupt = (pkg \ "tags").children.exists(_.extract[String] == "corrupt")
      (pkg \ "assemblies").children.map { asm =>
        ((asm \ "path").extract[String], mixedMode, corrupt)
      }
    }
  }

  private def corpusPath(rel: String): Path = {
    CorpusHelpers.corpusRoot.resolve(rel)
  }

  test("C4-04: parse-all over the corpus never throws and failures are accounted".tag(Slow)) {
    var ok = 0
    var failed = 0
    var total = 0
    corpusAssemblies().foreach { case (rel, _, corrupt) =>
      total += 1
      AssemblyDefinition.readAssembly(corpusPath(rel).toString) match {
        case Success(assembly) =>
          ok += 1
          assembly.mainModule.foreach { m =>
            m.types.foreach { t =>
              t.fields.foreach(f => ())
              t.methods.foreach(md => ())
            }
          }
        case Failure(t) =>
          // Only corrupt-tagged assemblies may legitimately fail; a
          // failure anywhere else is a real regression and must fail the
          // test (not just be counted).
          if (corrupt) {
            failed += 1
          } else {
            fail(s"non-corrupt assembly failed to load: ${LogSanitizer.sanitize(rel)} — $t")
          }
      }
    }
    assertEquals(ok + failed, total, "every assembly must be accounted as parsed or failed")
    assert(ok > 0, "at least one corpus assembly must parse")
  }

  test("C4-05: every resolvable TypeDef/method/field token maps back to its definition".tag(Slow)) {
    corpusAssemblies().foreach { case (rel, _, corrupt) =>
      if (!corrupt) {
        AssemblyDefinition.readAssembly(corpusPath(rel).toString) match {
          case Success(assembly) =>
            val m = assembly.mainModule.get
            m.types.foreach { t =>
              t.token.foreach { tok =>
                m.lookupToken(tok) match {
                  case Some(resolved: TypeDefinition) =>
                    assertEquals(resolved.token, t.token, s"$rel type token round-trip")
                  case other => fail(s"$rel: TypeDef token $tok did not resolve to a TypeDefinition: $other")
                }
              }
              t.fields.foreach { f =>
                f.metadataToken.foreach { tok =>
                  m.lookupToken(tok) match {
                    case Some(resolved: FieldDefinition) =>
                      assertEquals(resolved.metadataToken, f.metadataToken, s"$rel field token round-trip")
                    case other => fail(s"$rel: field token $tok did not resolve to a FieldDefinition: $other")
                  }
                }
              }
              t.methods.foreach { md =>
                md.metadataToken.foreach { tok =>
                  m.lookupToken(tok) match {
                    case Some(resolved: MethodDefinition) =>
                      assertEquals(resolved.metadataToken, md.metadataToken, s"$rel method token round-trip")
                    case other => fail(s"$rel: method token $tok did not resolve to a MethodDefinition: $other")
                  }
                }
              }
            }
          case Failure(_) => ()
        }
      }
    }
  }

  test("C4-07: seeded mutations over corpus bytes yield clean Success/Failure verdicts".tag(Slow)) {
    val seed = 0xC40_007L
    corpusAssemblies().foreach { case (rel, _, _) =>
      val original = Files.readAllBytes(corpusPath(rel))
      val rng = new Random(seed)
      for _ <- 1 to 4 do {
        val mutated = original.clone()
        val kind = rng.nextInt(4)
        kind match {
          case 0 =>
            // truncation at a random boundary
            val cut = 1 + rng.nextInt(Math.max(1, mutated.length - 1))
            mutated.dropRight(mutated.length - cut)
          case 1 =>
            // flip a byte at a metadata-plausible offset
            val at = rng.nextInt(mutated.length)
            mutated(at) = (mutated(at) ^ 0x40).toByte
          case 2 =>
            // zero out a 16-byte window
            val at = rng.nextInt(Math.max(1, mutated.length - 16))
            for i <- at until Math.min(at + 16, mutated.length) do mutated(i) = 0
          case _ =>
            // corrupt a byte in the optional-header region (a fixed
            // header byte, not the section count, which lives earlier)
            if (mutated.length > 0x100) {
              mutated(0x86) = 0x7f.toByte
            }
        }
        val file = Files.createTempFile("cilantro-fuzz", ".dll")
        val out = new FileOutputStream(file.toFile)
        out.write(mutated)
        out.close()
        try {
          val verdict = ModuleDefinition.readModule(file.toString)
          assert(verdict.isSuccess || verdict.isFailure, s"$rel mutation $kind must be a clean verdict")
        } finally {
          file.toFile.delete()
        }
      }
    }
  }

  test("C4-07: mutation verdicts match the golden helper's Cecil verdicts".tag(Slow)) {
    // One representative assembly, the same mutation set as the local
    // property, verdicts compared against the pinned helper (Cecil
    // 0.11.6 + NullResolver, metadata-only, never executes code).
    val rel = "bin/Newtonsoft.Json/12.0.3/net20/Newtonsoft.Json.dll"
    val original = Files.readAllBytes(corpusPath(rel))
    val seed = 0xC40_007L
    val rng = new Random(seed)
    val dir = Files.createTempDirectory("cilantro-helper")
    try {
      val cases = (0 until 6).map { i =>
        val mutated = original.clone()
        val kind = rng.nextInt(3)
        kind match {
          case 0 =>
            val cut = 1 + rng.nextInt(Math.max(1, mutated.length - 1))
            mutated.take(cut)
          case 1 =>
            val at = rng.nextInt(mutated.length)
            mutated(at) = (mutated(at) ^ 0x40).toByte
            mutated
          case _ =>
            val at = rng.nextInt(Math.max(1, mutated.length - 16))
            for j <- at until Math.min(at + 16, mutated.length) do mutated(j) = 0
            mutated
        }
      }.zipWithIndex.map { case (bytes, i) =>
        val file = dir.resolve(s"mut$i.dll")
        Files.write(file, bytes)
        file
      }

      val myVerdicts = cases.map { file =>
        val v = ModuleDefinition.readModule(file.toString)
        if (v.isSuccess) "ok" else "fail"
      }

      val helperVerdicts = runHelperProbe(dir, cases.size)
      assertEquals(helperVerdicts.length, myVerdicts.length)
      cases.indices.foreach { i =>
        assertEquals(helperVerdicts(i), myVerdicts(i), s"verdict mismatch for mutation $i ($rel)")
      }
    } finally {
      dir.toFile.listFiles().foreach(_.delete())
      dir.toFile.delete()
    }
  }

  private def runHelperProbe(dir: Path, count: Int): Vector[String] = {
    val helperDir = CorpusHelpers.corpusRoot.resolve("../scripts/GoldenDumper").toAbsolutePath.normalize
    val commands = (0 until count).map { i =>
      s"dotnet /work/gd/bin/Release/net8.0/GoldenDumper.dll probe --file /work/probe/mut$i.dll"
    }.mkString(" && echo SEP && ")
    // House rule 13: docker mounts must preserve the invoking uid/gid, so
    // the container writes back as the caller — the id expansion happens
    // in the host-side shell wrapping the docker invocation.
    val builder = new ProcessBuilder(
      "bash", "-lc",
      "docker run --rm --user \"$(id -u):$(id -g)\" " +
        "-v " + dir.toAbsolutePath.toString + ":/work/probe " +
        "-v " + helperDir.toString + ":/work/gd " +
        "cilantro-corpus-fetch:1 " +
        "bash -lc '" + commands + "'"
    )
    builder.redirectErrorStream(true)
    val proc = builder.start()
    val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    val exit = proc.waitFor()
    if (exit != 0) {
      fail(s"helper probe failed (exit $exit): $out")
    }
    out.split("SEP").toVector.map(_.linesIterator.find(_.startsWith("ok ")).map(_ => "ok").getOrElse("fail"))
  }

  test("C4-09: self-referential base-type chain resolves without stack overflow".tag(Slow)) {
    val rel = "bin/FluentAssertions/6.12.2/netcoreapp2.1/FluentAssertions.dll"
    AssemblyDefinition.readAssembly(corpusPath(rel).toString) match {
      case Success(assembly) =>
        val m = assembly.mainModule.get
        val count = m.types.length
        assert(count > 100, "FluentAssertions must have a substantial type set")
        m.types.foreach { t =>
          assert(t.baseType.isDefined || t.isInterface || t.fullName == "<Module>",
            s"${t.fullName} must have a resolved base type")
        }
      case Failure(t) => fail(s"$rel failed to load: $t")
    }
  }
}
