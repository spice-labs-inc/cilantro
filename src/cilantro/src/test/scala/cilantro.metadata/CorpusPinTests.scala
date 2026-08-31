// CorpusPinTests — C9-01 and C9-04.
//
// Why these tests exist:
//   ADR-0006 commits the unpullable ground truth: the hand-authored fixtures
//   (corpus/fixtures/), their oracle dumps (corpus/golden/fixtures/), the
//   seed list (corpus/packages.json), the integrity manifest
//   (corpus/manifest.json) and the golden index (corpus/golden-index.json).
//   Everything else is regenerable cache. These tests pin the committed
//   ground truth: if it silently drifts (missing file, tampered file,
//   unindexed file), every downstream oracle comparison is meaningless.
//
// Theory of the tests:
//   C9-01: the golden index is complete for the committed oracle tree
//   (every file under corpus/golden/fixtures/ is indexed), every indexed
//   file exists and matches its sha256, and every indexed file parses as
//   JSON in its actual format (tier1/tier2 dumps are gzip-compressed JSON;
//   the resources/debug oracles are plain JSON — detected by gzip magic
//   bytes, not by filename).
//   C9-04: the integrity anchors the fast path and the provisioner trust —
//   manifest.json, packages.json, golden-index.json and the four fixture
//   DLLs — are byte-pinned to hard-coded sha256s. A committed artifact that
//   drifts without updating its pin fails here (and, for the index, fails
//   C9-01 first).
//
// LLM-friendly notes:
//   - These tests touch ONLY committed files — no cache, no docker, no
//     network — so they run in the default suite on every machine.
//   - Format detection uses the gzip magic bytes (0x1F 0x8B); GZIPInputStream
//     throws on truncated gzip data, which is exactly the failure we want.
//   - The sha256 pins are hard-coded constants, not read from the manifest:
//     the pins ARE the test.

package io.spicelabs.cilantro.metadata

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import org.json4s._
import org.json4s.native.JsonMethods

class CorpusPinTests extends munit.FunSuite {

  implicit val formats: DefaultFormats.type = DefaultFormats

  private def root: Path = CorpusHelpers.corpusRoot

  private def isGzip(path: Path): Boolean = {
    val bytes = Files.readAllBytes(path)
    bytes.length >= 2 && (bytes(0) & 0xFF) == 0x1F && (bytes(1) & 0xFF) == 0x8B
  }

  private def readAll(path: Path): String = {
    if (isGzip(path)) {
      val gzip = new GZIPInputStream(new ByteArrayInputStream(Files.readAllBytes(path)))
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
    } else {
      new String(Files.readAllBytes(path), "UTF-8")
    }
  }

  test("C9-01: golden index is complete for the committed oracle tree") {
    val indexFile = root.resolve("golden-index.json")
    assert(Files.isRegularFile(indexFile), s"golden index missing: $indexFile")
    val index = JsonMethods.parse(new String(Files.readAllBytes(indexFile), "UTF-8"))
    assertEquals((index \ "schemaVersion").extract[Int], 1)
    val files = (index \ "files").extract[Map[String, String]]
    assert(files.nonEmpty, "golden index must list at least one file")

    val committedDir = root.resolve("golden/fixtures")
    assert(Files.isDirectory(committedDir), s"committed oracle dir missing: $committedDir")
    val onDisk = {
      val stream = Files.list(committedDir)
      try {
        import scala.jdk.CollectionConverters._
        stream.iterator().asScala.map(_.getFileName.toString).toSet
      } finally {
        stream.close()
      }
    }
    val indexed = files.keys.map(_.stripPrefix("golden/fixtures/")).toSet
    assertEquals(
      onDisk,
      indexed,
      "golden index must cover exactly the committed corpus/golden/fixtures files"
    )
  }

  test("C9-01: every indexed golden exists, hashes to its pin, and parses in its format") {
    val indexFile = root.resolve("golden-index.json")
    val index = JsonMethods.parse(new String(Files.readAllBytes(indexFile), "UTF-8"))
    val files = (index \ "files").extract[Map[String, String]]
    files.foreach { case (rel, expected) =>
      val file = root.resolve(rel)
      assert(Files.isRegularFile(file), s"indexed golden missing from corpus: $rel")
      assertEquals(
        CorpusHelpers.sha256(file),
        expected,
        s"golden sha256 mismatch for $rel"
      )
      val parsed = JsonMethods.parse(readAll(file))
      assert(parsed != JNothing, s"golden does not parse as JSON: $rel")
    }
  }

  test("C9-04: committed integrity anchors are byte-pinned") {
    val pins = Map(
      "manifest.json" -> "babc04c60dc0e9242234e42d4710aae8058459196b8561fa1a7ec0069938d3cf",
      "packages.json" -> "cfcf193e82eac1a95dca4d8382e4e9291ae8d01eb92e09f4ae99acd5bed5fba8",
      "golden-index.json" -> "b1089348f3bc5fbd2e2ce7f5ee4be2011b258f693078626ccd1702db6695483e",
      "fixtures/embedded_pdb_fixture.dll" -> "fee1d7125e7c3673d9a3e352be24a5381172f3bd38555286612c71dceadd7fed",
      "fixtures/ilasm_fixture.dll" -> "d59b452294fb605770b737341e58f9626c0dd792e2ca5724e351cf86775d143f",
      "fixtures/resources_fixture.dll" -> "feb116372ebb40a189d08bcddeb9b7da4ad69613b24f0336df3b538397e3a323",
      "fixtures/x64_fixture.dll" -> "835ccd54a7f132b9986eeb59ede398a9d5ff570f242d30811bd7d98ae075aaab"
    )
    pins.foreach { case (rel, expected) =>
      val file = root.resolve(rel)
      assert(Files.isRegularFile(file), s"committed anchor missing from corpus: $rel")
      assertEquals(
        CorpusHelpers.sha256(file),
        expected,
        s"committed anchor drifted: $rel (update the pin deliberately, never silently)"
      )
    }
  }
}
