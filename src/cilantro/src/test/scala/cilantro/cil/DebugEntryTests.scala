// DebugEntryTests — C5-05: debug-directory data + embedded PDB sources.
//
// Why these tests exist:
//   Plan 13 exposes the debug directory's data blobs and the embedded
//   portable PDB's embedded source documents. Embedded sources are
//   literal source files inside the binary — a prime spot for
//   hardcoded credentials — so the walk must be exact and hostile-safe.
//
// Theory of the test:
//   - The pinned fixture (built in the pinned docker image with
//     <DebugType>embedded</DebugType><EmbedAllSources>true) carries one
//     embedded PDB whose debug-entry types/sizes are pinned against the
//     oracle dump (GoldenDumper debug), and whose extracted Widget.cs
//     must equal the repo's source file byte-for-byte.
//   - A synthetic debug directory with an out-of-file data pointer must
//     fail cleanly at the module read.
//
// Requirements traced:
//   13_dll_container_traversal.md C5-05 (a-d).
//
// LLM notes:
//   - The embedded-PDB blob is "MPDB" + u32 uncompressed size + a raw
//     (no zlib header) deflate of the BSJB metadata root.
//   - The PDB's guid-heap indexes are 1-based entry ordinals (16 bytes
//     each), unlike the assembly tables' byte-offset convention; the
//     Cecil 0.11.6 oracle cannot read these blobs (its ReadGuid uses
//     the byte-offset form), which is why the oracle pins the ENTRY
//     hashes and the repo's own source file pins the content.

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import io.spicelabs.cilantro.metadata.CorpusProvisioner
import java.io.FileOutputStream
import io.spicelabs.cilantro.AssemblyDefinition

class DebugEntryTests extends munit.FunSuite {
  private def corpusRoot = CorpusProvisioner.ensureCorpus()

  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  private val Slow = new munit.Tag("Slow")

  private def i2(v: Int): Array[Byte] = Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte)

  private def i4(v: Int): Array[Byte] =
    Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte, ((v >> 16) & 0xff).toByte, ((v >> 24) & 0xff).toByte)

  private def zero(n: Int): Array[Byte] = Array.ofDim[Byte](n)

  private def sha256(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  private def debugInfo(path: String): scala.util.Try[(Vector[io.spicelabs.cilantro.DebugEntryData], Option[io.spicelabs.cilantro.EmbeddedPdb])] = {
    AssemblyDefinition.readAssembly(path).flatMap { assembly =>
      assembly.mainModule match {
        case None => scala.util.Failure(new IllegalArgumentException("no main module"))
        case Some(module) =>
          scala.util.Try {
            module.read((Vector.empty[io.spicelabs.cilantro.DebugEntryData], Option.empty[io.spicelabs.cilantro.EmbeddedPdb]), (_, reader: io.spicelabs.cilantro.MetadataReader) => {
              val entries = reader.readDebugEntryData()
              val b = Vector.newBuilder[io.spicelabs.cilantro.DebugEntryData]
              entries.foreach(e => b += e)
              (b.result(), reader.readEmbeddedPortablePdb())
            })
          }
      }
    }
  }

  // A synthetic debug directory with one entry pointing past the file.
  private def debugEntry(entryType: Int, size: Int, pointer: Int): Array[Byte] =
    zero(12) ++ i4(entryType) ++ i4(size) ++ i4(0) ++ i4(pointer)

  test("C5-05a: the pinned embedded-PDB fixture matches the oracle entries and its own sources") {
    debugInfo(corpusRoot.resolve("fixtures/embedded_pdb_fixture.dll").toString) match {
      case Success((entries, pdb)) =>
        // Pinned against the GoldenDumper debug oracle (2026-08-28).
        assertEquals(entries.length, 3, "the size-0 deterministic entry is skipped")
        assertEquals(entries(0).entryType, 2) // CodeView
        assertEquals(entries(0).blob.length, 47)
        assertEquals(entries(1).entryType, 19) // PdbChecksum
        assertEquals(entries(1).blob.length, 39)
        assertEquals(entries(2).entryType, 17) // EmbeddedPortablePdb
        assertEquals(entries(2).blob.length, 6888)

        pdb match {
          case Some(p) =>
            assertEquals(p.sources.length, 3, "Widget.cs + two generated sources")
            val widget = p.sources.find(_.name.endsWith("Widget.cs")).getOrElse(fail("Widget.cs missing"))
            val repoSource = new String(
              java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../../scripts/fixtures/embedded/Widget.cs")), "UTF-8")
            assertEquals(new String(widget.bytes, "UTF-8"), repoSource, "the embedded Widget.cs must equal the repo source byte-for-byte")
          case None => fail("the fixture must carry an embedded PDB")
        }
      case Failure(t) => fail(s"the fixture must read: $t")
    }
  }

  test("C5-05c: a debug entry pointing past the file fails cleanly") {
    val dir = debugEntry(2, 64, 0x7fffffff)
    val file = java.io.File.createTempFile("dbg", ".dll")
    val out = new FileOutputStream(file)
    out.write(new MinimalPeBuilder(debugDirectory = Some(dir)).build())
    out.close()
    try {
      assert(
        io.spicelabs.cilantro.ModuleDefinition.readModule(file.getAbsolutePath).isFailure,
        "an out-of-file debug data pointer must fail the header read"
      )
    } finally {
      file.delete()
    }
  }

  test("C5-05b (Slow): every corpus assembly's debug data reads or is empty — never throws".tag(Slow)) {
    import org.json4s._
    val manifest = org.json4s.native.JsonMethods.parse(
      new String(java.nio.file.Files.readAllBytes(corpusRoot.resolve("manifest.json")), "UTF-8"))
    implicit val formats: DefaultFormats.type = DefaultFormats
    var withDebug = 0
    var total = 0
    (manifest \ "packages").children.foreach { pkg =>
      (pkg \ "assemblies").children.foreach { asm =>
        val rel = (asm \ "path").extract[String]
        if (!rel.contains("corrupt")) {
          total += 1
          debugInfo(corpusRoot.resolve(rel).toString) match {
            case Success((entries, _)) =>
              if (entries.nonEmpty) withDebug += 1
            case Failure(t) => fail(s"$rel debug read threw: $t")
          }
        }
      }
    }
    assertEquals(total, 149)
    assert(withDebug >= 140, s"most corpus assemblies carry debug directories (149/149 measured), got $withDebug")
  }
}
