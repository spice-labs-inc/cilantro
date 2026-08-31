// CorpusProvisioner — the corpus gate every corpus-touching test goes
// through (ADR-0006).
//
// Why this exists:
//   The corpus cache (corpus/nupkg, corpus/bin, corpus/golden/bin) is
//   regenerable; the committed ground truth (manifest.json, packages.json,
//   golden-index.json, corpus/fixtures/, corpus/golden/fixtures/) is not.
//   A test run must work on a full-cache machine (fast path: pure JVM
//   checks, no docker, no network) AND on a machine whose cache is missing
//   or being populated (slow path: fetch, wait, verify). Population
//   failure must produce a clear actionable message, never a silent skip.
//
// Theory:
//   - ensureCorpusWith returns Either[ProvisionFailure, Path]; the
//     no-arg ensureCorpus() aborts via sys.error
//     so the 15 corpus suites get a failing test with an actionable
//     message. CorpusProvisioningTests assert on the Either directly.
//   - Fast path: manifest parses (schemaVersion 1), every manifest
//     assembly path exists as a non-symlink regular file with matching
//     sha256, every golden-index entry exists with matching sha256, and
//     corpus/golden/bin exists. Missing/corrupt CACHE = recoverable ->
//     populate. Missing/corrupt COMMITTED anchors = not recoverable (a
//     fetch must never rewrite committed ground truth) -> clear error.
//   - Slow path: cross-process FileLock on a lock file OUTSIDE the repo
//     (git clean cannot un-serialize fetchers); in-JVM threads serialize
//     on a monitor (FileLock is per-JVM). Re-check fast path after
//     acquiring. Run the JVM fetch (CorpusFetcher) only when the bin cache
//     is incomplete; run the golden regeneration seam only when
//     corpus/golden/bin is missing. Retry the failing step once. Post-
//     verify re-runs the fast path and compares a snapshot of the
//     committed anchors taken before the fetch.
//   - Success and failure are both memoized per JVM (keyed by canonical
//     root): a failed provision fails fast for later callers instead of
//     triggering N fetches from N suites.
//   - Wall-clock timeout on the fetch/regen steps so a hung download or a
//     stale docker pull cannot hang CI forever.
//
// LLM-friendly notes:
//   - All external calls (file IO, process spawning) are Try-wrapped;
//     failures become ProvisionFailure with a step label, never raw
//     exceptions or stack dumps.
//   - The lock protocol: channel.lock() blocks across processes (a second
//     JVM waits) and reports OverlappingFileLockException within one JVM
//     (serialized by the monitor instead).
//   - Injection seams: Config(root, lockDir, downloader, goldenRegen,
//     fetchTimeoutMs) — unit tests use synthetic corpora in temp dirs and
//     never touch docker or the network.

package io.spicelabs.cilantro.metadata

import java.nio.channels.{FileChannel, FileLock}
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import org.json4s._
import org.json4s.native.JsonMethods
import scala.util.Try

object CorpusProvisioner {

  implicit val formats: DefaultFormats.type = DefaultFormats

  case class ProvisionFailure(step: String, message: String, recoverable: Boolean)

  case class Config(
    root: Path,
    lockDir: Path,
    downloader: CorpusFetcher.Downloader,
    goldenRegen: Path => Either[String, Unit],
    fetchTimeoutMs: Long
  )

  def defaultConfig(): Config = {
    Config(
      root = CorpusHelpers.corpusRoot,
      lockDir = Paths.get(sys.props.getOrElse("user.home", "/tmp"), ".cache", "cilantro"),
      downloader = CorpusFetcher.defaultDownloader,
      goldenRegen = defaultGoldenRegen,
      fetchTimeoutMs = 30L * 60 * 1000
    )
  }

  // --- memoization (success AND failure), keyed by canonical root ---

  private val memo = new ConcurrentHashMap[String, Either[ProvisionFailure, Path]]()
  private val monitor = new Object

  private def key(root: Path): String = root.toAbsolutePath.normalize.toString

  private def remember(cfg: Config, result: Either[ProvisionFailure, Path]): Either[ProvisionFailure, Path] = {
    memo.put(key(cfg.root), result)
    result
  }

  def ensureCorpus(): Path = {
    ensureCorpusWith(defaultConfig()) match {
      case Right(root) => root
      // sys.error raises without the throw keyword (C0-04); munit reports
      // the test as failed with the actionable message.
      case Left(f) => sys.error(f.message)
    }
  }

  // sbt "test:runMain io.spicelabs.cilantro.metadata.CorpusProvisioner" —
  // the CI pre-populate step and scripts/ensure_corpus.sh entry point.
  // Runs the gate against the default corpus and exits non-zero with the
  // actionable message on failure.
  def main(args: Array[String]): Unit = {
    ensureCorpusWith(defaultConfig()) match {
      case Right(root) =>
        Console.out.println(s"corpus ready at ${root.toAbsolutePath.normalize}")
      case Left(f) =>
        Console.err.println(f.message)
        sys.exit(1)
    }
  }

  def ensureCorpusWith(cfg: Config): Either[ProvisionFailure, Path] = {
    val k = key(cfg.root)
    Option(memo.get(k)) match {
      case Some(result) => result
      case None =>
        fastPath(cfg) match {
          case result @ Right(_) => remember(cfg, result)
          case Left(incomplete) if !incomplete.recoverable => remember(cfg, Left(incomplete))
          case Left(_) =>
            monitor.synchronized {
              Option(memo.get(k)) match {
                case Some(result) => result
                case None =>
                  val outcome = withFileLock(lockFileFor(cfg)) {
                    fastPath(cfg) match {
                      case result @ Right(_) => result
                      case Left(second) if !second.recoverable => Left(second)
                      case Left(_) => populate(cfg)
                    }
                  }
                  remember(cfg, outcome)
              }
            }
        }
    }
  }

  // The lock file name is deterministic for a given root (used by the
  // cross-process test to pre-hold the lock from a second JVM).
  def lockFileFor(cfg: Config): Path = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(key(cfg.root).getBytes("UTF-8")).map(b => f"$b%02x").mkString
    cfg.lockDir.resolve(s"corpus-$hash.lock")
  }

  // --- fast path: pure JVM, no docker, no network ---

  private def sha256(path: Path): String = CorpusHelpers.sha256(path)

  private def isRegularFile(p: Path): Boolean = {
    Files.isRegularFile(p) && !Files.isSymbolicLink(p)
  }

  private def validateRel(rel: String): Either[ProvisionFailure, Unit] = {
    if (rel.startsWith("/") || rel.contains("\\") || rel.split("/").contains("..")) {
      Left(ProvisionFailure(
        "invalid manifest",
        s"manifest assembly path escapes the corpus root (rejected): $rel",
        recoverable = false
      ))
    } else {
      Right(())
    }
  }

  private def parseManifest(cfg: Config): Either[ProvisionFailure, JValue] = {
    val manifestFile = cfg.root.resolve("manifest.json")
    if (!isRegularFile(manifestFile)) {
      Left(ProvisionFailure(
        "invalid manifest",
        s"corpus manifest not found at $manifestFile. The manifest is COMMITTED ground truth — " +
          "a fetch cannot create it. Check that sbt runs from src/cilantro (corpusRoot = ../../corpus) " +
          "and that the checkout is complete.",
        recoverable = false
      ))
    } else {
      Try(JsonMethods.parse(new String(Files.readAllBytes(manifestFile), "UTF-8"))) match {
        case scala.util.Failure(t) =>
          Left(ProvisionFailure("invalid manifest", s"manifest does not parse: ${t.getMessage}", recoverable = false))
        case scala.util.Success(manifest) =>
          Try((manifest \ "schemaVersion").extract[Int]) match {
            case scala.util.Success(1) => Right(manifest)
            case _ => Left(ProvisionFailure("invalid manifest", "manifest schemaVersion is not 1", recoverable = false))
          }
      }
    }
  }

  private def parseGoldenIndex(cfg: Config): Either[ProvisionFailure, Map[String, String]] = {
    val indexFile = cfg.root.resolve("golden-index.json")
    if (!isRegularFile(indexFile)) {
      Left(ProvisionFailure(
        "invalid golden index",
        s"committed golden index not found at $indexFile (broken checkout)",
        recoverable = false
      ))
    } else {
      Try(JsonMethods.parse(new String(Files.readAllBytes(indexFile), "UTF-8"))) match {
        case scala.util.Failure(t) =>
          Left(ProvisionFailure("invalid golden index", s"golden index does not parse: ${t.getMessage}", recoverable = false))
        case scala.util.Success(index) =>
          Try((index \ "files").extract[Map[String, String]]) match {
            case scala.util.Success(files) => Right(files)
            case _ => Left(ProvisionFailure("invalid golden index", "golden index has no files map", recoverable = false))
          }
      }
    }
  }

  // Checks whether the bin cache is complete (manifest assemblies only).
  private def cacheComplete(cfg: Config, manifest: JValue): Either[ProvisionFailure, Boolean] = {
    val packages = Try((manifest \ "packages").extract[List[JValue]])
    packages match {
      case scala.util.Failure(t) =>
        Left(ProvisionFailure("invalid manifest", s"manifest packages do not parse: ${t.getMessage}", recoverable = false))
      case scala.util.Success(pkgList) =>
        val problems = scala.collection.mutable.ArrayBuffer.empty[String]
        val checked = pkgList.flatMap(pkg => (pkg \ "assemblies").extract[List[JValue]])
        val outcome = checked.foldLeft(Right(()): Either[ProvisionFailure, Unit]) {
          case (acc @ Left(_), _) => acc
          case (Right(_), asm) =>
            val rel = (asm \ "path").extract[String]
            validateRel(rel) match {
              case Left(f) => Left(f)
              case Right(_) =>
                val expected = (asm \ "sha256").extract[String]
                val file = cfg.root.resolve(rel)
                if (!isRegularFile(file)) {
                  problems += s"missing: $rel"
                  Right(())
                } else if (sha256(file) != expected) {
                  problems += s"sha256 mismatch: $rel"
                  Right(())
                } else {
                  Right(())
                }
            }
        }
        outcome match {
          case Left(f) => Left(f)
          case Right(_) =>
            if (problems.nonEmpty) {
              Left(ProvisionFailure("cache incomplete", problems.mkString("; "), recoverable = true))
            } else {
              Right(true)
            }
        }
    }
  }

  private def fastPath(cfg: Config): Either[ProvisionFailure, Path] = {
    parseManifest(cfg) match {
      case Left(f) => Left(f)
      case Right(manifest) =>
        parseGoldenIndex(cfg) match {
          case Left(f) => Left(f)
          case Right(indexFiles) =>
            val goldenCheck = indexFiles.foldLeft(Right(()): Either[ProvisionFailure, Unit]) {
              case (acc @ Left(_), _) => acc
              case (Right(_), (rel, expected)) =>
                val file = cfg.root.resolve(rel)
                if (!isRegularFile(file)) {
                  Left(ProvisionFailure(
                    "committed golden drift",
                    s"committed golden missing: $rel (broken checkout)",
                    recoverable = false
                  ))
                } else if (sha256(file) != expected) {
                  Left(ProvisionFailure(
                    "committed golden drift",
                    s"committed golden sha256 mismatch: $rel (expected $expected, got ${sha256(file)})",
                    recoverable = false
                  ))
                } else {
                  Right(())
                }
            }
            goldenCheck match {
              case Left(f) => Left(f)
              case Right(_) =>
                cacheComplete(cfg, manifest) match {
                  case Left(incomplete) => Left(incomplete)
                  case Right(_) =>
                    if (!Files.isDirectory(cfg.root.resolve("golden/bin"))) {
                      Left(ProvisionFailure(
                        "golden cache missing",
                        "corpus/golden/bin is absent — the regenerable golden dumps must be re-created",
                        recoverable = true
                      ))
                    } else {
                      Right(cfg.root)
                    }
                }
            }
        }
    }
  }

  // --- slow path: lock, fetch, regenerate, verify ---

  private def withFileLock(lockFile: Path)(body: => Either[ProvisionFailure, Path]): Either[ProvisionFailure, Path] = {
    val mkdirs = Try(Files.createDirectories(lockFile.getParent))
    if (mkdirs.isFailure) {
      return Left(ProvisionFailure(
        "lock",
        s"cannot create lock directory ${lockFile.getParent}: ${mkdirs.failed.get.getMessage}",
        recoverable = false
      ))
    }
    val channel = Try(FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE))
    channel match {
      case scala.util.Failure(t) =>
        Left(ProvisionFailure("lock", s"cannot open lock file $lockFile: ${t.getMessage}", recoverable = false))
      case scala.util.Success(ch) =>
        try {
          var acquired: Option[FileLock] = None
          var attempts = 0
          while (acquired.isEmpty && attempts < 100) {
            try {
              acquired = Some(ch.lock())
            } catch {
              case _: java.nio.channels.OverlappingFileLockException =>
                Thread.sleep(50)
                attempts += 1
            }
          }
          if (acquired.isEmpty) {
            Left(ProvisionFailure("lock", s"lock held by another thread in this JVM: $lockFile", recoverable = false))
          } else {
            try {
              body
            } finally {
              Try(acquired.get.release())
            }
          }
        } finally {
          Try(ch.close())
        }
    }
  }

  private def withTimeout[T](ms: Long, step: String)(body: => Either[ProvisionFailure, T]): Either[ProvisionFailure, T] = {
    val result = new AtomicReference[Option[Either[ProvisionFailure, T]]](None)
    val done = new AtomicBoolean(false)
    val worker = new Thread(
      () => {
        val r = try {
          body
        } catch {
          case t: Throwable =>
            Left(ProvisionFailure(step, s"unexpected failure during $step: ${t.getMessage}", recoverable = true))
        }
        result.set(Some(r))
        done.set(true)
        ()
      },
      s"corpus-provision-$step"
    )
    worker.setDaemon(true)
    worker.start()
    val deadline = System.currentTimeMillis() + ms
    while (!done.get() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50)
    }
    if (!done.get()) {
      worker.interrupt()
      Left(ProvisionFailure("timeout", s"$step did not complete within ${ms}ms", recoverable = true))
    } else {
      result.get().get
    }
  }

  private def populate(cfg: Config): Either[ProvisionFailure, Path] = {
    val snapshotResult = snapshotCommitted(cfg)
    snapshotResult match {
      case Left(f) => Left(f)
      case Right(snapshot) =>
        val manifestResult = parseManifest(cfg)
        val seedResult = {
          val seedFile = cfg.root.resolve("packages.json")
          Try(JsonMethods.parse(new String(Files.readAllBytes(seedFile), "UTF-8")))
        }
        (manifestResult, seedResult) match {
          case (Right(manifest), scala.util.Success(seed)) =>
            val cacheResult = cacheComplete(cfg, manifest)
            cacheResult match {
              case Left(incomplete) =>
                // Step 1: fetch the bin cache (retry once on a fetch
                // failure). A post-fetch fast-path state such as a missing
                // golden/bin is NOT a fetch failure: it is handled by
                // step 2.
                val fetchAttempt = withTimeout(cfg.fetchTimeoutMs, "fetch") {
                  CorpusFetcher.fetch(cfg.root, manifest, seed, cfg.downloader) match {
                    case Right(_) => Right(())
                    case Left(problem) =>
                      Left(ProvisionFailure(problem.step, problem.message, recoverable = true))
                  }
                }
                val fetchResult = fetchAttempt match {
                  case Left(f) if f.recoverable && f.step != "timeout" =>
                    withTimeout(cfg.fetchTimeoutMs, "fetch") {
                      CorpusFetcher.fetch(cfg.root, manifest, seed, cfg.downloader) match {
                        case Right(_) => Right(())
                        case Left(problem) =>
                          Left(ProvisionFailure(problem.step, problem.message, recoverable = true))
                      }
                    }
                  case other => other
                }
                fetchResult match {
                  case Left(f) =>
                    Left(wrap(cfg, f))
                  case Right(_) =>
                    regenerateGoldensIfNeeded(cfg, snapshot)
                }
              case Right(_) => regenerateGoldensIfNeeded(cfg, snapshot)
            }
          case (Left(f), _) => Left(f)
          case (_, scala.util.Failure(t)) =>
            Left(ProvisionFailure(
              "invalid seed",
              s"packages.json does not parse: ${t.getMessage}",
              recoverable = false
            ))
        }
    }
  }

  private def wrap(cfg: Config, f: ProvisionFailure): ProvisionFailure = {
    ProvisionFailure(
      f.step,
      s"corpus population failed at ${cfg.root.toAbsolutePath.normalize} (${f.step}): ${f.message}. " +
        "Manual remedy: scripts/ensure_corpus.sh (requires docker for golden " +
        "regeneration; see docs/OPERATIONS).",
      recoverable = true
    )
  }

  private def regenerateGoldensIfNeeded(cfg: Config, snapshot: Map[String, FileState]): Either[ProvisionFailure, Path] = {
    if (Files.isDirectory(cfg.root.resolve("golden/bin"))) {
      verifyAfter(cfg, snapshot)
    } else {
      val attempt = withTimeout(cfg.fetchTimeoutMs, "golden regen") {
        cfg.goldenRegen(cfg.root) match {
          case Right(_) => verifyAfter(cfg, snapshot)
          case Left(msg) => Left(ProvisionFailure("golden regen", msg, recoverable = true))
        }
      }
      attempt match {
        case Left(f) if f.recoverable && f.step != "timeout" =>
          val retry = withTimeout(cfg.fetchTimeoutMs, "golden regen") {
            cfg.goldenRegen(cfg.root) match {
              case Right(_) => verifyAfter(cfg, snapshot)
              case Left(msg) => Left(ProvisionFailure("golden regen", msg, recoverable = true))
            }
          }
          retry match {
            case Left(f2) =>
              Left(wrap(cfg, f2))
            case ok => ok
          }
        case Left(f) =>
          Left(wrap(cfg, f))
        case ok => ok
      }
    }
  }

  private def verifyAfter(cfg: Config, snapshot: Map[String, FileState]): Either[ProvisionFailure, Path] = {
    // Snapshot comparison FIRST: a population that modified committed
    // ground truth must be reported as such, not masked by a fast-path
    // "committed golden drift" (which would blame the checkout).
    snapshotCommitted(cfg) match {
      case Left(f) => Left(f)
      case Right(after) =>
        val changed = snapshot.collect {
          case (path, before) if after.get(path) != Some(before) => path
        }
        if (changed.nonEmpty) {
          Left(ProvisionFailure(
            "committed ground truth modified",
            s"population modified committed ground truth: ${changed.toSeq.sorted.mkString(", ")}. " +
              "The fetch must never write the committed trees (ADR-0006).",
            recoverable = false
          ))
        } else {
          fastPath(cfg) match {
            case Left(f) if f.recoverable =>
              Left(ProvisionFailure(
                "cache still incomplete",
                s"corpus cache still incomplete after population: ${f.message}",
                recoverable = true
              ))
            case Left(f) => Left(f)
            case Right(_) => Right(cfg.root)
          }
        }
    }
  }

  // --- committed ground truth snapshot (pre/post fetch) ---

  private case class FileState(exists: Boolean, sha256: Option[String])

  private def stateOf(path: Path): FileState = {
    if (Files.isRegularFile(path)) {
      FileState(exists = true, sha256 = Some(sha256(path)))
    } else {
      FileState(exists = false, sha256 = None)
    }
  }

  private def listFiles(dir: Path): Vector[String] = {
    if (!Files.isDirectory(dir)) {
      Vector.empty
    } else {
      val stream = Files.list(dir)
      try {
        import scala.jdk.CollectionConverters._
        stream.iterator().asScala.map(_.getFileName.toString).toVector
      } finally {
        stream.close()
      }
    }
  }

  private def snapshotCommitted(cfg: Config): Either[ProvisionFailure, Map[String, FileState]] = {
    val anchors = List("manifest.json", "packages.json", "golden-index.json")
    val anchorStates = anchors.map(rel => rel -> stateOf(cfg.root.resolve(rel)))
    parseGoldenIndex(cfg) match {
      case Left(f) => Left(f)
      case Right(indexFiles) =>
        val goldenStates = (indexFiles.keys.toSet ++ listFiles(cfg.root.resolve("golden/fixtures")).map(f => s"golden/fixtures/$f"))
          .toSeq
          .map(rel => rel -> stateOf(cfg.root.resolve(rel)))
        val fixtureStates = listFiles(cfg.root.resolve("fixtures")).map(f => s"fixtures/$f" -> stateOf(cfg.root.resolve(s"fixtures/$f")))
        Right((anchorStates ++ goldenStates ++ fixtureStates).toMap)
    }
  }

  // --- golden regeneration seam (docker; the only docker step in the
  // pipeline) ---

  def defaultGoldenRegen: Path => Either[String, Unit] = { root =>
    if (!CorpusHelpers.dockerAvailable()) {
      Left(
        "docker is required to regenerate the golden dumps (house rule): the pinned Mono.Cecil " +
          "dumper is a .NET tool. Install docker, or copy corpus/golden/bin from a machine that " +
          "has it (see docs/OPERATIONS)."
      )
    } else {
      val repoRoot = root.getParent.toAbsolutePath.normalize
      val goldenBin = root.resolve("golden/bin")
      val mk = Try(Files.createDirectories(goldenBin))
      mk match {
        case scala.util.Failure(t) =>
          Left(s"cannot create ${goldenBin}: ${t.getMessage}")
        case scala.util.Success(_) =>
          val image = CorpusHelpers.fetchImageTag
          val buildImage = Try(CorpusHelpers.ensureFetchImage())
          buildImage match {
            case scala.util.Failure(t) =>
              Left(s"failed to prepare the fetch image: ${t.getMessage}")
            case scala.util.Success(_) =>
              val id = Try {
                val out = new java.io.ByteArrayOutputStream()
                val p = new ProcessBuilder("id", "-u").redirectErrorStream(true).start()
                val buf = new Array[Byte](128)
                var n = p.getInputStream.read(buf)
                while (n >= 0) { if (n > 0) out.write(buf, 0, n); n = p.getInputStream.read(buf) }
                p.waitFor()
                out.toString("UTF-8").trim
              }
              id match {
                case scala.util.Failure(t) => Left(s"cannot determine uid: ${t.getMessage}")
                case scala.util.Success(uid) =>
                  val gid = Try {
                    val out = new java.io.ByteArrayOutputStream()
                    val p = new ProcessBuilder("id", "-g").redirectErrorStream(true).start()
                    val buf = new Array[Byte](128)
                    var n = p.getInputStream.read(buf)
                    while (n >= 0) { if (n > 0) out.write(buf, 0, n); n = p.getInputStream.read(buf) }
                    p.waitFor()
                    out.toString("UTF-8").trim
                  }
                  gid match {
                    case scala.util.Failure(t) => Left(s"cannot determine gid: ${t.getMessage}")
                    case scala.util.Success(gid) =>
                      // Prefer the baked binary (fetch_image Dockerfile);
                      // fall back to a copy+build for older images.
                      val command =
                        "if [ -x /opt/goldendumper/GoldenDumper ]; then " +
                          "DUMPER=/opt/goldendumper/GoldenDumper; " +
                          "else rm -rf /tmp/gd-src /tmp/gd && cp -r /work/scripts/GoldenDumper /tmp/gd-src && " +
                          "cd /tmp/gd-src && dotnet build -c Release -v quiet -o /tmp/gd >/tmp/gd-build.log 2>&1 && " +
                          "DUMPER=/tmp/gd/GoldenDumper; fi && " +
                          "$DUMPER dump --manifest /work/corpus/manifest.json " +
                          "--out /work/corpus/golden --fixtures-dir /tmp/no-fixtures"
                      val docker = new ProcessBuilder(
                        "docker", "run", "--rm",
                        "--user", s"$uid:$gid",
                        "-e", "DOTNET_CLI_TELEMETRY_OPTOUT=1",
                        "-e", "DOTNET_NOLOGO=1",
                        "-e", "HOME=/tmp/container-home",
                        "-v", s"$repoRoot:/work:ro",
                        "-v", s"$goldenBin:/work/corpus/golden/bin:rw",
                        "-w", "/work",
                        image,
                        "bash", "-lc", s"mkdir -p $$HOME && $command"
                      ).redirectErrorStream(true).start()
                      val output = new java.io.ByteArrayOutputStream()
                      val stream = docker.getInputStream
                      val buffer = new Array[Byte](8192)
                      var read = stream.read(buffer)
                      while (read >= 0) {
                        if (read > 0) output.write(buffer, 0, read)
                        read = stream.read(buffer)
                      }
                      val code = docker.waitFor()
                      if (code == 0) {
                        Right(())
                      } else {
                        Left(s"golden regeneration failed (exit $code):\n${output.toString("UTF-8")}")
                      }
                  }
              }
          }
      }
    }
  }
}
