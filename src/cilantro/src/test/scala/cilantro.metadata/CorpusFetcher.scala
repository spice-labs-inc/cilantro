// CorpusFetcher — pure-JVM corpus cache population (ADR-0006).
//
// Why this exists:
//   The corpus cache (corpus/nupkg, corpus/bin) is regenerable: the
//   committed manifest.json pins every nupkg and extracted DLL by sha256.
//   Population = download the pinned nupkgs, entry-safe extract the lib
//   DLLs, and generate the deterministic corrupt fixtures. No docker, no
//   .NET — the only docker step in the pipeline is golden regeneration,
//   which lives in the provisioner (CorpusProvisioner), not here.
//
// Theory:
//   - The committed manifest is the source of truth for WHAT to fetch and
//     WHAT it must hash to. A download that does not match nupkgSha256
//     fails the fetch (a fetch may not re-bless the manifest).
//   - Downloads are skipped when the cached nupkg already matches its pin
//     (P1-24); a corrupt cached nupkg is re-downloaded.
//   - Corrupt fixtures: deterministic truncate + last-byte XOR 0x5A of
//     pinned DLLs, exactly the recipe in GoldenDumper Program.cs
//     (verified: the committed manifest pins their sha256s, so P1-25 can
//     assert the Scala output matches the manifest pins).
//   - The downloader is an injectable seam ((url, dest) => Either[...]);
//     the production downloader uses java.net.http with bounded timeouts.
//
// LLM-friendly notes:
//   - No exceptions for flow control: failures are returned as
//     Left(FetchProblem(step, message)).
//   - Extraction uses CorpusExtractor (the entry-safe port); a hostile
//     nupkg aborts the fetch of that package with a clear message.

package io.spicelabs.cilantro.metadata

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.time.Duration
import org.json4s._
import org.json4s.native.JsonMethods
import scala.util.Try

object CorpusFetcher {

  implicit val formats: DefaultFormats.type = DefaultFormats

  case class FetchProblem(step: String, message: String)

  case class Report(downloaded: Int, extracted: Int, corruptFixtures: Int)

  type Downloader = (String, Path) => Either[String, Unit]

  // Production downloader: bounded timeouts, atomic temp+rename.
  val defaultDownloader: Downloader = { (url, dest) =>
    val attempt = Try {
      Files.createDirectories(dest.getParent)
      val tmp = dest.resolveSibling(dest.getFileName.toString + ".tmp")
      val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
      val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMinutes(10))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp))
      if (response.statusCode() == 200) {
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
        Right(())
      } else {
        Files.deleteIfExists(tmp)
        Left(s"HTTP ${response.statusCode()} for $url")
      }
    }
    attempt.recover { case t: Throwable => Left(s"download failed for $url: ${t.getMessage}") }.get
  }

  private def sha256(path: Path): String = CorpusHelpers.sha256(path)

  private def nupkgPath(root: Path, id: String, version: String): Path =
    root.resolve(s"nupkg/$id.$version.nupkg")

  // Fetches the corpus cache. root = corpus root (must contain the
  // committed manifest.json), manifest = parsed manifest, seed = parsed
  // packages.json (carries the corruptFixtures recipe).
  def fetch(root: Path, manifest: JValue, seed: JValue, download: Downloader): Either[FetchProblem, Report] = {
    val corruptSpecs = {
      val builder = Vector.newBuilder[(String, String, Long)]
      (seed \ "corruptFixtures") match {
        case JNothing => ()
        case JArray(items) =>
          items.foreach { item =>
            val source = (item \ "source").extract[String]
            val name = (item \ "name").extract[String]
            val length = (item \ "lengthBytes").extract[Long]
            builder += ((source, name, length))
          }
        case _ => ()
      }
      builder.result()
    }

    val packages = (manifest \ "packages").extract[List[JValue]]
    var downloaded = 0
    var extractedCount = 0
    var failure: Option[FetchProblem] = None

    val iter = packages.iterator
    while (iter.hasNext && failure.isEmpty) {
      val pkg = iter.next()
      val id = (pkg \ "id").extract[String]
      val version = (pkg \ "version").extract[String]
      val url = (pkg \ "nupkgUrl").extract[String]
      val nupkgSha = (pkg \ "nupkgSha256").extract[String]
      if (url.nonEmpty) {
        val dest = nupkgPath(root, id, version)
        val cached = if (Files.isRegularFile(dest) && sha256(dest) == nupkgSha) {
          Right(false)
        } else {
          download(url, dest) match {
            case Left(msg) => Left(msg)
            case Right(_) => Right(true)
          }
        }
        cached match {
          case Left(msg) =>
            failure = Some(FetchProblem("download", s"$id $version: $msg"))
          case Right(newly) =>
            if (newly) {
              downloaded += 1
            }
            if (!Files.isRegularFile(dest)) {
              failure = Some(FetchProblem("download", s"$id $version: download produced no file at $dest"))
            } else if (sha256(dest) != nupkgSha) {
              failure = Some(FetchProblem(
                "sha mismatch",
                s"downloaded nupkg for $id $version does not match manifest nupkgSha256 " +
                  s"(got ${sha256(dest)}, expected $nupkgSha)"
              ))
            } else {
              CorpusExtractor.extract(dest, root.resolve(s"bin/$id/$version")) match {
                case Right(files) => extractedCount += files.size
                case Left(reason) =>
                  failure = Some(FetchProblem("extraction", s"$id $version: $reason"))
              }
            }
        }
      }
    }
    if (failure.isDefined) {
      return Left(failure.get)
    }

    val corruptBin = root.resolve("bin/corrupt")
    var corruptCount = 0
    corruptSpecs.foreach { case (source, name, length) =>
      val sourcePath = root.resolve("bin").resolve(source)
      if (!Files.isRegularFile(sourcePath)) {
        return Left(FetchProblem("corrupt fixtures", s"source missing for corrupt fixture $name: $source"))
      }
      val bytes = Files.readAllBytes(sourcePath)
      if (length > bytes.length) {
        return Left(FetchProblem("corrupt fixtures", s"length $length exceeds source size ${bytes.length} for $name"))
      }
      val truncated = bytes.take(length.toInt)
      if (truncated.nonEmpty) {
        truncated(truncated.length - 1) = (truncated(truncated.length - 1) ^ 0x5A).toByte
      }
      Files.createDirectories(corruptBin)
      Files.write(corruptBin.resolve(name), truncated)
      corruptCount += 1
    }

    Right(Report(downloaded, extractedCount, corruptCount))
  }
}
