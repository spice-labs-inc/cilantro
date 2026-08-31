// CertificateTests — C5-03: Authenticode certificates.
//
// Why these tests exist:
//   Plan 13 makes the PE Security data directory readable: Goat Rodeo
//   will emit each WIN_CERTIFICATE blob as an artifact for the
//   certificate strategies. The reader is new ground (Cecil has no
//   counterpart), so these tests pin it with deliberate constants, the
//   overlay layout, hostile-input behavior, and the unsigned case.
//
// Theory of the test:
//   - Pinned constants: the corpus is sha256-pinned, so a signed
//     assembly's certificate table is stable input; the test pins the
//     entry count, revision/type, and the blob sha256 (computed once,
//     deliberately, like a golden).
//   - Overlay: both pinned signed assemblies store the table beyond the
//     sections (VA == raw file offset); the pinned read exercises that
//     path.
//   - Bombs: hostile dwLength/counts fail cleanly with a wrapped
//     DataFormatException before any allocation or past-EOF read.
//
// Requirements traced:
//   13_dll_container_traversal.md C5-03 (a-e).
//
// LLM notes:
//   - WIN_CERTIFICATE: dwLength(4), wRevision(2), wCertificateType(2),
//     blob(dwLength-8), entries 8-byte aligned from the table start.
//   - PKCS_SIGNED_DATA certificate type = 2; the signature revision is
//     0x0200 (512).
//   - The SHA-256 constant below was computed from the pinned corpus
//     file (corpus/bin/Newtonsoft.Json/12.0.3/net20/Newtonsoft.Json.dll)
//     on 2026-08-28; the corpus manifest pins that file's bytes.

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import java.io.FileOutputStream
import io.spicelabs.cilantro.AssemblyDefinition
import io.spicelabs.cilantro.metadata.{CorpusHelpers, CorpusProvisioner}

class CertificateTests extends munit.FunSuite {
  private def corpusRoot = CorpusProvisioner.ensureCorpus()

  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  private val Slow = new munit.Tag("Slow")

  private def i2(v: Int): Array[Byte] = Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte)

  private def i4(v: Int): Array[Byte] =
    Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte, ((v >> 16) & 0xff).toByte, ((v >> 24) & 0xff).toByte)

  private def zero(n: Int): Array[Byte] = Array.ofDim[Byte](n)

  // A WIN_CERTIFICATE entry: dwLength, wRevision, wCertificateType, blob.
  private def winCert(length: Int, revision: Int, certType: Int): Array[Byte] =
    i4(length) ++ i2(revision) ++ i2(certType) ++ zero(length - 8)

  private def readEntries(path: String): scala.util.Try[Vector[io.spicelabs.cilantro.CertificateEntry]] = {
    AssemblyDefinition.readAssembly(path).flatMap { assembly =>
      assembly.mainModule match {
        case None => scala.util.Failure(new IllegalArgumentException("no main module"))
        case Some(module) =>
          scala.util.Try {
            module.read(Vector.empty[io.spicelabs.cilantro.CertificateEntry], (_, reader: io.spicelabs.cilantro.MetadataReader) => {
              val got = reader.readCertificateEntries()
              val collected = Vector.newBuilder[io.spicelabs.cilantro.CertificateEntry]
              got.foreach(e => collected += e)
              collected.result()
            })
          }
      }
    }
  }

  private def withPe(table: Array[Byte])(body: String => Unit): Unit = {
    val file = java.io.File.createTempFile("certs", ".dll")
    val out = new FileOutputStream(file)
    out.write(new MinimalPeBuilder(certificateTable = Some(table)).build())
    out.close()
    try {
      body(file.getAbsolutePath)
    } finally {
      file.delete()
    }
  }

  private def sha256(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  test("C5-03a: the pinned signed assembly's certificate table matches pinned constants") {
    readEntries(corpusRoot.resolve("bin/Newtonsoft.Json/12.0.3/net20/Newtonsoft.Json.dll").toString) match {
      case Success(entries) =>
        assertEquals(entries.length, 1, "Newtonsoft.Json net20 has one Authenticode signature")
        assertEquals(entries(0).revision, 0x0200, "the signature revision is WIN_CERT_REVISION_2_0")
        assertEquals(entries(0).certificateType, 2, "PKCS_SIGNED_DATA")
        assertEquals(
          sha256(entries(0).blob),
          "0f8fc2643529f9d670bcccc427a83cf29999e94c6f4d568676f8bfc0329dac9c",
          "the pinned signature blob sha256 (overlay table, VA == file offset)"
        )
      case Failure(t) => fail(s"the signed assembly must read: $t")
    }
  }

  test("C5-03c: a hostile dwLength fails cleanly before reads") {
    // dwLength = 0xFFFFFFFF: the reader must reject it on the length
    // check before attempting any blob allocation.
    withPe(i4(0xffffffff) ++ i2(0x0200) ++ i2(2)) { path =>
      readEntries(path) match {
        case Success(_) => fail("an oversized dwLength must fail")
        case Failure(_) => ()
      }
    }
  }

  test("C5-03c: an entry longer than the directory fails cleanly") {
    withPe(i4(0x1000) ++ zero(4)) { path =>
      readEntries(path) match {
        case Success(_) => fail("a dwLength beyond the directory must fail")
        case Failure(_) => ()
      }
    }
  }

  test("C5-03c: an entry-count bomb fails cleanly") {
    val table = (1 to 1025).flatMap(_ => winCert(8, 0x0200, 2)).toArray
    withPe(table) { path =>
      readEntries(path) match {
        case Success(_) => fail("1025 entries must exceed the count cap")
        case Failure(_) => ()
      }
    }
  }

  test("C5-03d: an unsigned assembly yields an empty result cleanly") {
    readEntries(corpusRoot.resolve("fixtures/ilasm_fixture.dll").toString) match {
      case Success(entries) => assertEquals(entries.length, 0, "the ilasm fixture is unsigned")
      case Failure(t) => fail(s"an unsigned assembly must read cleanly: $t")
    }
  }

  test("C5-03e (Slow): every corpus assembly's certificate table reads or is empty — never throws".tag(Slow)) {
    import org.json4s._
    val manifest = org.json4s.native.JsonMethods.parse(
      new String(java.nio.file.Files.readAllBytes(corpusRoot.resolve("manifest.json")), "UTF-8"))
    implicit val formats: DefaultFormats.type = DefaultFormats
    var signed = 0
    var total = 0
    (manifest \ "packages").children.foreach { pkg =>
      (pkg \ "assemblies").children.foreach { asm =>
        val rel = (asm \ "path").extract[String]
        if (!rel.contains("corrupt")) {
          total += 1
          readEntries(corpusRoot.resolve(rel).toString) match {
            case Success(entries) =>
              if (entries.nonEmpty) signed += 1
            case Failure(t) => fail(s"$rel certificate read threw: $t")
          }
        }
      }
    }
    assertEquals(total, 149, "the corpus assembly count")
    assert(signed >= 80, s"most corpus assemblies are signed (measured 87), got $signed")
  }
}
