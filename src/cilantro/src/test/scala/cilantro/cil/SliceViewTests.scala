// SliceViewTests — CP-3a..d: zero-copy payload slice views.
//
// Why these tests exist:
//   Plan 2026_09_02 (phase B) replaces the whole-blob Array[Byte]
//   payload exposure (cert blobs, win32 leaves, debug blobs, managed
//   resources) with bounded, zero-copy slice views delivered through
//   PayloadSource.processStream (D-3/D-8). The tests pin:
//     CP-3a — a declared length inside the file delivers the exact
//             bytes, identically through every read pattern;
//     CP-3b — a declared length past EOF is a clean refusal
//             (DataFormatException) with no allocation;
//     CP-3c — the refusal precedes allocation even under a bounded
//             heap (forked child, -Xmx32m);
//     CP-3d — a > 256 MiB payload streams under a 32 MiB heap:
//             the zero-copy proof.
//
// Theory of the test:
//   - The slice boundary rules live in MappedSliceSource's factory
//     and BinaryStreamReader.payloadSlice; the suite tests them
//     directly (the reader is the public construction seam) and
//     through the real accessors over the pinned corpus.
//   - The forked child (cilantro.testutil.ForkedHelper) runs with
//     -Xmx32m: a regression that materialized a payload or allocated
//     from a hostile declared length would OOM the child, so the
//     parent's assertion on the child output is a behavioral proof,
//     not a heap-config-dependent tripwire.
//   - The large fixture is a sparse file (patterned writes at
//     intervals, holes read as zeros): sparseness is an optimization,
//     never an assertion — the expected hash is computed over the
//     real bytes.
//
// Requirements traced:
//   workspace/2026_09_01_cilantro_handoff.md §4 CP-3, CP-4 (plan
//   2026_09_02 phase B).
//
// LLM notes:
//   - The pinned hashes below are the same content pins the amended
//     C5-03a/C5-04a suites assert; duplicating them here pins the
//     slice path against the same corpus bytes independently.
//   - Declared lengths are 32-bit fields: 0x80000000 and 0xffffffff
//     are negative as signed Ints and refuse on the sign check;
//     0x7fffffff refuses on the extent check against a small file.

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import io.spicelabs.cilantro._
import io.spicelabs.cilantro.PE.BinaryStreamReader
import io.spicelabs.cilantro.metadata.CorpusProvisioner
import io.spicelabs.cilantro.testutil.ForkSupport
import java.io.{File, FileInputStream, FileOutputStream, RandomAccessFile}

class SliceViewTests extends munit.FunSuite {

  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  private val Slow = new munit.Tag("Slow")

  private def corpusRoot = CorpusProvisioner.ensureCorpus()

  private def i4(v: Int): Array[Byte] =
    Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte, ((v >> 16) & 0xff).toByte, ((v >> 24) & 0xff).toByte)

  private def zero(n: Int): Array[Byte] = Array.ofDim[Byte](n)

  private def sha256Bytes(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  // Stream the payload to a sha256 with a fixed chunk size.
  private def streamSha256(p: PayloadSource, chunk: Int): String =
    p.processStream { in =>
      val md = java.security.MessageDigest.getInstance("SHA-256")
      val buf = new Array[Byte](chunk)
      var n = in.read(buf)
      while (n >= 0) {
        if (n > 0) md.update(buf, 0, n)
        n = in.read(buf)
      }
      md.digest().map(b => f"${b & 0xff}%02x").mkString
    }

  // Byte-at-a-time read: a different read pattern over the same slice.
  private def singleByteSha256(p: PayloadSource): String =
    p.processStream { in =>
      val md = java.security.MessageDigest.getInstance("SHA-256")
      var b = in.read()
      while (b >= 0) {
        md.update(b.toByte)
        b = in.read()
      }
      md.digest().map(x => f"${x & 0xff}%02x").mkString
    }

  private def payloadBytes(p: PayloadSource): Array[Byte] =
    p.processStream(in => in.readAllBytes())

  private def readCertificates(path: String): scala.util.Try[Vector[CertificateEntry]] = {
    ModuleDefinition.readModule(path).flatMap { module =>
      scala.util.Try {
        module.read(Vector.empty[CertificateEntry], (_, reader: MetadataReader) => {
          val b = Vector.newBuilder[CertificateEntry]
          reader.readCertificateEntries().foreach(e => b += e)
          b.result()
        })
      }
    }
  }

  private def readResources(path: String): scala.util.Try[Vector[Win32Resource]] = {
    ModuleDefinition.readModule(path).flatMap { module =>
      scala.util.Try {
        module.read(Vector.empty[Win32Resource], (_, reader: MetadataReader) => {
          val b = Vector.newBuilder[Win32Resource]
          reader.readWin32Resources().foreach(r => b += r)
          b.result()
        })
      }
    }
  }

  private def readDebugData(path: String): scala.util.Try[Vector[DebugEntryData]] = {
    ModuleDefinition.readModule(path).flatMap { module =>
      scala.util.Try {
        module.read(Vector.empty[DebugEntryData], (_, reader: MetadataReader) => {
          val b = Vector.newBuilder[DebugEntryData]
          reader.readDebugEntryData().foreach(e => b += e)
          b.result()
        })
      }
    }
  }

  test("CP-3a: in-file declared lengths deliver the exact bytes through every read pattern") {
    val newtonsoft = corpusRoot.resolve("bin/Newtonsoft.Json/12.0.3/net20/Newtonsoft.Json.dll").toString

    // Certificate payload: the pinned blob sha256 (0f8f...).
    readCertificates(newtonsoft) match {
      case Success(entries) =>
        assertEquals(entries.length, 1)
        val sha = "0f8fc2643529f9d670bcccc427a83cf29999e94c6f4d568676f8bfc0329dac9c"
        assertEquals(streamSha256(entries(0), 65536), sha, "bulk reads hash the pinned cert bytes")
        assertEquals(streamSha256(entries(0), 7), sha, "7-byte chunk reads hash the same bytes")
        assertEquals(singleByteSha256(entries(0)), sha, "byte-at-a-time reads hash the same bytes")
        assertEquals(payloadBytes(entries(0)).length, 8096, "the blob is dwLength - 8")
      case Failure(t) => fail(s"certificate slice read failed: $t")
    }

    // Win32 leaf: the pinned RT_VERSION sha256 (5bd2...).
    readResources(newtonsoft) match {
      case Success(leaves) =>
        assertEquals(leaves.length, 1)
        val sha = "5bd2a0f900755c12a17b123b78a6280d22f4cdaad481f44c490661132f928fcc"
        assertEquals(streamSha256(leaves(0), 65536), sha, "bulk reads hash the pinned leaf bytes")
        assertEquals(streamSha256(leaves(0), 1), sha, "byte-at-a-time reads hash the same bytes")
        assertEquals(singleByteSha256(leaves(0)), sha, "the second pass is byte-identical")
        assertEquals(payloadBytes(leaves(0)).length, 1110)
      case Failure(t) => fail(s"win32 slice read failed: $t")
    }

    // Debug blobs: the pinned fixture's type-17 MPDB blob (6888 bytes).
    val pdbFixture = corpusRoot.resolve("fixtures/embedded_pdb_fixture.dll").toString
    readDebugData(pdbFixture) match {
      case Success(entries) =>
        assertEquals(entries.length, 3)
        val mpdb = entries.find(_.entryType == 17).getOrElse(fail("type-17 entry missing"))
        assertEquals(payloadBytes(mpdb).length, 6888, "the raw MPDB blob is intact")
        assertEquals(streamSha256(mpdb, 4096), streamSha256(mpdb, 3), "chunk size does not change the bytes")
      case Failure(t) => fail(s"debug slice read failed: $t")
    }

    // Managed resources: the slice path equals the pinned
    // resourceData path byte-for-byte on the resources fixture.
    val resFixture = corpusRoot.resolve("fixtures/resources_fixture.dll").toString
    AssemblyDefinition.readAssembly(resFixture) match {
      case Success(assembly) =>
        assembly.mainModule match {
          case Some(module) =>
            val embedded = module.resources.collect { case r: EmbeddedResource => r }
            assert(embedded.nonEmpty, "the fixture carries embedded resources")
            embedded.foreach { r =>
              val viaData = r.resourceData()
              r.resourceOffset match {
                case Some(offset) =>
                  module.read(Vector.empty[Array[Byte]], (_, reader: MetadataReader) => {
                    val slice = reader.managedResourcePayload(offset)
                    val viaSlice = payloadBytes(slice)
                    viaData match {
                      case Success(expected) =>
                        assertEquals(viaSlice.toSeq, expected.toSeq, "the slice and resourceData agree byte-for-byte")
                        assertEquals(sha256Bytes(viaSlice), sha256Bytes(expected))
                      case Failure(e) => fail(s"resourceData failed: $e")
                    }
                  })
                case None => fail("offset-backed resources must expose their offset")
              }
            }
          case None => fail("no main module")
        }
      case Failure(t) => fail(s"the resources fixture must read: $t")
    }
  }

  test("CP-3b: declared lengths past EOF refuse cleanly, and the boundary pins hold") {
    val newtonsoft = corpusRoot.resolve("bin/Newtonsoft.Json/12.0.3/net20/Newtonsoft.Json.dll").toString
    val in = new FileInputStream(new File(newtonsoft))
    try {
      val reader = BinaryStreamReader(in)
      val fileSize = reader.length
      assertEquals(fileSize > 1000, true, "the pinned file is not tiny")

      // Exact boundary: the whole file is one valid slice.
      val whole = reader.payloadSlice(fileSize)
      assertEquals(payloadBytes(whole).length, fileSize)

      // One past the end refuses; the boundary itself accepts.
      val reader2 = BinaryStreamReader(in)
      reader2.moveTo(fileSize - 1)
      reader2.payloadSlice(1) // exact extent: OK
      val reader3 = BinaryStreamReader(in)
      reader3.moveTo(fileSize - 1)
      val r3 = scala.util.Try(reader3.payloadSlice(2))
      assert(r3.isFailure, "a declared length one past EOF must refuse")

      // Format-width boundary pins: lengths are signed 32-bit fields.
      val reader4 = BinaryStreamReader(in)
      reader4.moveTo(0)
      assert(scala.util.Try(reader4.payloadSlice(0x7fffffff)).isFailure, "2^31-1 past a small file must refuse")
      val reader5 = BinaryStreamReader(in)
      reader5.moveTo(0)
      assert(scala.util.Try(reader5.payloadSlice(0x80000000)).isFailure, "0x80000000 (negative) must refuse")
      val reader6 = BinaryStreamReader(in)
      reader6.moveTo(0)
      assert(scala.util.Try(reader6.payloadSlice(0xffffffff)).isFailure, "0xffffffff (negative) must refuse")
    } finally {
      in.close()
    }
  }

  test("CP-3c: refusals precede allocation — bounded-heap child over hostile certificate claims") {
    // A dwLength of 0xffffffff on a tiny builder file: readCertificates
    // must refuse. Under -Xmx32m any hostile-driven allocation would
    // OOM the child.
    val table = i4(0xffffffff) ++ i4(0x0200) ++ i4(2)
    val file = File.createTempFile("slicecert", ".dll")
    try {
      val out = new FileOutputStream(file)
      out.write(new MinimalPeBuilder(certificateTable = Some(table)).build())
      out.close()
      val (code, outText) = ForkSupport.runForked(List("certrefuse", file.getAbsolutePath))
      assertEquals(code, 0, s"the child must exit cleanly; output: $outText")
      assert(outText.contains("REFUSED"), s"the hostile dwLength must refuse under a 32 MiB heap: $outText")

      // In-process the same refusal is a Try Failure (DataFormatException).
      readCertificates(file.getAbsolutePath) match {
        case Success(_) => fail("a 0xffffffff dwLength must refuse")
        case Failure(t) =>
          assertEquals(
            t.getClass.getSimpleName,
            "DataFormatException",
            s"the refusal type is DataFormatException, got $t"
          )
      }
    } finally {
      file.delete()
    }
  }

  test("CP-3d (CP-4): a 256 MiB+ payload streams under a 32 MiB heap — the zero-copy proof") {
    // Build a win32 tree with one leaf claiming a ~272 MiB region,
    // sparse-extend the file, write pattern chunks into the region at
    // intervals, and stream-hash the leaf in a -Xmx32m child. The
    // parent compares against a hash it computed over the real bytes.
    // A whole-payload materialization would OOM the child.
    val root = zero(12) ++ Array[Byte](0, 0, 1, 0) ++ i4(10) ++ i4(0x80000000 | (16 + 8))
    val nameDir = zero(12) ++ Array[Byte](0, 0, 1, 0) ++ i4(1) ++ i4(0x80000000 | (16 + 8 + 16 + 8))
    val langDir = zero(12) ++ Array[Byte](0, 0, 1, 0) ++ i4(0x409) ++ i4(16 + 8 + 16 + 8 + 16 + 8)
    val dirRva = 0x2500
    val claimSize = 0x11000000 // 272 MiB
    // The blob sits AFTER the data entry (tree-relative 88), so the
    // pattern writes never touch the tree itself.
    val blobOff = 16 + 8 + 16 + 8 + 16 + 8 + 16
    val data = i4(dirRva + blobOff) ++ i4(claimSize) ++ zero(8)
    val treeBytes = root ++ nameDir ++ langDir ++ data
    val file = File.createTempFile("slicebig", ".dll")
    try {
      val out = new FileOutputStream(file)
      out.write(new MinimalPeBuilder(win32ResourceTree = Some(treeBytes)).build())
      out.close()
      // treeBaseRaw = builder bodyOffset (0x160) + (dirRva - sectionRva 0x2000)
      val treeBaseRaw = 0x160 + (dirRva - 0x2000)
      val blobRaw = treeBaseRaw + blobOff
      val raf = new RandomAccessFile(file, "rw")
      try {
        raf.setLength(blobRaw.toLong + claimSize.toLong + 1L)
      } finally {
        raf.close()
      }
      // Pattern chunks: 64 KiB of a deterministic pattern every 4 MiB.
      val chunk = new Array[Byte](65536)
      var i = 0
      while (i < chunk.length) {
        chunk(i) = ((i * 31 + 7) & 0xff).toByte
        i += 1
      }
      val raf2 = new RandomAccessFile(file, "rw")
      try {
        var at = 0L
        var wrote = 0L
        while (at < claimSize.toLong) {
          raf2.seek(blobRaw.toLong + at)
          raf2.write(chunk)
          wrote += 1
          at += 4L * 1024 * 1024
        }
        assert(wrote >= 64, s"the fixture must carry pattern chunks, wrote $wrote")
      } finally {
        raf2.close()
      }

      // Expected hash: stream the region the same way the child does.
      val expected = {
        val ch = new FileInputStream(file).getChannel
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = java.nio.ByteBuffer.allocate(65536)
        var remaining = claimSize.toLong
        var pos = blobRaw.toLong
        while (remaining > 0) {
          buf.clear()
          buf.limit(Math.min(buf.capacity.toLong, remaining).toInt)
          val n = ch.read(buf, pos)
          if (n <= 0) sys.error("sparse read failed")
          md.update(buf.array(), 0, n)
          pos += n
          remaining -= n
        }
        md.digest().map(b => f"${b & 0xff}%02x").mkString
      }

      // Verify the leaf enumerates in-process first (extent-only).
      readResources(file.getAbsolutePath) match {
        case Success(leaves) =>
          assertEquals(leaves.length, 1)
          leaves(0).processStream { in =>
            assertEquals(in.available(), claimSize, "the slice covers the full 272 MiB")
          }
        case Failure(t) => fail(s"the big-leaf tree must read: $t")
      }

      val (code, outText) = ForkSupport.runForked(List("win32hash", file.getAbsolutePath, "0"))
      assertEquals(code, 0, s"the child must exit cleanly; output: $outText")
      val printed = outText.trim
      assertEquals(
        printed.startsWith(expected + ":"),
        true,
        s"the 272 MiB payload must hash identically under a 32 MiB heap; got: $printed"
      )
    } finally {
      file.delete()
    }
  }
}
