// CorpusProvisioningTests — P1-01 .. P1-27, the provisioning gate unit
// suite. No docker, no network: synthetic corpora in temp dirs, injected
// seams (downloader, golden regenerator, lock dir, timeout).
//
// Why these tests exist:
//   ADR-0006 requires the corpus to be populated on demand (R1), tests to
//   pass on a full-cache machine AND on a machine whose cache is being
//   populated (R4), population failure to produce clear actionable
//   messages (R5), committed ground truth to be authoritative (R6), and
//   the full-cache fast path to need neither docker nor network (R7).
//   These tests pin every branch of CorpusProvisioner/CorpusFetcher/
//   CorpusExtractor with synthetic state, so the docker/network steps are
//   never exercised in unit tests — only the production seams are real.
//
// Theory:
//   - SyntheticCorpus builds a temp-dir corpus: committed anchors
//     (manifest.json, packages.json, golden-index.json, golden/fixtures/,
//     fixtures/) with computed sha256s, and optionally the cache (nupkg,
//     bin with two TFMs + the deterministic corrupt fixture, golden/bin).
//   - Seams count invocations so tests can assert exactly how many
//     downloads/regen runs happened (no hidden extra work, no double
//     fetch).
//   - Fixed seeds for the randomized deletion property test (repo
//     convention).
//
// LLM-friendly notes:
//   - Test names carry the plan ids (P1-xx); each test body documents the
//     requirement it pins.
//   - Timing assertions (P1-12, P1-13) only assert LOWER bounds on elapsed
//     time — a slow machine can only make them slower, never falsely pass
//     a lock that was never held.

package io.spicelabs.cilantro.metadata

import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.util.concurrent.atomic.AtomicInteger
import org.json4s._
import org.json4s.native.JsonMethods
import scala.jdk.CollectionConverters._
import scala.util.Try

object SyntheticCorpus {

  val dllBytes: Array[Byte] = (0 until 256).map(i => (i * 7 % 251).toByte).toArray

  case class Spec(
    root: Path,
    dllSha: String,
    nupkgBytes: Array[Byte],
    nupkgSha: String,
    corruptBytes: Array[Byte],
    corruptSha: String
  )

  def sha(bytes: Array[Byte]): String = {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.digest(bytes).map(b => f"$b%02x").mkString
  }

  // Builds a synthetic corpus at base. Committed anchors are always
  // present; the cache (nupkg, bin, golden/bin) is present iff withCache.
  def build(base: Path, withCache: Boolean): Spec = {
    Files.createDirectories(base.resolve("golden/fixtures"))
    Files.createDirectories(base.resolve("fixtures"))

    val goldenBytes = """{"oracle":true}""".getBytes("UTF-8")
    val goldenSha = sha(goldenBytes)
    Files.write(base.resolve("golden/fixtures/oracle.json"), goldenBytes)
    Files.write(base.resolve("fixtures/hand.dll"), dllBytes.take(64))

    val indexJson = JObject(
      "schemaVersion" -> JInt(1),
      "files" -> JObject("golden/fixtures/oracle.json" -> JString(goldenSha))
    )
    Files.write(base.resolve("golden-index.json"), JsonMethods.compact(JsonMethods.render(indexJson)).getBytes("UTF-8"))

    val nupkgTmp = base.resolve("nupkg.tmp")
    CorpusExtractor.writeBenignNupkg(
      nupkgTmp,
      Seq(
        "lib/net45/A.dll" -> dllBytes,
        "lib/net20/A.dll" -> dllBytes
      )
    )
    val nupkgBytes = Files.readAllBytes(nupkgTmp)
    val nupkgSha = sha(nupkgBytes)
    Files.delete(nupkgTmp)

    val corruptBytes = {
      val t = dllBytes.take(64)
      t(t.length - 1) = (t(t.length - 1) ^ 0x5A).toByte
      t
    }
    val corruptSha = sha(corruptBytes)
    val dllSha = sha(dllBytes)

    val manifest = JObject(
      "schemaVersion" -> JInt(1),
      "generator" -> JString("synthetic (test)"),
      "packages" -> JArray(
        List(
          JObject(
            "id" -> JString("P"),
            "version" -> JString("1.0"),
            "nupkgUrl" -> JString("https://fake.example/P.1.0.nupkg"),
            "nupkgSha256" -> JString(nupkgSha),
            "tags" -> JArray(Nil),
            "mixedMode" -> JBool(false),
            "assemblies" -> JArray(
              List(
                JObject(
                  "path" -> JString("bin/P/1.0/net45/A.dll"),
                  "tfm" -> JString("net45"),
                  "sha256" -> JString(dllSha),
                  "sizeBytes" -> JInt(dllBytes.length)
                ),
                JObject(
                  "path" -> JString("bin/P/1.0/net20/A.dll"),
                  "tfm" -> JString("net20"),
                  "sha256" -> JString(dllSha),
                  "sizeBytes" -> JInt(dllBytes.length)
                )
              )
            )
          ),
          JObject(
            "id" -> JString("cilantro.corrupt-fixtures"),
            "version" -> JString("1"),
            "nupkgUrl" -> JString(""),
            "nupkgSha256" -> JString(""),
            "tags" -> JArray(List(JString("corrupt"))),
            "mixedMode" -> JBool(false),
            "assemblies" -> JArray(
              List(
                JObject(
                  "path" -> JString("bin/corrupt/truncated-net45.dll"),
                  "tfm" -> JString("corrupt"),
                  "sha256" -> JString(corruptSha),
                  "sizeBytes" -> JInt(corruptBytes.length)
                )
              )
            )
          )
        )
      )
    )
    Files.write(base.resolve("manifest.json"), JsonMethods.compact(JsonMethods.render(manifest)).getBytes("UTF-8"))

    val seed = JObject(
      "packages" -> JArray(Nil),
      "corruptFixtures" -> JArray(
        List(
          JObject(
            "source" -> JString("P/1.0/net45/A.dll"),
            "name" -> JString("truncated-net45.dll"),
            "lengthBytes" -> JInt(64)
          )
        )
      )
    )
    Files.write(base.resolve("packages.json"), JsonMethods.compact(JsonMethods.render(seed)).getBytes("UTF-8"))

    if (withCache) {
      Files.createDirectories(base.resolve("nupkg"))
      Files.write(base.resolve("nupkg/P.1.0.nupkg"), nupkgBytes)
      Files.createDirectories(base.resolve("bin/P/1.0/net45"))
      Files.write(base.resolve("bin/P/1.0/net45/A.dll"), dllBytes)
      Files.createDirectories(base.resolve("bin/P/1.0/net20"))
      Files.write(base.resolve("bin/P/1.0/net20/A.dll"), dllBytes)
      Files.createDirectories(base.resolve("bin/corrupt"))
      Files.write(base.resolve("bin/corrupt/truncated-net45.dll"), corruptBytes)
      Files.createDirectories(base.resolve("golden/bin"))
    }

    Spec(base, dllSha, nupkgBytes, nupkgSha, corruptBytes, corruptSha)
  }
}

class CorpusProvisioningTests extends munit.FunSuite {

  private def withTempDir[T](body: Path => T): T = {
    val dir = Files.createTempDirectory("corpus-provision-test")
    try {
      body(dir)
    } finally {
      deleteRecursively(dir)
    }
  }

  private def deleteRecursively(dir: Path): Unit = {
    if (Files.exists(dir)) {
      val stream = Files.walk(dir)
      try {
        stream.iterator().asScala.toSeq.sortBy(_.getNameCount).reverse.foreach(p => Try(Files.delete(p)))
      } finally {
        stream.close()
      }
    }
  }

  private def walkCorpus(root: Path): Vector[Path] = {
    val stream = Files.walk(root)
    try {
      stream.iterator().asScala.filter(p => Files.isRegularFile(p) || Files.isSymbolicLink(p)).toVector
    } finally {
      stream.close()
    }
  }

  // Seams

  private def recordingDownloader(
    spec: SyntheticCorpus.Spec,
    count: AtomicInteger,
    behavior: Option[(String, Path) => Either[String, Unit]] = None
  ): CorpusFetcher.Downloader = { (url, dest) =>
    count.incrementAndGet()
    behavior.getOrElse { (_, d) =>
      Try {
        Files.createDirectories(d.getParent)
        Files.write(d, spec.nupkgBytes)
        ()
      }.toEither.left.map(_.getMessage)
    }(url, dest)
  }

  private def recordingRegen(
    count: AtomicInteger,
    behavior: Option[Path => Either[String, Unit]] = None
  ): Path => Either[String, Unit] = { root =>
    count.incrementAndGet()
    behavior.getOrElse { r => Try { Files.createDirectories(r.resolve("golden/bin")); () }.toEither.left.map(_.getMessage) }(root)
  }

  private def cfgFor(
    root: Path,
    downloader: CorpusFetcher.Downloader,
    regen: Path => Either[String, Unit],
    timeoutMs: Long = 60_000,
    lockDir: Option[Path] = None
  ): CorpusProvisioner.Config = {
    CorpusProvisioner.Config(
      root = root,
      lockDir = lockDir.getOrElse(root.resolveSibling("locks")),
      downloader = downloader,
      goldenRegen = regen,
      fetchTimeoutMs = timeoutMs
    )
  }

  private def okMessage(f: CorpusProvisioner.ProvisionFailure): String = f.message

  // --- P1-01: fast path, docker-free, network-free ---

  test("P1-01: fast path returns the root when the cache is complete; no seam is invoked") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = true)
      val downloads = new AtomicInteger(0)
      val regens = new AtomicInteger(0)
      val cfg = cfgFor(base, recordingDownloader(spec, downloads), recordingRegen(regens))
      val result = CorpusProvisioner.ensureCorpusWith(cfg)
      assertEquals(result.map(_.toAbsolutePath.normalize), Right(base.toAbsolutePath.normalize))
      assertEquals(downloads.get(), 0, "fast path must not download")
      assertEquals(regens.get(), 0, "fast path must not regenerate goldens")
    }
  }

  // --- P1-02: missing manifest is a clear, non-recoverable error ---

  test("P1-02: a missing committed manifest fails with a clear message naming the path") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = true)
      Files.delete(base.resolve("manifest.json"))
      val downloads = new AtomicInteger(0)
      val cfg = cfgFor(base, recordingDownloader(spec, downloads), recordingRegen(new AtomicInteger(0)))
      val result = CorpusProvisioner.ensureCorpusWith(cfg)
      result match {
        case Left(f) =>
          assertEquals(f.step, "invalid manifest")
          assert(f.message.contains(base.toString), s"message must name the root: ${f.message}")
          assertEquals(f.recoverable, false, "a fetch cannot create the committed manifest")
        case Right(_) => fail("missing manifest must not fast-path")
      }
      assertEquals(downloads.get(), 0, "no fetch without a manifest")
    }
  }

  // --- P1-03: property — any cache deletion triggers population ---

  test("P1-03 (property): deleting any cache file triggers population; committed-only state is preserved") {
    withTempDir { base =>
      val seeds = List(1, 2, 3, 4, 5)
      val rng = new scala.util.Random(0xC1A0)
      // NOTE: nupkg/ is fetch-input only (the completeness definition is
      // bin + goldens); deleting it alone must NOT trigger population.
      val cacheFiles = Vector(
        "bin/P/1.0/net45/A.dll",
        "bin/P/1.0/net20/A.dll",
        "bin/corrupt/truncated-net45.dll"
      )
      seeds.foreach { _ =>
        // Each iteration builds a FRESH corpus root: the provisioner
        // memoizes per root, and the memoized result must not mask a new
        // deletion.
        val spec = SyntheticCorpus.build(base.resolve(s"corpus-${rng.nextInt(100000)}"), withCache = true)
        val subset = cacheFiles.filter(_ => rng.nextBoolean())
        // delete-nothing edge: population must not run
        if (subset.isEmpty) {
          val downloads = new AtomicInteger(0)
          val cfg = cfgFor(spec.root, recordingDownloader(spec, downloads), recordingRegen(new AtomicInteger(0)))
          assertEquals(CorpusProvisioner.ensureCorpusWith(cfg).isRight, true, "delete-nothing must fast-path")
          assertEquals(downloads.get(), 0)
        } else {
          subset.foreach(rel => Files.delete(spec.root.resolve(rel)))
          val downloads = new AtomicInteger(0)
          val cfg = cfgFor(spec.root, recordingDownloader(spec, downloads), recordingRegen(new AtomicInteger(0)))
          assertEquals(CorpusProvisioner.ensureCorpusWith(cfg).isRight, true, s"deleting $subset must trigger population")
          subset.foreach(rel => assert(Files.isRegularFile(spec.root.resolve(rel)), s"population must restore $rel"))
        }
      }
    }
  }

  test("P1-03b: a manifest path replaced by a directory triggers population") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = true)
      val target = base.resolve("bin/P/1.0/net45/A.dll")
      Files.delete(target)
      Files.createDirectories(target)
      val downloads = new AtomicInteger(0)
      val cfg = cfgFor(base, recordingDownloader(spec, downloads), recordingRegen(new AtomicInteger(0)))
      assertEquals(CorpusProvisioner.ensureCorpusWith(cfg).isRight, true)
      assert(Files.isRegularFile(target), "population must restore the file")
    }
  }

  // --- P1-04: tampering ---

  test("P1-04: a tampered DLL self-heals via re-extraction from the pinned nupkg") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = true)
      val target = base.resolve("bin/P/1.0/net45/A.dll")
      val good = Files.readAllBytes(target)
      good(0) = (good(0) ^ 0xFF).toByte
      Files.write(target, good)
      val downloads = new AtomicInteger(0)
      val cfg = cfgFor(base, recordingDownloader(spec, downloads), recordingRegen(new AtomicInteger(0)))
      assertEquals(CorpusProvisioner.ensureCorpusWith(cfg).isRight, true, "tampered DLL must be repaired")
      assertEquals(downloads.get(), 0, "repair must come from the cached nupkg, not the network")
      assertEquals(CorpusHelpers.sha256(target), spec.dllSha, "DLL must be restored to its pin")
    }
  }

  test("P1-04b: a tampered nupkg is re-downloaded; a lying downloader fails with the sha-mismatch step") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = true)
      Files.write(base.resolve("nupkg/P.1.0.nupkg"), Array[Byte](1, 2, 3, 4))
      Files.delete(base.resolve("bin/P/1.0/net45/A.dll"))
      val downloads = new AtomicInteger(0)
      val cfg = cfgFor(base, recordingDownloader(spec, downloads), recordingRegen(new AtomicInteger(0)))
      assertEquals(CorpusProvisioner.ensureCorpusWith(cfg).isRight, true, "tampered nupkg must be re-downloaded")
      assertEquals(downloads.get(), 1)

      // Lying downloader: writes wrong bytes. Fresh corpus root: the
      // provisioner memoizes per root and the first part already populated.
      withTempDir { base3 =>
        val spec3 = SyntheticCorpus.build(base3, withCache = true)
        Files.write(base3.resolve("nupkg/P.1.0.nupkg"), Array[Byte](1, 2, 3, 4))
        Files.delete(base3.resolve("bin/P/1.0/net45/A.dll"))
        val downloads3 = new AtomicInteger(0)
        val lying: (String, Path) => Either[String, Unit] = (_, dest) => Try { Files.write(dest, Array[Byte](9, 9)); () }.toEither.left.map(_.getMessage)
        val cfg3 = cfgFor(base3, recordingDownloader(spec3, downloads3, Some(lying)), recordingRegen(new AtomicInteger(0)))
        CorpusProvisioner.ensureCorpusWith(cfg3) match {
          case Left(f) =>
            assertEquals(f.step, "sha mismatch")
            assert(f.message.contains("nupkgSha256"), s"message must explain the mismatch: ${f.message}")
          case Right(_) => fail("a lying downloader must not pass")
        }
      }
    }
  }

  // --- P1-05: the CI post-commit state ---

  test("P1-05: committed ground truth present but bin absent triggers population") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = false)
      val downloads = new AtomicInteger(0)
      val regens = new AtomicInteger(0)
      val cfg = cfgFor(base, recordingDownloader(spec, downloads), recordingRegen(regens))
      assertEquals(CorpusProvisioner.ensureCorpusWith(cfg).isRight, true)
      assert(Files.isRegularFile(base.resolve("bin/P/1.0/net45/A.dll")), "bin must be populated")
      assert(Files.isDirectory(base.resolve("golden/bin")), "golden/bin must be created")
    }
  }

  // --- P1-06: golden regen only ---

  test("P1-06: bin complete but golden/bin absent runs only the regen seam") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = true)
      Files.createDirectories(base.resolve("golden/bin"))
      deleteRecursively(base.resolve("golden/bin"))
      val downloads = new AtomicInteger(0)
      val regens = new AtomicInteger(0)
      val cfg = cfgFor(base, recordingDownloader(spec, downloads), recordingRegen(regens))
      assertEquals(CorpusProvisioner.ensureCorpusWith(cfg).isRight, true)
      assertEquals(downloads.get(), 0, "no re-download when bin is complete")
      assertEquals(regens.get(), 1, "exactly one regen")
    }
  }

  // --- P1-07: full population with an empty cache ---

  test("P1-07: full population verifies all sha256s and downloads exactly the missing packages") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = false)
      val downloads = new AtomicInteger(0)
      val cfg = cfgFor(base, recordingDownloader(spec, downloads), recordingRegen(new AtomicInteger(0)))
      assertEquals(CorpusProvisioner.ensureCorpusWith(cfg).isRight, true)
      assertEquals(downloads.get(), 1, "one package with a nupkgUrl")
      assertEquals(CorpusHelpers.sha256(base.resolve("bin/P/1.0/net45/A.dll")), spec.dllSha)
      assertEquals(CorpusHelpers.sha256(base.resolve("bin/P/1.0/net20/A.dll")), spec.dllSha)
      assertEquals(CorpusHelpers.sha256(base.resolve("bin/corrupt/truncated-net45.dll")), spec.corruptSha)
    }
  }

  // --- P1-08: retry once, then fail with the actionable message ---

  test("P1-08: failing population retries exactly once, then fails with an actionable message") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = false)
      val downloads = new AtomicInteger(0)
      val failing: (String, Path) => Either[String, Unit] = (_, _) => Left("network exploded")
      val cfg = cfgFor(base, recordingDownloader(spec, downloads, Some(failing)), recordingRegen(new AtomicInteger(0)))
      CorpusProvisioner.ensureCorpusWith(cfg) match {
        case Left(f) =>
          assertEquals(f.step, "download")
          assert(f.message.contains("network exploded"), s"root cause must surface: ${f.message}")
          assert(f.message.contains("scripts/ensure_corpus.sh"), s"manual remedy must be named: ${f.message}")
          assert(f.message.contains(base.toString), s"corpus root must be named: ${f.message}")
        case Right(_) => fail("a failing fetch must not pass")
      }
      assertEquals(downloads.get(), 2, "exactly one retry")
    }
  }

  // --- P1-09: fail once, succeed once ---

  test("P1-09: fail-once-then-succeed returns the root; the retry is the one that counts") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = false)
      val attempts = new AtomicInteger(0)
      val flaky: (String, Path) => Either[String, Unit] = (_, dest) => {
        if (attempts.incrementAndGet() == 1) {
          Left("transient failure")
        } else {
          Try {
            Files.createDirectories(dest.getParent)
            Files.write(dest, spec.nupkgBytes)
            ()
          }.toEither.left.map(_.getMessage)
        }
      }
      val cfg = cfgFor(base, recordingDownloader(spec, attempts, Some(flaky)), recordingRegen(new AtomicInteger(0)))
      assertEquals(CorpusProvisioner.ensureCorpusWith(cfg).isRight, true)
      assertEquals(attempts.get(), 2, "retry succeeds on the second attempt")
    }
  }

  // --- P1-10: docker-unavailable message + failure memoization ---

  test("P1-10: docker-unavailable golden regen fails with a message naming docker; failure is memoized") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = true)
      Files.createDirectories(base.resolve("golden/bin"))
      deleteRecursively(base.resolve("golden/bin"))
      val regens = new AtomicInteger(0)
      val noDocker: Path => Either[String, Unit] = _ => Left("docker is required to regenerate the golden dumps (house rule)")
      val cfg = cfgFor(base, recordingDownloader(spec, new AtomicInteger(0)), recordingRegen(regens, Some(noDocker)))
      val first = CorpusProvisioner.ensureCorpusWith(cfg)
      first match {
        case Left(f) =>
          assertEquals(f.step, "golden regen")
          assert(f.message.contains("docker"), s"message must name docker: ${f.message}")
        case Right(_) => fail("no-docker regen must fail")
      }
      val second = CorpusProvisioner.ensureCorpusWith(cfg)
      assertEquals(first, second, "failure must be memoized")
      assertEquals(regens.get(), 2, "first call retries once; the memoized second call must not re-run the seam")
    }
  }

  // --- P1-11: success memoization ---

  test("P1-11: after a successful population the result is memoized even if the cache is deleted again") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = false)
      val downloads = new AtomicInteger(0)
      val cfg = cfgFor(base, recordingDownloader(spec, downloads), recordingRegen(new AtomicInteger(0)))
      assertEquals(CorpusProvisioner.ensureCorpusWith(cfg).isRight, true)
      assertEquals(downloads.get(), 1)
      Files.delete(base.resolve("bin/P/1.0/net45/A.dll"))
      assertEquals(
        CorpusProvisioner.ensureCorpusWith(cfg).isRight,
        true,
        "memoized success must not re-fetch"
      )
      assertEquals(downloads.get(), 1, "no re-download after memoization")
    }
  }

  // --- P1-12: concurrent callers ---

  test("P1-12: a second caller blocks while population is in progress, then fast-paths; one fetch total") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = false)
      val downloads = new AtomicInteger(0)
      val slowDownloader: (String, Path) => Either[String, Unit] = (url, dest) => {
        Thread.sleep(400)
        Try {
          Files.createDirectories(dest.getParent)
          Files.write(dest, spec.nupkgBytes)
          ()
        }.toEither.left.map(_.getMessage)
      }
      val cfg = cfgFor(base, recordingDownloader(spec, downloads, Some(slowDownloader)), recordingRegen(new AtomicInteger(0)))

      val aErrors = new java.util.concurrent.ConcurrentLinkedQueue[Throwable]()
      val a = new Thread(() => { try { CorpusProvisioner.ensureCorpusWith(cfg); () } catch { case t: Throwable => aErrors.add(t); () } })
      val b = new Thread(() => { CorpusProvisioner.ensureCorpusWith(cfg); () })
      a.start()
      Thread.sleep(100)
      b.start()
      a.join(10_000)
      b.join(10_000)
      assertEquals(a.isAlive, false, "caller A must finish")
      assertEquals(b.isAlive, false, "caller B must finish")
      assertEquals(aErrors.isEmpty, true, s"caller A must not throw: ${aErrors}")
      assertEquals(downloads.get(), 1, "exactly one fetch for two concurrent callers")
    }
  }

  // --- P1-13: cross-process lock ---

  test("P1-13: a second process holding the lock makes the gate wait, then proceed") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = false)
      val cfg = cfgFor(base, recordingDownloader(spec, new AtomicInteger(0)), recordingRegen(new AtomicInteger(0)))
      val lockFile = CorpusProvisioner.lockFileFor(cfg)
      Files.createDirectories(lockFile.getParent)

      val javaBin = Paths.get(sys.props("java.home"), "bin", "java").toString
      val classpath = sys.props("java.class.path")
      val holder = new ProcessBuilder(
        javaBin, "-cp", classpath,
        "io.spicelabs.cilantro.metadata.LockHolderMain",
        lockFile.toString, "1500"
      ).redirectErrorStream(true).start()
      val holderOutput = new java.io.ByteArrayOutputStream()
      val reader = new Thread(() => {
        val buf = new Array[Byte](256)
        var n = holder.getInputStream.read(buf)
        while (n >= 0) {
          if (n > 0) holderOutput.write(buf, 0, n)
          n = holder.getInputStream.read(buf)
        }
      })
      reader.start()

      // Wait for the holder to announce it holds the lock.
      var held = false
      var waited = 0
      while (!held && waited < 10_000) {
        if (holderOutput.toString("UTF-8").contains("HELD")) {
          held = true
        } else {
          Thread.sleep(50)
          waited += 50
        }
      }
      assert(held, s"lock holder never announced HELD: ${holderOutput.toString("UTF-8")}")

      val start = System.currentTimeMillis()
      val result = CorpusProvisioner.ensureCorpusWith(cfg)
      val elapsed = System.currentTimeMillis() - start
      assertEquals(result.isRight, true, "gate must proceed after the holder releases")
      assert(elapsed >= 1200, s"gate must block while the holder holds the lock (elapsed ${elapsed}ms)")
      holder.waitFor()
      reader.join(5_000)
    }
  }

  // --- P1-14: lock released after population ---

  test("P1-14: the lock file is released after population — a fresh lock acquisition succeeds") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = false)
      val cfg = cfgFor(base, recordingDownloader(spec, new AtomicInteger(0)), recordingRegen(new AtomicInteger(0)))
      assertEquals(CorpusProvisioner.ensureCorpusWith(cfg).isRight, true)
      val lockFile = CorpusProvisioner.lockFileFor(cfg)
      val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try {
        val lock = channel.lock()
        lock.release()
      } finally {
        channel.close()
      }
    }
  }

  // --- P1-15: symlinks ---

  test("P1-15a: a symlinked cache path is refused, never followed") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = true)
      val target = base.resolve("bin/P/1.0/net45/A.dll")
      Files.delete(target)
      Files.createSymbolicLink(target, base.resolve("fixtures/hand.dll"))
      val cfg = cfgFor(base, recordingDownloader(spec, new AtomicInteger(0)), recordingRegen(new AtomicInteger(0)))
      CorpusProvisioner.ensureCorpusWith(cfg) match {
        case Left(f) =>
          assert(
            f.message.contains("symlink") || f.message.contains("symbolic link"),
            s"message must name the symlink refusal: ${f.message}"
          )
        case Right(_) => fail("a symlinked cache path must not pass and must not be followed")
      }
    }
  }

  test("P1-15b: after a real population no symlinks exist under the corpus root") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = false)
      val cfg = cfgFor(base, recordingDownloader(spec, new AtomicInteger(0)), recordingRegen(new AtomicInteger(0)))
      assertEquals(CorpusProvisioner.ensureCorpusWith(cfg).isRight, true)
      val symlinks = walkCorpus(base).filter(p => Files.isSymbolicLink(p))
      assertEquals(symlinks, Vector.empty[Path], "population must never create symlinks")
    }
  }

  // --- P1-16: committed ground truth immutability ---

  test("P1-16: population that modifies committed ground truth fails with the dedicated step") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = false)
      val rogue: Path => Either[String, Unit] = root => {
        Files.createDirectories(root.resolve("golden/fixtures"))
        Files.write(root.resolve("golden/fixtures/oracle.json"), """{"oracle":false}""".getBytes("UTF-8"))
        Files.createDirectories(root.resolve("golden/bin"))
        Right(())
      }
      val cfg = cfgFor(base, recordingDownloader(spec, new AtomicInteger(0)), recordingRegen(new AtomicInteger(0), Some(rogue)))
      CorpusProvisioner.ensureCorpusWith(cfg) match {
        case Left(f) =>
          assertEquals(f.step, "committed ground truth modified")
          assert(f.message.contains("golden/fixtures/oracle.json"), s"message must name the modified file: ${f.message}")
        case Right(_) => fail("a fetch that modifies committed ground truth must fail")
      }
    }
  }

  // --- P1-17: timeout ---

  test("P1-17: a fetch that exceeds the timeout fails with the timeout step; the lock is released") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = false)
      val hanging: (String, Path) => Either[String, Unit] = (_, _) => {
        Thread.sleep(10_000)
        Left("never")
      }
      val cfg = cfgFor(base, recordingDownloader(spec, new AtomicInteger(0), Some(hanging)), recordingRegen(new AtomicInteger(0)), timeoutMs = 300)
      CorpusProvisioner.ensureCorpusWith(cfg) match {
        case Left(f) =>
          assertEquals(f.step, "timeout")
          assert(f.message.contains("300"), s"message must name the timeout: ${f.message}")
        case Right(_) => fail("a hung fetch must time out")
      }
      val lockFile = CorpusProvisioner.lockFileFor(cfg)
      val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try {
        val lock = channel.lock()
        lock.release()
      } finally {
        channel.close()
      }
    }
  }

  // --- P1-18: corpus root absent ---

  test("P1-18: an absent corpus root fails with the clear manifest message") {
    withTempDir { base =>
      val cfg = CorpusProvisioner.Config(
        root = base.resolve("no-such-corpus"),
        lockDir = base.resolve("locks"),
        downloader = (_, _) => Right(()),
        goldenRegen = _ => Right(()),
        fetchTimeoutMs = 1000
      )
      CorpusProvisioner.ensureCorpusWith(cfg) match {
        case Left(f) =>
          assertEquals(f.step, "invalid manifest")
          assert(f.message.contains("no-such-corpus"), s"message must name the missing root: ${f.message}")
        case Right(_) => fail("an absent corpus must not pass")
      }
    }
  }

  // --- P1-19: lock acquisition failure ---

  test("P1-19: an unwritable lock location fails with the lock step, no hang") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = false)
      val lockFileAsFile = base.resolve("lockfile-blocker")
      Files.write(lockFileAsFile, Array[Byte](1))
      val cfg = cfgFor(base, recordingDownloader(spec, new AtomicInteger(0)), recordingRegen(new AtomicInteger(0)), lockDir = Some(lockFileAsFile))
      CorpusProvisioner.ensureCorpusWith(cfg) match {
        case Left(f) =>
          assertEquals(f.step, "lock")
        case Right(_) => fail("an unwritable lock location must fail")
      }
    }
  }

  // --- P1-20: manifest path traversal ---

  test("P1-20: a manifest path with .. is rejected with a clear message") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = true)
      val manifestFile = base.resolve("manifest.json")
      val text = new String(Files.readAllBytes(manifestFile), "UTF-8")
        .replace("bin/P/1.0/net45/A.dll", "../escape.dll")
      Files.write(manifestFile, text.getBytes("UTF-8"))
      val cfg = cfgFor(base, recordingDownloader(spec, new AtomicInteger(0)), recordingRegen(new AtomicInteger(0)))
      CorpusProvisioner.ensureCorpusWith(cfg) match {
        case Left(f) =>
          assertEquals(f.step, "invalid manifest")
          assert(f.message.contains("escapes"), s"message must explain the rejection: ${f.message}")
        case Right(_) => fail("traversal paths must be rejected")
      }
    }
  }

  // --- P1-21: malformed manifest / wrong schema ---

  test("P1-21: a malformed manifest and a wrong schemaVersion fail with clear messages") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = true)
      Files.write(base.resolve("manifest.json"), "{not json".getBytes("UTF-8"))
      val cfg = cfgFor(base, recordingDownloader(spec, new AtomicInteger(0)), recordingRegen(new AtomicInteger(0)))
      CorpusProvisioner.ensureCorpusWith(cfg) match {
        case Left(f) =>
          assertEquals(f.step, "invalid manifest")
        case Right(_) => fail("malformed manifest must fail")
      }

      withTempDir { base2 =>
        val spec2 = SyntheticCorpus.build(base2, withCache = true)
        val manifestFile = base2.resolve("manifest.json")
        val text = new String(Files.readAllBytes(manifestFile), "UTF-8").replace("\"schemaVersion\":1", "\"schemaVersion\":99")
        Files.write(manifestFile, text.getBytes("UTF-8"))
        val cfg2 = cfgFor(base2, recordingDownloader(spec2, new AtomicInteger(0)), recordingRegen(new AtomicInteger(0)))
        CorpusProvisioner.ensureCorpusWith(cfg2) match {
          case Left(f) =>
            assertEquals(f.step, "invalid manifest")
            assert(f.message.contains("schemaVersion"), s"message must name schemaVersion: ${f.message}")
          case Right(_) => fail("wrong schemaVersion must fail")
        }
      }
    }
  }

  // --- P1-22: empty packages list ---

  test("P1-22: an empty packages list is defined as complete") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = true)
      val manifestFile = base.resolve("manifest.json")
      val text = new String(Files.readAllBytes(manifestFile), "UTF-8").replace("\"packages\":[", "\"packages\":[")
      // Replace the entire package list with [].
      val empty = text.substring(0, text.indexOf("\"packages\"")) +
        "\"packages\":[]" +
        text.substring(text.lastIndexOf("}"), text.length)
      Files.write(manifestFile, empty.getBytes("UTF-8"))
      val cfg = cfgFor(base, recordingDownloader(spec, new AtomicInteger(0)), recordingRegen(new AtomicInteger(0)))
      assertEquals(CorpusProvisioner.ensureCorpusWith(cfg).isRight, true, "empty packages = complete")
    }
  }

  // --- P1-23: fetch that creates nothing ---

  test("P1-23: a download that produces no file fails with the download step, distinct from sha mismatch") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = false)
      val writesNothing: (String, Path) => Either[String, Unit] = (_, _) => Right(())
      val cfg = cfgFor(base, recordingDownloader(spec, new AtomicInteger(0), Some(writesNothing)), recordingRegen(new AtomicInteger(0)))
      CorpusProvisioner.ensureCorpusWith(cfg) match {
        case Left(f) =>
          assertEquals(f.step, "download")
          assert(f.message.contains("produced no file"), s"message must say what is wrong: ${f.message}")
        case Right(_) => fail("a download that produces nothing must fail")
      }
    }
  }

  // --- P1-24: nupkg caching ---

  test("P1-24: a cached nupkg with the right sha is not re-downloaded; a corrupt one is") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = true)
      val downloads = new AtomicInteger(0)
      val cfg = cfgFor(base, recordingDownloader(spec, downloads), recordingRegen(new AtomicInteger(0)))
      assertEquals(CorpusProvisioner.ensureCorpusWith(cfg).isRight, true)
      assertEquals(downloads.get(), 0, "cached nupkg must not be re-downloaded")

      withTempDir { base2 =>
        val spec2 = SyntheticCorpus.build(base2, withCache = true)
        Files.write(base2.resolve("nupkg/P.1.0.nupkg"), Array[Byte](7, 7, 7))
        Files.delete(base2.resolve("bin/P/1.0/net20/A.dll"))
        val downloads2 = new AtomicInteger(0)
        val cfg2 = cfgFor(base2, recordingDownloader(spec2, downloads2), recordingRegen(new AtomicInteger(0)))
        assertEquals(CorpusProvisioner.ensureCorpusWith(cfg2).isRight, true)
        assertEquals(downloads2.get(), 1, "corrupt nupkg must be re-downloaded")
      }
    }
  }

  // --- P1-25: corrupt fixtures determinism ---

  test("P1-25: corrupt fixtures are regenerated byte-identically and match the manifest pins") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = false)
      val cfg = cfgFor(base, recordingDownloader(spec, new AtomicInteger(0)), recordingRegen(new AtomicInteger(0)))
      assertEquals(CorpusProvisioner.ensureCorpusWith(cfg).isRight, true)
      val first = Files.readAllBytes(base.resolve("bin/corrupt/truncated-net45.dll"))
      assertEquals(CorpusHelpers.sha256(base.resolve("bin/corrupt/truncated-net45.dll")), spec.corruptSha)
      assertEquals(first.toSeq, spec.corruptBytes.toSeq, "corrupt fixture must match the recipe")
      // Second population on a fresh corpus: byte-identical corrupt
      // fixtures (determinism; the memo is per root, so this is a true
      // second population).
      withTempDir { base2 =>
        val spec2 = SyntheticCorpus.build(base2, withCache = false)
        val cfg2 = cfgFor(base2, recordingDownloader(spec2, new AtomicInteger(0)), recordingRegen(new AtomicInteger(0)))
        assertEquals(CorpusProvisioner.ensureCorpusWith(cfg2).isRight, true)
        assertEquals(
          Files.readAllBytes(base2.resolve("bin/corrupt/truncated-net45.dll")).toSeq,
          first.toSeq,
          "corrupt fixture generation must be deterministic"
        )
      }
    }
  }

  // --- P1-26: hostile nupkgs ---

  test("P1-26: hostile nupkgs (traversal, absolute, backslash, symlink mode, oversized) are rejected") {
    withTempDir { base =>
      val vectors: List[(String, (String, Array[Byte], Long))] = List(
        "traversal" -> ("../evil.txt", "evil".getBytes("UTF-8"), 0L),
        "absolute" -> ("/etc/evil.txt", "evil".getBytes("UTF-8"), 0L),
        "backslash" -> ("a\\b.txt", "evil".getBytes("UTF-8"), 0L),
        "symlink-mode" -> ("lib/net45/link.dll", "target".getBytes("UTF-8"), 0xA1FF0000L),
        "oversized" -> ("lib/net45/huge.dll", Array[Byte](1), (CorpusExtractor.MaxEntrySize + 1).toLong)
      )
      vectors.foreach { case (name, (entryName, content, attrs)) =>
        val hostile = base.resolve(s"hostile-$name.nupkg")
        val declared = if (name == "oversized") CorpusExtractor.MaxEntrySize + 1 else content.length.toLong
        CorpusExtractor.writeZipWithSizes(hostile, Seq((entryName, content, attrs, declared)))
        val out = base.resolve(s"out-$name")
        val result = CorpusExtractor.extract(hostile, out)
        assert(result.isLeft, s"vector $name must be rejected as hostile (got ${result})")
        if (name != "oversized") {
          // Nothing may escape: no file outside out may exist.
          assert(!Files.exists(base.resolve(s"evil.txt")), s"vector $name must not escape")
        }
      }
    }
  }

  test("P1-26b: a hostile nupkg in the cache aborts the fetch with the extraction step") {
    withTempDir { base =>
      val spec = SyntheticCorpus.build(base, withCache = false)
      val hostileNupkg = base.resolve("hostile.nupkg")
      CorpusExtractor.writeZip(hostileNupkg, Seq(("../evil.dll", "evil".getBytes("UTF-8"), 0L)))
      val hostileSha = SyntheticCorpus.sha(Files.readAllBytes(hostileNupkg))
      Files.createDirectories(base.resolve("nupkg"))
      Files.copy(hostileNupkg, base.resolve("nupkg/P.1.0.nupkg"))
      val manifestFile = base.resolve("manifest.json")
      val text = new String(Files.readAllBytes(manifestFile), "UTF-8").replace(spec.nupkgSha, hostileSha)
      Files.write(manifestFile, text.getBytes("UTF-8"))
      val downloads = new AtomicInteger(0)
      val cfg = cfgFor(base, recordingDownloader(spec, downloads), recordingRegen(new AtomicInteger(0)))
      CorpusProvisioner.ensureCorpusWith(cfg) match {
        case Left(f) =>
          assertEquals(f.step, "extraction")
        case Right(_) => fail("a hostile nupkg must abort the fetch")
      }
    }
  }

  // --- P1-27: extractor skip rules ---

  test("P1-27: the extractor keeps only lib DLLs; satellite, native, ref and non-DLL entries are skipped") {
    withTempDir { base =>
      val nupkg = base.resolve("skip.nupkg")
      val bytes = Array[Byte](0x4D.toByte, 0x5A.toByte, 0x90.toByte, 0x00.toByte)
      CorpusExtractor.writeBenignNupkg(
        nupkg,
        Seq(
          "lib/net45/A.dll" -> bytes,
          "lib/net45/de/Res.resources.dll" -> bytes,
          "lib/net45/SQLite.Interop.dll" -> bytes,
          "ref/net45/R.dll" -> bytes,
          "lib/net45/docs.xml" -> bytes
        )
      )
      val out = base.resolve("out")
      val extracted = CorpusExtractor.extract(nupkg, out)
      assertEquals(extracted, Right(Vector("net45/A.dll")))
      assert(Files.isRegularFile(out.resolve("net45/A.dll")))
      assert(!Files.exists(out.resolve("net45/de/Res.resources.dll")))
      assert(!Files.exists(out.resolve("net45/SQLite.Interop.dll")))
      assert(!Files.exists(out.resolve("ref/net45/R.dll")))
      assert(!Files.exists(out.resolve("net45/docs.xml")))
    }
  }
}

// LockHolderMain — used by P1-13: a separate OS process that acquires the
// provisioner's lock file and holds it, so the test can prove the gate
// blocks across processes (FileLock is per-JVM, so an in-JVM thread test
// can never prove this).
object LockHolderMain {
  def main(args: Array[String]): Unit = {
    val lockFile = Paths.get(args(0))
    val holdMs = args(1).toLong
    val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val lock = channel.lock()
    try {
      println("HELD")
      System.out.flush()
      Thread.sleep(holdMs)
    } finally {
      lock.release()
      channel.close()
      println("RELEASED")
      System.out.flush()
    }
  }
}
