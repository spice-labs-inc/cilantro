// Win32ResourceTests — C5-04: the PE resource directory tree.
//
// Why these tests exist:
//   Plan 13 makes the .rsrc tree readable: Goat Rodeo emits every
//   resource leaf as an artifact, and RT_RCDATA / RT_VERSION blobs are
//   exactly the kind of bytes a crypto hunt must see. The tree is a
//   classic parsing attack surface, so the walker is capped and
//   bounds-checked; these tests pin the real layout (including the
//   linker's .rsrc quirk: directory targets tree-relative, data offsets
//   dirRva-relative), the pinned blob constants, and the bombs.
//
// Theory of the test:
//   - Pinned constants: the corpus is sha256-pinned; the net20
//     Newtonsoft.Json version resource is stable input (RT_VERSION,
//     name 1, language 0, 1110 bytes, sha256 below — computed once from
//     the pinned file, deliberately, 2026-08-28).
//   - Bombs: synthetic trees (MinimalPeBuilder) with an oversized entry
//     count and an out-of-file data offset must fail cleanly.
//
// Requirements traced:
//   13_dll_container_traversal.md C5-04 (a-d).
//
// LLM notes:
//   - Directory = 16-byte header (Characteristics, TimeDateStamp,
//     Major/MinorVersion, NumberOfNamedEntries, NumberOfIdEntries) +
//     8-byte entries (Name or 0x80000000|nameOffset, target; the target
//     high bit marks a subdirectory).
//   - Data entry = OffsetToData, Size, CodePage, Reserved (16 bytes).
//   - The tree offsets are NOT absolute RVAs (see the file comment in
//     AssemblyReader.readWin32Resources).

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import java.io.FileOutputStream

class Win32ResourceTests extends munit.FunSuite {

  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  private val Slow = new munit.Tag("Slow")

  private def i2(v: Int): Array[Byte] = Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte)

  private def i4(v: Int): Array[Byte] =
    Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte, ((v >> 16) & 0xff).toByte, ((v >> 24) & 0xff).toByte)

  private def zero(n: Int): Array[Byte] = Array.ofDim[Byte](n)

  private def readResources(path: String): scala.util.Try[Vector[io.spicelabs.cilantro.Win32Resource]] = {
    io.spicelabs.cilantro.ModuleDefinition.readModule(path).flatMap { module =>
      scala.util.Try {
        module.read(Vector.empty[io.spicelabs.cilantro.Win32Resource], (_, reader: io.spicelabs.cilantro.MetadataReader) => {
          val got = reader.readWin32Resources()
          val b = Vector.newBuilder[io.spicelabs.cilantro.Win32Resource]
          got.foreach(r => b += r)
          b.result()
        })
      }
    }
  }

  private def withPe(tree: Array[Byte])(body: String => Unit): Unit = {
    val file = java.io.File.createTempFile("rsrc", ".dll")
    val out = new FileOutputStream(file)
    out.write(new MinimalPeBuilder(win32ResourceTree = Some(tree)).build())
    out.close()
    try {
      body(file.getAbsolutePath)
    } finally {
      file.delete()
    }
  }

  private def sha256(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  // A minimal tree: root (RT_RCDATA) -> name level (id 1) -> language
  // level (0x409) -> data entry -> blob. Offsets: directory targets
  // tree-relative, data offsets dirRva-relative (dirRva = 0x2500).
  private def rcdTree(blob: Array[Byte]): Array[Byte] = {
    // layout: root(16 + 8) | name dir(16 + 8) | lang dir(16 + 8) | data entry(16) | blob
    val root = 0
    val nameDir = 16 + 8
    val langDir = nameDir + 16 + 8
    val dataEntry = langDir + 16 + 8
    val blobOff = dataEntry + 16
    val dirRva = 0x2500
    def dirHeader(named: Int, ids: Int): Array[Byte] =
      zero(12) ++ i2(named) ++ i2(ids)
    val rootBytes = dirHeader(0, 1) ++ i4(10) ++ i4(0x80000000 | nameDir)
    val nameBytes = dirHeader(0, 1) ++ i4(1) ++ i4(0x80000000 | langDir)
    val langBytes = dirHeader(0, 1) ++ i4(0x409) ++ i4(dataEntry)
    val dataBytes = i4(dirRva + blobOff) ++ i4(blob.length) ++ zero(8)
    rootBytes ++ nameBytes ++ langBytes ++ dataBytes ++ blob
  }

  test("C5-04a: the pinned assembly's version resource matches pinned constants") {
    readResources("../../corpus/bin/Newtonsoft.Json/12.0.3/net20/Newtonsoft.Json.dll") match {
      case Success(resources) =>
        assertEquals(resources.length, 1, "the net20 assembly has one resource leaf")
        val r = resources(0)
        assertEquals(r.typeNameOrId, "RT_VERSION")
        assertEquals(r.nameId, 1)
        assertEquals(r.language, 0)
        assertEquals(r.blob.length, 1110)
        assertEquals(
          sha256(r.blob),
          "5bd2a0f900755c12a17b123b78a6280d22f4cdaad481f44c490661132f928fcc",
          "the pinned RT_VERSION blob sha256"
        )
      case Failure(t) => fail(s"resource walk failed: $t")
    }
  }

  test("C5-04b: an RT_RCDATA resource round-trips its bytes") {
    val blob = "crypto hunting seed bytes".getBytes("UTF-8")
    withPe(rcdTree(blob)) { path =>
      readResources(path) match {
        case Success(resources) =>
          assertEquals(resources.length, 1)
          val r = resources(0)
          assertEquals(r.typeNameOrId, "RT_RCDATA")
          assertEquals(r.nameId, 1)
          assertEquals(r.language, 0x409)
          assertEquals(r.blob.toSeq, blob.toSeq, "the RCDATA blob must round-trip")
        case Failure(t) => fail(s"synthetic tree must read: $t")
      }
    }
  }

  test("C5-04c: an oversized directory entry count fails cleanly") {
    val bomb = zero(12) ++ i2(0xffff) ++ i2(0xffff) ++ zero(8)
    withPe(bomb) { path =>
      readResources(path) match {
        case Success(_) => fail("131070 entries must exceed the per-directory cap")
        case Failure(_) => ()
      }
    }
  }

  test("C5-04c: a data offset beyond the file fails cleanly") {
    val tree = {
      val dataEntry = 16 + 8 + 16 + 8 + 16 + 8
      val rootBytes = zero(12) ++ i2(0) ++ i2(1) ++ i4(10) ++ i4(0x80000000 | (16 + 8))
      val nameBytes = zero(12) ++ i2(0) ++ i2(1) ++ i4(1) ++ i4(dataEntry)
      val dataBytes = i4(0x7fffffff) ++ i4(4) ++ zero(8)
      rootBytes ++ nameBytes ++ dataBytes
    }
    withPe(tree) { path =>
      readResources(path) match {
        case Success(_) => fail("an out-of-file data offset must fail")
        case Failure(_) => ()
      }
    }
  }

  test("C5-04d (Slow): every corpus assembly's resource tree reads — never throws".tag(Slow)) {
    import org.json4s._
    val manifest = org.json4s.native.JsonMethods.parse(
      new String(java.nio.file.Files.readAllBytes(io.spicelabs.cilantro.metadata.CorpusHelpers.corpusRoot.resolve("manifest.json")), "UTF-8"))
    implicit val formats: DefaultFormats.type = DefaultFormats
    var withResources = 0
    var total = 0
    (manifest \ "packages").children.foreach { pkg =>
      (pkg \ "assemblies").children.foreach { asm =>
        val rel = (asm \ "path").extract[String]
        if (!rel.contains("corrupt")) {
          total += 1
          readResources("../../corpus/" + rel) match {
            case Success(resources) =>
              if (resources.nonEmpty) withResources += 1
            case Failure(t) => fail(s"$rel resource walk threw: $t")
          }
        }
      }
    }
    assertEquals(total, 149)
    assert(withResources >= 140, s"nearly every corpus assembly carries resources (149/149 measured), got $withResources")
  }
}
