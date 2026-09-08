// PortablePdbFileTests — reading a STANDALONE portable PDB file,
// BSJB or MPDB envelope, through the callback-owned reader
// (plans 2026-09-04; ADR-0014 amendment B-8).
//
// Why these tests exist:
//   Goat Rodeo receives PDB files that arrive outside any DLL. A
//   portable PDB is a file whose bytes ARE the BSJB metadata root;
//   it may also arrive wrapped in an MPDB envelope (compressed). The
//   reader is callback-owned and envelope-detecting:
//   PortablePdbFile.withPdb(file, spoolDir)(f) — f ALWAYS runs with
//   the outcome; the view is live only inside f; cleanup (map
//   release, scratch deletion) is automatic; the caller's file and
//   spool directory are never touched.
//
// Theory of the test:
//   - A1: byte equivalence — the standalone view's sources are
//     byte-for-byte the embedded path's sources over the same
//     content (fixture derived from the pinned embedded PDB).
//   - A2: the probe and the gate — BSJB and MPDB files classify;
//     PE/MSF/junk/empty/truncated are None, never throw; a missing
//     file is a clean outer Failure.
//   - A3: caller-file hygiene — the file survives; a BSJB read needs
//     no spool; retained views refuse after the call; f exceptions
//     propagate with cleanup.
//   - A4: hostile inputs — hostile version lengths refuse; MPDB
//     envelopes need a spoolDir (Failure without it), decompress
//     with it, and a >= 2^31 declaration refuses BEFORE any write;
//     seeded flips never escape.
//
// Requirements traced:
//   Goat Rodeo issue exchange 2026-09-04; ADR-0014 amendment.

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import io.spicelabs.cilantro._
import io.spicelabs.cilantro.PE.RawInflate
import io.spicelabs.cilantro.metadata.CorpusProvisioner
import java.io.File
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class PortablePdbFileTests extends munit.FunSuite {

  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  private def corpusRoot = CorpusProvisioner.ensureCorpus()

  private def fixturePath: String =
    corpusRoot.resolve("fixtures/embedded_pdb_fixture.dll").toString

  private def payloadBytes(p: PayloadSource): Array[Byte] =
    p.processStream(in => in.readAllBytes())

  private def deleteRecursively(dir: Path): Unit = {
    if (Files.isDirectory(dir)) {
      val stream = Files.list(dir)
      try {
        stream.forEach(p => deleteRecursively(p))
      } finally {
        stream.close()
      }
    }
    try {
      Files.deleteIfExists(dir)
      ()
    } catch {
      case _: java.io.IOException => ()
    }
  }

  private def withSpool[T](body: Path => T): T = {
    val dir = Files.createTempDirectory("cilantro-test-spool")
    try {
      body(dir)
    } finally {
      deleteRecursively(dir)
    }
  }

  private def withPdbFile[T](bytes: Array[Byte])(body: File => T): T = {
    val file = File.createTempFile("standalone", ".pdb")
    Files.write(file.toPath, bytes)
    try {
      body(file)
    } finally {
      file.delete()
    }
  }

  private def listNames(dir: Path): Vector[String] = {
    val stream = Files.list(dir)
    try {
      stream.iterator().asScala.map(_.getFileName.toString).toVector
    } finally {
      stream.close()
    }
  }

  private def type17Region(path: String): (Int, Int) = {
    ModuleDefinition.readModule(path) match {
      case Failure(e) => fail(s"the fixture must read: $e")
      case Success(module) =>
        try {
          module.image.flatMap(_.debugHeader).flatMap { header =>
            header.entties
              .find(_.directory.`type` == io.spicelabs.cilantro.cil.ImageDebugType.embeddedPortablePdb)
              .map(e => (e.directory.pointerToRawData, e.directory.sizeOfData))
          }.getOrElse(fail("the fixture must carry a type-17 entry"))
        } finally {
          module.close()
        }
    }
  }

  // The decompressed root: a standalone portable PDB (BSJB).
  private def standalonePdbBytes: Array[Byte] = {
    val bytes = Files.readAllBytes(java.nio.file.Paths.get(fixturePath))
    val (p, size) = type17Region(fixturePath)
    val envelope = java.util.Arrays.copyOfRange(bytes, p, p + size)
    val declared =
      (envelope(4) & 0xffL) | ((envelope(5) & 0xffL) << 8) | ((envelope(6) & 0xffL) << 16) | ((envelope(7) & 0xffL) << 24)
    val out = new java.io.ByteArrayOutputStream()
    RawInflate.pump(new java.io.ByteArrayInputStream(java.util.Arrays.copyOfRange(envelope, 8, envelope.length)), out, declared)
    out.toByteArray
  }

  // A fresh MPDB envelope (magic + declared + raw deflate of root).
  private def mpdbBytes(declared: Long, root: Array[Byte]): Array[Byte] = {
    val out = new java.io.ByteArrayOutputStream()
    out.write(Array[Byte]('M'.toByte, 'P'.toByte, 'D'.toByte, 'B'.toByte))
    out.write(Array(
      (declared & 0xff).toByte, ((declared >> 8) & 0xff).toByte,
      ((declared >> 16) & 0xff).toByte, ((declared >> 24) & 0xff).toByte
    ))
    val deflaterOut = new java.util.zip.DeflaterOutputStream(out,
      new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true), 65536)
    deflaterOut.write(root)
    deflaterOut.finish()
    deflaterOut.close()
    out.toByteArray
  }

  // The same content through the embedded reader, for the cross-check.
  private def embeddedSources: Vector[(String, Array[Byte])] = {
    withSpool { dir =>
      AssemblyDefinition.readAssembly(fixturePath) match {
        case Failure(e) => fail(s"fixture read failed: $e")
        case Success(ad) =>
          try {
            ad.mainModule match {
              case None => fail("no main module")
              case Some(module) =>
                val captured = module.reader match {
                  case Some(reader) =>
                    reader.withEmbeddedPdb(Some(dir)) {
                      case Success(Some(view)) => view.sources.map(s => (s.name, payloadBytes(s)))
                      case other => fail(s"the embedded reader must succeed: $other")
                    }
                  case None => fail("no reader")
                }
                captured match {
                  case Success(Some(sources)) => sources
                  case other => fail(s"the embedded reader must run f: $other")
                }
            }
          } finally {
            ad.close()
          }
      }
    }
  }

  test("A1: a standalone portable PDB (BSJB) opens and yields the same source files as the embedded path") {
    val derived = standalonePdbBytes
    assert(derived.length > 32, "the decompressed root is a real PDB")
    assertEquals(new String(derived, 0, 4, "UTF-8"), "BSJB", "the derived bytes ARE a portable PDB root")
    withPdbFile(derived) { file =>
      val outcome = PortablePdbFile.withPdb[Vector[(String, Array[Byte])]](file, None) {
        case Success(Some(view)) => view.sources.map(s => (s.name, payloadBytes(s)))
        case other => fail(s"the standalone PDB must open: $other")
      }
      outcome match {
        case Failure(e) => fail(s"withPdb itself must not fail: $e")
        case Success(Some(standalone)) =>
          val embedded = embeddedSources
          assertEquals(standalone.length, embedded.length, "the same number of sources")
          assertEquals(standalone.map(_._1), embedded.map(_._1), "the same document names in the same order")
          standalone.zip(embedded).foreach { case ((name, a), (_, b)) =>
            assertEquals(a.toSeq, b.toSeq, s"$name: byte-for-byte equal across both paths")
          }
          val widget = standalone.find(_._1.contains("Widget.cs")).getOrElse(fail("Widget.cs missing"))
          val repoSource = new String(
            Files.readAllBytes(java.nio.file.Paths.get("../../scripts/fixtures/embedded/Widget.cs")), "UTF-8")
          assertEquals(new String(widget._2, "UTF-8"), repoSource, "the standalone source matches the repo source")
        case Success(None) => fail("f must always run and return a value")
      }
    }
  }

  test("A2: isPortablePdb classifies; non-portable inputs are None inside f, never throw") {
    val bsjb = standalonePdbBytes
    val envelope = mpdbBytes(bsjb.length.toLong, bsjb)
    withPdbFile(bsjb) { file =>
      assert(PortablePdbFile.isPortablePdb(file), "a BSJB root is portable")
      PortablePdbFile.withPdb[Boolean](file, None) {
        case Success(Some(_)) => true
        case _ => false
      } match {
        case Success(Some(true)) => ()
        case other => fail(s"a BSJB root must open: $other")
      }
    }
    withPdbFile(envelope) { file =>
      assert(PortablePdbFile.isPortablePdb(file), "an MPDB envelope is portable")
      withSpool { dir =>
        PortablePdbFile.withPdb[Boolean](file, Some(dir)) {
          case Success(Some(_)) => true
          case _ => false
        } match {
          case Success(Some(true)) => ()
          case other => fail(s"an MPDB envelope must open with a spool dir: $other")
        }
      }
    }
    val dll = Files.readAllBytes(java.nio.file.Paths.get(fixturePath))
    val msf = "Microsoft C/C++ MSF 7.00\r\n\u001aDS\u0000\u0000\u0000".getBytes("UTF-8")
    val junk = "not a pdb at all, just some bytes to fill space".getBytes("UTF-8")
    val cases: Vector[(String, Array[Byte])] = Vector(
      ("pe-dll", dll),
      ("native-msf", msf),
      ("junk", junk),
      ("empty", Array.emptyByteArray),
      ("truncated-bsjb", new String(bsjb).substring(0, 3).getBytes("UTF-8"))
    )
    cases.foreach { case (label, bytes) =>
      withPdbFile(bytes) { file =>
        assert(!PortablePdbFile.isPortablePdb(file), s"$label must not classify as portable")
        val r = scala.util.Try(PortablePdbFile.withPdb[Boolean](file, None) {
          case Success(None) => false
          case other => fail(s"$label: non-portable input must reach f as Success(None), got $other")
        })
        assert(r.isSuccess, s"$label: withPdb never throws")
        r.get match {
          case Success(Some(false)) => ()
          case other => fail(s"$label: unexpected outcome $other")
        }
      }
    }
    // A missing file reaches f as a Failure (f always runs with the
    // outcome; the result is passed back).
    val missing = File.createTempFile("missing", ".pdb")
    missing.delete()
    var seenMissing = false
    val r = scala.util.Try(PortablePdbFile.withPdb[Boolean](missing, None) {
      case Failure(_) => seenMissing = true; true
      case _ => false
    })
    assert(r.isSuccess, "withPdb itself never throws")
    assertEquals(seenMissing, true, "a missing file reaches f as Failure")
    assertEquals(r.get, Success(Some(true)))
  }

  test("A3: the caller's file is never touched; views are callback-scoped; f exceptions propagate") {
    val derived = standalonePdbBytes
    withPdbFile(derived) { file =>
      val original = Files.readAllBytes(file.toPath)
      withSpool { dir =>
        // A BSJB read needs no spool: the dir stays empty during f.
        var during: Vector[String] = Vector.empty
        PortablePdbFile.withPdb[Int](file, Some(dir)) { outcome =>
          during = listNames(dir)
          outcome match {
            case Success(Some(view)) => view.sources.length
            case other => fail(s"must open: $other")
          }
        } match {
          case Success(Some(n)) => assert(n >= 3, s"sources present: $n")
          case other => fail(s"unexpected outcome: $other")
        }
        assertEquals(during, Vector.empty[String], "a BSJB root never spools")
        assertEquals(listNames(dir), Vector.empty[String], "nothing was created")
      }
      // f throwing propagates as an outer Failure (after cleanup).
      val boom = PortablePdbFile.withPdb[Int](file, None) { _ =>
        sys.error("callback boom")
      }
      boom match {
        case Failure(e) => assertEquals(e.getMessage, "callback boom")
        case other => fail(s"f's exception must come back as the outer Failure: $other")
      }
      // The file survives untouched.
      assert(Files.exists(file.toPath), "the caller's file survives")
      assertEquals(Files.readAllBytes(file.toPath).toSeq, original.toSeq, "the file bytes are untouched")
    }
    // A view retained past the call refuses (session-gated sources).
    withPdbFile(derived) { file =>
      var retained: Option[EmbeddedSourceFile] = None
      PortablePdbFile.withPdb[Int](file, None) {
        case Success(Some(view)) =>
          retained = Some(view.sources.head)
          view.sources.length
        case other => fail(s"must open: $other")
      }
      val post = scala.util.Try(payloadBytes(retained.get))
      assert(post.isFailure, "a retained source must refuse after the call")
      assertEquals(post.failed.toOption.map(_.getClass.getSimpleName), Some("IOException"))
    }
  }

  test("A4: hostile standalone PDBs refuse cleanly; MPDB envelopes need a spool dir; flips never escape") {
    // BSJB magic with a hostile version-length claim.
    val hostile = new Array[Byte](64)
    hostile(0) = 'B'.toByte
    hostile(1) = 'S'.toByte
    hostile(2) = 'J'.toByte
    hostile(3) = 'B'.toByte
    hostile(15) = 0x7f.toByte
    withPdbFile(hostile) { file =>
      PortablePdbFile.withPdb[Boolean](file, None) {
        case Failure(_) => true
        case other => fail(s"a hostile version length must refuse: $other")
      } match {
        case Success(Some(true)) => ()
        case other => fail(s"unexpected: $other")
      }
    }
    // MPDB envelope semantics.
    val bsjb = standalonePdbBytes
    val envelope = mpdbBytes(bsjb.length.toLong, bsjb)
    withPdbFile(envelope) { file =>
      // Without a spool dir: clean refusal.
      PortablePdbFile.withPdb[Boolean](file, None) {
        case Failure(_) => true
        case _ => false
      } match {
        case Success(Some(true)) => ()
        case other => fail(s"an MPDB without a spool dir must refuse: $other")
      }
      // With a spool dir: decompresses to the same sources.
      withSpool { dir =>
        PortablePdbFile.withPdb[Int](file, Some(dir)) {
          case Success(Some(view)) => view.sources.length
          case other => fail(s"a valid MPDB with a spool dir must open: $other")
        } match {
          case Success(Some(n)) => assert(n >= 3, s"sources present: $n")
          case other => fail(s"unexpected: $other")
        }
        assertEquals(listNames(dir), Vector.empty[String], "cleanup deleted the scratch")
      }
      // A >= 2^31 declaration refuses BEFORE any write.
      val huge = mpdbBytes(0x80000000L, bsjb)
      withPdbFile(huge) { f2 =>
        withSpool { dir =>
          var during: Vector[String] = Vector.empty
          PortablePdbFile.withPdb[Boolean](f2, Some(dir)) {
            case Failure(_) =>
              during = listNames(dir)
              true
            case _ => false
          } match {
            case Success(Some(true)) => ()
            case other => fail(s"a >= 2^31 declaration must refuse: $other")
          }
          assertEquals(during, Vector.empty[String], "no scratch was ever created")
        }
      }
    }
    // Seeded single-byte flips over the BSJB root: withPdb always
    // completes with a Try; nothing escapes.
    var flip = 0
    while (flip < 100) {
      val mutated = bsjb.clone()
      val pos = (0xbeef + flip * 37) % mutated.length
      mutated(pos) = (mutated(pos) ^ (0x5a + (flip % 200)).toByte).toByte
      withPdbFile(mutated) { file =>
        val r = scala.util.Try(PortablePdbFile.withPdb[Int](file, None)(_ => 0))
        assert(r.isSuccess, s"flip #$flip must not escape")
      }
      flip += 1
    }
  }
}
