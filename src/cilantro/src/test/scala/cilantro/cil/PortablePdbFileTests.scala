// PortablePdbFileTests — opening a STANDALONE portable PDB as a
// container of source files (plan follow-up, 2026-09-04; ADR-0014
// amendment).
//
// Why these tests exist:
//   Goat Rodeo receives .pdb files that arrive outside any DLL (e.g.
//   next to an assembly in a package). A portable PDB is a file whose
//   bytes ARE the BSJB metadata root — the same bytes the embedded
//   MPDB envelope contains once decompressed. cilantro's embedded
//   path is anchored to a type-17 entry inside a parsed assembly, so
//   a standalone file needs its own public entry: PortablePdbFile
//   (probe + open). These tests pin it red→green.
//
// Theory of the test:
//   - The standalone fixture is DERIVED from the pinned embedded
//     fixture (decompressing its MPDB envelope yields the portable
//     PDB bytes) — no new committed binaries; the pinned DLL keeps
//     the content pinned.
//   - CP-8-style cross-check: the standalone view's sources must be
//     byte-for-byte the same (names + order + text) as the embedded
//     path's view over the same content.
//   - The BSJB gate: non-portable inputs (PE files, native MSF PDB
//     headers, junk, truncated) yield None, never throw.
//   - Hostile structure refuses as a clean Try Failure.
//   - The caller's file is never deleted (view close releases the
//     map only).
//
// Requirements traced:
//   Goat Rodeo issue exchange 2026-09-04 (standalone portable PDB
//   source files); ADR-0014 amendment.

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import io.spicelabs.cilantro._
import io.spicelabs.cilantro.PE.RawInflate
import io.spicelabs.cilantro.metadata.CorpusProvisioner
import java.io.File
import java.nio.file.{Files, Path}

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

  // The type-17 entry's raw region in the pinned fixture.
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

  // Decompress the pinned fixture's MPDB: the result IS a standalone
  // portable PDB (BSJB root + streams).
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

  private def withPdbFile[T](bytes: Array[Byte])(body: File => T): T = {
    val file = File.createTempFile("standalone", ".pdb")
    Files.write(file.toPath, bytes)
    try {
      body(file)
    } finally {
      file.delete()
    }
  }

  // The same content through the embedded path, for the cross-check.
  private def embeddedSources: Vector[(String, Array[Byte])] = {
    val dir = Files.createTempDirectory("cilantro-test-spool")
    try {
      AssemblyDefinition.readAssembly(fixturePath) match {
        case Failure(e) => fail(s"fixture read failed: $e")
        case Success(ad) =>
          try {
            ad.mainModule match {
              case None => fail("no main module")
              case Some(module) =>
                val outcome: scala.util.Try[Option[PDBView]] = module.read(Option.empty[PDBView], (_, reader: MetadataReader) =>
                  reader.readEmbeddedPortablePdb(Some(dir)))
                outcome match {
                  case Success(Some(view)) =>
                    try {
                      view.sources.map(s => (s.name, payloadBytes(s)))
                    } finally {
                      view.close()
                    }
                  case Success(None) => fail("the embedded fixture must carry a PDB")
                  case Failure(e) => fail(s"embedded PDB read failed: $e")
                }
            }
          } finally {
            ad.close()
          }
      }
    } finally {
      deleteRecursively(dir)
    }
  }

  test("A1: a standalone portable PDB opens and yields the same source files as the embedded path") {
    val derived = standalonePdbBytes
    assert(derived.length > 32, "the decompressed root is a real PDB")
    assertEquals(new String(derived, 0, 4, "UTF-8"), "BSJB", "the derived bytes ARE a portable PDB root")
    withPdbFile(derived) { file =>
      PortablePdbFile.open(file) match {
        case Failure(e) => fail(s"the standalone PDB must open: $e")
        case Success(None) => fail("the standalone PDB must be recognized")
        case Success(Some(view)) =>
          try {
            val standalone = view.sources.map(s => (s.name, payloadBytes(s)))
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
          } finally {
            view.close()
          }
      }
    }
  }

  test("A2: isPortablePdb classifies; open returns None for non-portable inputs, never throws") {
    val derived = standalonePdbBytes
    withPdbFile(derived) { file =>
      assert(PortablePdbFile.isPortablePdb(file), "the derived PDB is portable")
      val opened = PortablePdbFile.open(file)
      assert(opened.isSuccess && opened.get.isDefined, "the derived PDB opens")
    }
    // Non-portable inputs: a PE DLL, an MSF (native PDB) header, junk,
    // an empty file, and a truncated BSJB prefix.
    val dll = Files.readAllBytes(java.nio.file.Paths.get(fixturePath))
    val msf = "Microsoft C/C++ MSF 7.00\r\n\u001aDS\u0000\u0000\u0000".getBytes("UTF-8")
    val junk = "not a pdb at all, just some bytes to fill space".getBytes("UTF-8")
    val cases: Vector[(String, Array[Byte])] = Vector(
      ("pe-dll", dll),
      ("native-msf", msf),
      ("junk", junk),
      ("empty", Array.emptyByteArray),
      ("truncated-bsjb", new String(derived).substring(0, 3).getBytes("UTF-8"))
    )
    cases.foreach { case (label, bytes) =>
      withPdbFile(bytes) { file =>
        assert(!PortablePdbFile.isPortablePdb(file), s"$label must not classify as portable")
        PortablePdbFile.open(file) match {
          case Failure(e) => fail(s"$label: non-portable input must be None, not a refusal: $e")
          case Success(None) => ()
          case Success(Some(_)) => fail(s"$label: non-portable input must not open")
        }
      }
    }
    // A missing file is a clean Failure, not an exception out of the call.
    val missing = File.createTempFile("missing", ".pdb")
    missing.delete()
    val r = scala.util.Try(PortablePdbFile.open(missing))
    assert(r.isSuccess, "open itself never throws")
    assert(r.get.isFailure, "a missing file is a clean Failure")
  }

  test("A3: the caller's file is never deleted by open/close") {
    val derived = standalonePdbBytes
    withPdbFile(derived) { file =>
      val original = Files.readAllBytes(file.toPath)
      PortablePdbFile.open(file) match {
        case Success(Some(view)) =>
          view.close()
          view.close() // idempotent
        case other => fail(s"the standalone PDB must open: $other")
      }
      assert(Files.exists(file.toPath), "the caller's file survives close")
      assertEquals(Files.readAllBytes(file.toPath).toSeq, original.toSeq, "the file bytes are untouched")
    }
  }

  test("A4: hostile standalone PDBs refuse cleanly; seeded flips never escape") {
    // BSJB magic with a hostile version-length claim.
    val hostile = new Array[Byte](64)
    hostile(0) = 'B'.toByte
    hostile(1) = 'S'.toByte
    hostile(2) = 'J'.toByte
    hostile(3) = 'B'.toByte
    hostile(15) = 0x7f.toByte // versionLength high byte -> huge skip
    withPdbFile(hostile) { file =>
      PortablePdbFile.open(file) match {
        case Success(_) => fail("a hostile version length must refuse")
        case Failure(e) => assertEquals(e.getClass.getSimpleName, "DataFormatException", s"refusal type: $e")
      }
    }
    // Seeded single-byte flips over the derived PDB: open always
    // completes with a Try (Some/None/Failure), never escapes.
    val derived = standalonePdbBytes
    var flip = 0
    while (flip < 100) {
      val mutated = derived.clone()
      val pos = (0xbeef + flip * 37) % mutated.length
      mutated(pos) = (mutated(pos) ^ (0x5a + (flip % 200)).toByte).toByte
      withPdbFile(mutated) { file =>
        val r = scala.util.Try(PortablePdbFile.open(file))
        assert(r.isSuccess, s"flip #$flip must not escape")
      }
      flip += 1
    }
  }
}
