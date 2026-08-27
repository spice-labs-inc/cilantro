// CorpusHelpers — shared plumbing for the C1 corpus tests.
//
// Why this exists:
//   The corpus lives outside the sbt project (../../corpus relative to the
//   project dir). Every C1 test that touches it must fail loudly when the
//   corpus is absent (no silent skips), must verify sha256s the same way,
//   and the docker-backed tests (C1-05, C1-07) must run with the invoking
//   user's uid/gid so files written to mounted volumes are owned correctly
//   (house rule).
//
// LLM-friendly notes:
//   - No null, no throw literals: missing-corpus paths return None and
//     callers decide to fail via munit's fail().
//   - dockerAvailable/ensureImage fail the test (not skip) when docker is
//     absent: the corpus suite requires the pinned fetch image.

package io.spicelabs.cilantro.metadata

import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import scala.util.Try

object CorpusHelpers {

  def corpusRoot: Path = Paths.get("../../corpus")

  def requireCorpus(root: Path): Option[Path] = {
    if (Files.isRegularFile(root.resolve("manifest.json"))) Some(root)
    else None
  }

  def sha256(path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val stream = new FileInputStream(path.toFile)
    try {
      val buffer = new Array[Byte](64 * 1024)
      var read = stream.read(buffer)
      while (read >= 0) {
        if (read > 0) {
          digest.update(buffer, 0, read)
        }
        read = stream.read(buffer)
      }
    } finally {
      stream.close()
    }
    digest.digest().map(b => f"$b%02x").mkString
  }

  def readGzipJson(path: Path): String = {
    val bytes = Files.readAllBytes(path)
    val gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes))
    val out = new ByteArrayOutputStream()
    try {
      val buffer = new Array[Byte](64 * 1024)
      var read = gzip.read(buffer)
      while (read >= 0) {
        if (read > 0) {
          out.write(buffer, 0, read)
        }
        read = gzip.read(buffer)
      }
    } finally {
      gzip.close()
    }
    out.toString("UTF-8")
  }

  def fetchImageTag: String = "cilantro-corpus-fetch:1"

  def dockerAvailable(): Boolean = {
    Try {
      val process = new ProcessBuilder("docker", "version").redirectErrorStream(true).start()
      process.waitFor() == 0
    }.getOrElse(false)
  }

  def ensureFetchImage(): Unit = {
    val inspect = new ProcessBuilder("docker", "image", "inspect", fetchImageTag)
      .redirectErrorStream(true)
      .start()
    val present = inspect.waitFor() == 0
    if (!present) {
      val build = new ProcessBuilder(
        "docker", "build", "-t", fetchImageTag, "scripts/fetch_image"
      ).directory(Paths.get("../..").toFile).redirectErrorStream(true).start()
      val builderOutput = new ByteArrayOutputStream()
      val builderStream = build.getInputStream
      val buffer = new Array[Byte](8192)
      var read = builderStream.read(buffer)
      while (read >= 0) {
        if (read > 0) {
          builderOutput.write(buffer, 0, read)
        }
        read = builderStream.read(buffer)
      }
      val code = build.waitFor()
      if (code != 0) {
        sys.error(s"docker build of ${fetchImageTag} failed: ${builderOutput.toString("UTF-8")}")
      }
    }
  }

  // Runs a command inside the pinned fetch image with the invoking user's
  // uid/gid and the repo mounted at /work. Returns (exitCode, combinedOutput).
  private def numericId(kind: String): String = {
    val process = new ProcessBuilder("id", kind).redirectErrorStream(true).start()
    val out = new ByteArrayOutputStream()
    val stream = process.getInputStream
    val buffer = new Array[Byte](256)
    var read = stream.read(buffer)
    while (read >= 0) {
      if (read > 0) {
        out.write(buffer, 0, read)
      }
      read = stream.read(buffer)
    }
    process.waitFor()
    out.toString("UTF-8").trim
  }

  def runInFetchImage(command: String): (Int, String) = {
    val docker = new ProcessBuilder(
      "docker", "run", "--rm",
      "--user", s"${numericId("-u")}:${numericId("-g")}",
      "--network", "host",
      "-e", "DOTNET_CLI_TELEMETRY_OPTOUT=1",
      "-e", "DOTNET_NOLOGO=1",
      "-e", "HOME=/tmp/container-home",
      "-v", s"${Paths.get("../..").toAbsolutePath.normalize()}:/work",
      "-w", "/work",
      fetchImageTag,
      "bash", "-lc", s"mkdir -p $$HOME && $command"
    ).redirectErrorStream(true).start()
    val output = new ByteArrayOutputStream()
    val stream = docker.getInputStream
    val buffer = new Array[Byte](8192)
    var read = stream.read(buffer)
    while (read >= 0) {
      if (read > 0) {
        output.write(buffer, 0, read)
      }
      read = stream.read(buffer)
    }
    val code = docker.waitFor()
    (code, output.toString("UTF-8"))
  }

  // Builds GoldenDumper inside the image and returns the path to the binary.
  def ensureGoldenDumperBuilt(): Unit = {
    val (code, output) = runInFetchImage(
      "cd /work/scripts/GoldenDumper && dotnet build -c Release -v quiet"
    )
    if (code != 0) {
      sys.error(s"GoldenDumper build failed:\n$output")
    }
  }
}
