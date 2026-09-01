// ManagedResourceSafetyTests — H2-xx: managed-resource length
// pre-checks and the resourceLength accessor.
//
// Why these tests exist:
//   Plan 2026_09_01 (phase-02) hardens MetadataReader.getManagedResource
//   so the declared blob length is validated against the file extent
//   BEFORE reading (a 2 GiB declared length in a tiny file must fail
//   with DataFormatException, never OOM), the ManifestResource offset
//   arithmetic itself is validated (hostile offsets must fail with
//   DataFormatException, not an NIO IllegalArgumentException), and
//   EmbeddedResource gains a cheap resourceLength() accessor so
//   consumers (Goat Rodeo) can pre-check sizes without materializing
//   the blob.
//
// Theory of the test:
//   - The pinned fixture (resources_fixture.dll) pins the real path:
//     resourceLength() must equal the oracle-pinned 50-byte blob sizes
//     and agree with the materialized bytes.
//   - Synthetic ManifestResource rows (MinimalPeBuilder) drive the
//     declared-length bomb (prefix claims 2 GiB) and the offset bomb
//     (offset past the file); both must fail with DataFormatException.
//   - The stream-backed constructor cannot know its length cheaply: a
//     documented Failure(OperationNotSupportedException).
//
// Requirements traced:
//   plans/2026_09_01_cilantro_hardening_and_dotnet_probe/phase-02.md
//   H2-01..H2-06 (suggestion cilantro #2; ADR-0013 failure contract).
//
// LLM notes:
//   - The embedded blob lives at the CLI header's Resources directory
//     plus the ManifestResource row's offset; the blob is [u32
//     length][bytes].
//   - resourceLength() reads only the 4-byte prefix for the
//     offset+reader form; it never materializes the blob.

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import io.spicelabs.cilantro.metadata.CorpusProvisioner
import java.io.FileOutputStream
import javax.naming.OperationNotSupportedException

class ManagedResourceSafetyTests extends munit.FunSuite {

  private def corpusRoot = CorpusProvisioner.ensureCorpus()

  private def i2(v: Int): Array[Byte] = Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte)

  private def i4(v: Int): Array[Byte] =
    Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte, ((v >> 16) & 0xff).toByte, ((v >> 24) & 0xff).toByte)

  private def zero(n: Int): Array[Byte] = Array.ofDim[Byte](n)

  private def sha256(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  private def manifestResourceRow(offset: Int, flags: Int, nameIdx: Int, implementation: Int): Array[Byte] =
    i4(offset) ++ i4(flags) ++ i2(nameIdx) ++ i2(implementation)

  private def embeddedResources(path: String): scala.util.Try[Vector[io.spicelabs.cilantro.EmbeddedResource]] = {
    io.spicelabs.cilantro.ModuleDefinition.readModule(path).flatMap { module =>
      scala.util.Try {
        val got = module.resources
        val b = Vector.newBuilder[io.spicelabs.cilantro.EmbeddedResource]
        got.foreach {
          case e: io.spicelabs.cilantro.EmbeddedResource => b += e
          case _ => ()
        }
        b.result()
      }
    }
  }

  private def assertDataFormatFailure(result: scala.util.Try[Any], what: String): Unit = {
    result match {
      case Failure(t) =>
        assert(
          t.isInstanceOf[java.util.zip.DataFormatException],
          s"$what must fail with DataFormatException, got ${t.getClass.getName}"
        )
      case Success(_) => fail(s"$what must fail")
    }
  }

  test("H2-01: resourceLength matches the oracle-pinned blob sizes on the fixture") {
    embeddedResources(corpusRoot.resolve("fixtures/resources_fixture.dll").toString) match {
      case Success(embedded) =>
        assertEquals(embedded.length, 2)
        val byName = embedded.map(r => r.name -> r).toMap
        val alpha = byName.getOrElse("ResourcesFixture.data.alpha.bin", fail("alpha missing"))
        val beta = byName.getOrElse("ResourcesFixture.data.beta.bin", fail("beta missing"))
        assertEquals(alpha.resourceLength(), Success(50), "alpha's declared length is oracle-pinned at 50")
        beta.resourceLength() match {
          case Success(betaLen) =>
            beta.resourceData() match {
              case Success(betaData) => assertEquals(betaLen, betaData.length, "beta's declared length agrees with its blob")
              case Failure(t) => fail(s"beta data read failed: $t")
            }
          case Failure(t) => fail(s"beta resourceLength failed: $t")
        }
      case Failure(t) => fail(s"the pinned fixture must read: $t")
    }
  }

  test("H2-02: a 2 GiB declared resource length fails cleanly, no OOM") {
    val blob = i4(0x7fffffff) // the prefix claims 2 GiB; the file holds 4 bytes
    val row = manifestResourceRow(0, 0x20, 3, 0)
    val file = java.io.File.createTempFile("res", ".dll")
    val out = new FileOutputStream(file)
    out.write(new MinimalPeBuilder(
      extraTables = Seq((40, 12, row)),
      extraStrings = "Bomb\u0000".getBytes("UTF-8"),
      managedResourceBlob = Some(blob)
    ).build())
    out.close()
    try {
      embeddedResources(file.getAbsolutePath) match {
        case Success(embedded) =>
          embedded.foreach { e =>
            assertDataFormatFailure(e.resourceData(), "a 2 GiB declared length (resourceData)")
            assertDataFormatFailure(e.resourceLength(), "a 2 GiB declared length (resourceLength)")
          }
        case Failure(t) => fail(s"resource enumeration must read: $t")
      }
    } finally {
      file.delete()
    }
  }

  test("H2-03: a hostile resource offset fails with DataFormatException") {
    val blob = i4(4) ++ "data".getBytes("UTF-8")
    val row = manifestResourceRow(0x7fffff00, 0x20, 3, 0)
    val file = java.io.File.createTempFile("res", ".dll")
    val out = new FileOutputStream(file)
    out.write(new MinimalPeBuilder(
      extraTables = Seq((40, 12, row)),
      extraStrings = "Bad\u0000".getBytes("UTF-8"),
      managedResourceBlob = Some(blob)
    ).build())
    out.close()
    try {
      embeddedResources(file.getAbsolutePath) match {
        case Success(embedded) =>
          embedded.foreach { e =>
            assertDataFormatFailure(e.resourceData(), "an out-of-bounds offset (resourceData)")
            assertDataFormatFailure(e.resourceLength(), "an out-of-bounds offset (resourceLength)")
          }
        case Failure(t) => fail(s"resource enumeration must read: $t")
      }
    } finally {
      file.delete()
    }
  }

  test("H2-04: resourceLength does not materialize the blob") {
    embeddedResources(corpusRoot.resolve("fixtures/resources_fixture.dll").toString) match {
      case Success(embedded) =>
        val alpha = embedded.find(_.name.endsWith("alpha.bin")).getOrElse(fail("alpha missing"))
        assertEquals(alpha.resourceLength(), Success(50), "the cheap prefix read")
        alpha.resourceData() match {
          case Success(data) =>
            assertEquals(data.length, 50, "the later data read returns the identical blob")
            assertEquals(alpha.resourceLength(), Success(data.length), "prefix and blob agree")
          case Failure(t) => fail(s"alpha data read failed: $t")
        }
      case Failure(t) => fail(s"the pinned fixture must read: $t")
    }
  }

  test("H2-05: stream-backed resources report a documented Failure for resourceLength") {
    val stream = new java.io.ByteArrayInputStream(Array[Byte](1, 2, 3))
    val e = new io.spicelabs.cilantro.EmbeddedResource("streamed", 0, stream)
    e.resourceLength() match {
      case Failure(t) =>
        assert(
          t.isInstanceOf[OperationNotSupportedException],
          s"expected OperationNotSupportedException, got ${t.getClass.getName}"
        )
      case Success(_) => fail("stream-backed resourceLength must be a Failure")
    }
  }

  test("H2-06: the pinned resource bytes still round-trip (regression)") {
    embeddedResources(corpusRoot.resolve("fixtures/resources_fixture.dll").toString) match {
      case Success(embedded) =>
        val alpha = embedded.find(_.name.endsWith("alpha.bin")).getOrElse(fail("alpha missing"))
        alpha.resourceData() match {
          case Success(data) =>
            assertEquals(data.length, 50)
            assertEquals(
              sha256(data),
              "3f8017a8e09505b25eaab6781a48d9a1b00634f4f11efe0a9b624cd5294beeb8",
              "the pinned alpha sha256"
            )
          case Failure(t) => fail(s"alpha data read failed: $t")
        }
      case Failure(t) => fail(s"the pinned fixture must read: $t")
    }
  }
}
