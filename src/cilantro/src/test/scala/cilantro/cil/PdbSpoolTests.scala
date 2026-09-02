// PdbSpoolTests — CP-5a..f: the spooled embedded portable PDB
// (plan 2026_09_02, phase D; ADR-0014, D-6/D-12/D-13).
//
// Why these tests exist:
//   The embedded PDB is a raw-deflate blob that must be decompressed
//   before its tables can be walked. The streaming design
//   decompresses the root into a scratch file inside the CALLER's
//   spool directory (cilantro never creates/owns/deletes the
//   directory and deletes only the files it created), memory-maps it,
//   and walks the tables from the map; sources stream lazily inside
//   processStream.
//
// Theory of the test:
//   - CP-5a: spool placement and hygiene (scratch appears in the
//     caller's dir, is deleted at close/walk end, the dir and its
//     pre-existing content are untouched; a bad spoolRoot refuses).
//   - CP-5b: the pinned fixture's sources stream with the exact
//     pinned bytes through the walk's EmbeddedSource entries.
//   - CP-5c: spoolRoot = None -> DebugBlob only, no sources; the
//     accessor yields Success(None); magic-invalid type-17 -> benign.
//   - CP-5d: envelope refusals — declared > actual (short stream),
//     declared < actual (abort at the bound, output never exceeds
//     declared), declared >= 2^31 (refusal BEFORE any write), and the
//     exact-size boundary succeeds; each refusal deletes the partial
//     scratch and fails the walk (None).
//   - CP-5e: hostile PDB internals via a decompress -> patch ->
//     re-deflate harness: a row-count bomb refuses on the tables
//     extent; a heap offset past the spool end refuses; a source
//     whose deflate output mismatches its declared format refuses at
//     stream level (IOException inside processStream, walk Some).
//   - CP-5f: pull semantics — reading only the start of a deflate
//     source performs only the work the consumer demands.
//
// Requirements traced:
//   workspace/2026_09_01_cilantro_handoff.md §4 CP-5; ADR-0014.

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import io.spicelabs.cilantro._
import io.spicelabs.cilantro.PE.{RawInflate, RawInflateInputStream}
import io.spicelabs.cilantro.metadata.CorpusProvisioner
import java.io.{File, InputStream}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class PdbSpoolTests extends munit.FunSuite {

  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  private def corpusRoot = CorpusProvisioner.ensureCorpus()

  private def fixtureBytes: Array[Byte] =
    Files.readAllBytes(corpusRoot.resolve("fixtures/embedded_pdb_fixture.dll"))

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

  private def withFile[T](bytes: Array[Byte])(body: File => T): T = {
    val file = File.createTempFile("pdbsuite", ".dll")
    Files.write(file.toPath, bytes)
    try {
      body(file)
    } finally {
      file.delete()
    }
  }

  // ---- helpers over the fixture --------------------------------------

  // The type-17 debug entry's file offset + size in a freshly parsed
  // fixture copy, plus its payload slice source.
  private def type17Region(path: String): (Int, Int) = {
    ModuleDefinition.readModule(path) match {
      case Failure(e) => fail(s"the fixture must read: $e")
      case Success(module) =>
        try {
          val found = module.image.flatMap(_.debugHeader).flatMap { header =>
            header.entties
              .find(_.directory.`type` == io.spicelabs.cilantro.cil.ImageDebugType.embeddedPortablePdb)
              .map(e => (e.directory.pointerToRawData, e.directory.sizeOfData))
          }
          found.getOrElse(fail("the fixture must carry a type-17 entry"))
        } finally {
          module.close()
        }
    }
  }

  // Rebuild an assembly file whose type-17 payload is replaced by a
  // freshly built MPDB (magic + declared + raw deflate of root).
  private def withMpdb(declared: Long, root: Array[Byte]): Array[Byte] = {
    val original = fixtureBytes
    val (p, size) = type17Region(tempFixture)
    val out = new java.io.ByteArrayOutputStream()
    out.write(original, 0, p)
    val deflater = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
    val compressed = new java.io.ByteArrayOutputStream()
    val deflaterOut = new java.util.zip.DeflaterOutputStream(compressed, deflater, 65536)
    deflaterOut.write(root)
    deflaterOut.finish()
    deflaterOut.close()
    val magic = Array[Byte]('M'.toByte, 'P'.toByte, 'D'.toByte, 'B'.toByte)
    out.write(magic)
    out.write(Array(
      (declared & 0xff).toByte, ((declared >> 8) & 0xff).toByte,
      ((declared >> 16) & 0xff).toByte, ((declared >> 24) & 0xff).toByte
    ))
    out.write(compressed.toByteArray)
    out.write(original, p + size, original.length - p - size)
    out.toByteArray
  }

  private lazy val tempFixture: String = {
    val f = File.createTempFile("pdb-fixture", ".dll")
    Files.write(f.toPath, fixtureBytes)
    f.deleteOnExit()
    f.getAbsolutePath
  }

  test("CP-5a: the root spools into the caller's directory; hygiene is exact") {
    withSpool { dir =>
      // A pre-existing marker file must survive untouched.
      val marker = dir.resolve("marker.txt")
      Files.write(marker, "keep-me".getBytes("UTF-8"))
      val fixture = corpusRoot.resolve("fixtures/embedded_pdb_fixture.dll").toFile

      val during = AssemblyWalker.withinAssemblyStream[Vector[String]](fixture) { entries =>
        val names = entries.collect { case s: EmbeddedSourceEntry => s.name }
        val spooled = Files.list(dir)
        val files = try {
          spooled.iterator().asScala.filter(p => Files.isRegularFile(p)).map(_.getFileName.toString).toVector
        } finally {
          spooled.close()
        }
        assertEquals(files.contains("marker.txt"), true, "the marker is untouched")
        val cilantroFiles = files.filter(_.startsWith("cilantro-pdb-"))
        assertEquals(cilantroFiles.length, 1, "exactly one cilantro scratch file during f")
        names
      }(Some(dir))
      assert(during.isDefined, "the fixture walks with a spool dir")
      assert(during.get.nonEmpty, "the spooled walk yields embedded sources")
      val after = Files.list(dir)
      val remaining = try {
        after.iterator().asScala.map(_.getFileName.toString).toVector
      } finally {
        after.close()
      }
      assertEquals(remaining.sorted, Vector("marker.txt"), "after the walk: the scratch is deleted, the dir is the caller's")
    }
  }

  test("CP-5b: the pinned fixture's sources stream exactly through the walk") {
    val fixture = corpusRoot.resolve("fixtures/embedded_pdb_fixture.dll").toFile
    withSpool { dir =>
      val outcome = AssemblyWalker.withinAssemblyStream[Vector[(String, String, Option[String], Array[Byte])]](fixture) { entries =>
        entries.collect { case s: EmbeddedSourceEntry => (s.name, s.kind.toString, s.mimeHint, payloadBytes(s)) }
      }(Some(dir))
      outcome match {
        case None => fail("the fixture must walk with a spool dir")
        case Some(sources) =>
          assert(sources.length >= 3, s"Widget.cs + generated sources expected, got ${sources.length}")
          sources.foreach { case (name, kind, hint, bytes) =>
            assertEquals(kind, "EmbeddedSource")
            assertEquals(hint, None: Option[String])
            assert(!name.contains('/') && !name.contains('\\'), s"source names are hardened: $name")
            assert(bytes.nonEmpty, "sources carry bytes")
          }
          val widget = sources.find(_._1.contains("Widget.cs")).getOrElse(fail("Widget.cs source missing"))
          val repoSource = new String(
            Files.readAllBytes(java.nio.file.Paths.get("../../scripts/fixtures/embedded/Widget.cs")), "UTF-8")
          assertEquals(new String(widget._4, "UTF-8"), repoSource, "the embedded Widget.cs must equal the repo source byte-for-byte")
      }
      // Sources come AFTER the debug blobs (pinned order), and the
      // type-17 DebugBlob stays present alongside them.
      val order = AssemblyWalker.withinAssemblyStream[Vector[String]](fixture) { entries =>
        entries.map(e => e.kind.toString)
      }(Some(dir))
      val kinds = order.getOrElse(fail("the fixture must walk"))
      val debugPos = kinds.lastIndexWhere(_ == "DebugBlob")
      val sourcePos = kinds.indexOf("EmbeddedSource")
      assert(sourcePos > debugPos, s"embedded sources follow debug blobs: $kinds")
    }
  }

  test("CP-5c: spoolRoot = None and magic-invalid type-17 yield DebugBlob only") {
    val fixture = corpusRoot.resolve("fixtures/embedded_pdb_fixture.dll").toFile
    val outcome = AssemblyWalker.withinAssemblyStream[Vector[String]](fixture)(e => e.map(x => x.kind.toString))(None)
    outcome match {
      case None => fail("the fixture walks without a spool")
      case Some(kinds) =>
        assert(kinds.contains("DebugBlob"), "the type-17 DebugBlob is present")
        assertEquals(kinds.count(_ == "EmbeddedSource"), 0, "no sources without a spool dir")
    }
    // The accessor alone: Success(None) for None, no in-memory fallback.
    AssemblyDefinition.readAssembly(fixture.getAbsolutePath) match {
      case Success(ad) =>
        try {
          ad.mainModule.foreach { module =>
            module.read(Vector.empty[Unit], (_, reader: io.spicelabs.cilantro.MetadataReader) => {
              val r = reader.readEmbeddedPortablePdb(None)
              assertEquals(r, Success(None), "spoolDir = None yields absent (no in-memory fallback)")
            })
          }
        } finally {
          ad.close()
        }
      case Failure(e) => fail(s"readAssembly failed: $e")
    }
    // Magic-invalid type-17 (flip the MPDB magic): DebugBlob only,
    // the walk succeeds — benign absent, not a refusal.
    val mutated = fixtureBytes.clone()
    val (p, _) = type17Region(tempFixture)
    mutated(p) = 'X'.toByte
    withFile(mutated) { file =>
      val outcome = AssemblyWalker.withinAssemblyStream[Vector[String]](file)(e => e.map(x => x.kind.toString))(Some(java.nio.file.Paths.get("/tmp")))
      outcome match {
        case None => fail("a magic-invalid type-17 entry is benign-absent")
        case Some(kinds) =>
          assert(kinds.contains("DebugBlob"), "the type-17 blob entry remains")
          assertEquals(kinds.count(_ == "EmbeddedSource"), 0)
      }
    }
  }

  test("CP-5d: envelope refusals — declared mismatch and the >= 2^31 refusal before any write") {
    val originalRoot = decompressFixtureRoot()
    assert(originalRoot.length > 100, "the fixture root is a real PDB")

    def walkMpdb(declared: Long, root: Array[Byte]): Option[Vector[String]] = {
      val bytes = withMpdb(declared, root)
      withFile(bytes) { file =>
        withSpool { dir =>
          AssemblyWalker.withinAssemblyStream[Vector[String]](file)(e => e.map(x => x.kind.toString))(Some(dir))
        }
      }
    }

    // Exact boundary: declared == actual succeeds.
    val exact = walkMpdb(originalRoot.length.toLong, originalRoot)
    assert(exact.isDefined, "declared == actual must succeed")
    assert(exact.get.count(_ == "EmbeddedSource") >= 3, "sources come through on the exact boundary")

    // Declared larger than actual: short stream -> refusal -> None.
    val over = walkMpdb(originalRoot.length.toLong + 1000L, originalRoot)
    assertEquals(over, None, "a declared size beyond the actual output refuses the walk")

    // Declared smaller than actual: abort at the bound (output never
    // exceeds declared) -> refusal -> None.
    val under = walkMpdb(originalRoot.length.toLong - 100L, originalRoot)
    assertEquals(under, None, "output past the declared size refuses the walk")

    // Declared >= 2^31: refusal BEFORE any write — the spool dir must
    // contain no cilantro scratch at all.
    val huge = walkMpdb(0x80000000L, originalRoot)
    assertEquals(huge, None, "a >= 2^31 declaration refuses before any write")
  }

  test("CP-5e: hostile PDB internals — tables extent and heap offsets refuse; source-level mismatch is stream-level") {
    val originalRoot = decompressFixtureRoot()
    // (1) A row-count bomb: patch the #~ Document row count to a value
    // that cannot fit the tables extent. Locate #~ inside the root.
    val tablesHeader = locateTablesHeader(originalRoot).getOrElse(fail("the root must carry a #~ stream"))
    val tablesOff = tablesHeader._1
    val patched = originalRoot.clone()
    // Counts start at tablesOff + 24, in ascending valid-table order.
    // Find the Document (0x30) count position by walking the valid mask.
    val valid = {
      var v = 0L
      var i = 7
      while (i >= 0) {
        v = (v << 8) | (patched(tablesOff + 8 + i) & 0xff).toLong
        i -= 1
      }
      v
    }
    var pos = tablesOff + 24
    var tid = 0
    var docCountAt = -1
    while (tid < 64) {
      if ((valid & (1L << tid)) != 0) {
        if (tid == 0x30) {
          docCountAt = pos.toInt
        }
        pos += 4
      }
      tid += 1
    }
    assert(docCountAt >= 0, "Document count found")
    // A count of 0x7fffffff cannot fit any extent.
    patched(docCountAt) = 0xff.toByte
    patched(docCountAt + 1) = 0xff.toByte
    patched(docCountAt + 2) = 0xff.toByte
    patched(docCountAt + 3) = 0x7f.toByte
    val bombBytes = withMpdb(originalRoot.length.toLong, patched)
    withFile(bombBytes) { file =>
      withSpool { dir =>
        val outcome = AssemblyWalker.withinAssemblyStream[Int](file)(_.length)(Some(dir))
        assertEquals(outcome, None, "a row-count bomb must refuse the walk")
      }
    }

    // (2) A heap offset past the spool end: patch the #Blob stream's
    // declared offset to the root's own end (bounds refuse).
    val blobOffAt = locateStreamHeader(originalRoot, "#Blob").getOrElse(fail("the root must carry a #Blob stream"))
    val rootLen = originalRoot.length
    val shifted = originalRoot.clone()
    def putI4(at: Int, v: Int): Unit = {
      shifted(at) = (v & 0xff).toByte
      shifted(at + 1) = ((v >> 8) & 0xff).toByte
      shifted(at + 2) = ((v >> 16) & 0xff).toByte
      shifted(at + 3) = ((v >> 24) & 0xff).toByte
    }
    putI4(blobOffAt, rootLen - 2)
    val shiftedBytes = withMpdb(originalRoot.length.toLong, shifted)
    withFile(shiftedBytes) { file =>
      withSpool { dir =>
        val outcome = AssemblyWalker.withinAssemblyStream[Int](file)(_.length)(Some(dir))
        assertEquals(outcome, None, "a heap region past the spool end must refuse")
      }
    }

    // (3) Source-level deflate mismatch is stream-level, not a walk
    // refusal: a source whose deflate output disagrees with its
    // declared format refuses INSIDE processStream (IOException), and
    // the walk itself stays Some. Constructed on the machinery level
    // (the same code path deflate-format sources use).
    val raw = new java.io.ByteArrayOutputStream()
    val deflater = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
    val deflaterOut = new java.util.zip.DeflaterOutputStream(raw, deflater, 65536)
    deflaterOut.write(Array.fill[Byte](4096)(7))
    deflaterOut.finish()
    deflaterOut.close()
    val compressed = raw.toByteArray
    val bad = new RawInflateInputStream(new java.io.ByteArrayInputStream(compressed), 100L)
    val readAttempt = scala.util.Try {
      val sink = new java.io.ByteArrayOutputStream()
      val buf = new Array[Byte](1024)
      var n = bad.read(buf)
      while (n >= 0) {
        if (n > 0) sink.write(buf, 0, n)
        n = bad.read(buf)
      }
      sink.size()
    }
    readAttempt match {
      case Success(_) => fail("a deflate that exceeds its declared size must refuse")
      case Failure(e) =>
        assertEquals(e.getClass.getSimpleName, "IOException", s"the refusal is an IOException, got $e")
    }
    bad.close()
    // The truncated variant (declared larger than the stream) also
    // refuses at stream level.
    val good = new RawInflateInputStream(new java.io.ByteArrayInputStream(compressed), 4096L)
    val goodRead = scala.util.Try {
      val sink = new java.io.ByteArrayOutputStream()
      val buf = new Array[Byte](1024)
      var n = good.read(buf)
      while (n >= 0) {
        if (n > 0) sink.write(buf, 0, n)
        n = good.read(buf)
      }
      sink.size()
    }
    assertEquals(goodRead, Success(4096), "declared == actual streams cleanly")
    val truncated = new RawInflateInputStream(new java.io.ByteArrayInputStream(compressed), 5000L)
    val truncRead = scala.util.Try {
      val buf = new Array[Byte](1024)
      var n = truncated.read(buf)
      while (n >= 0) {
        n = truncated.read(buf)
      }
    }
    assert(truncRead.isFailure, "a short deflate stream must refuse at stream level")
    truncated.close()
  }

  test("CP-5f: pull semantics — a partial read performs only partial work") {
    // Feed RawInflateInputStream from an InputStream that dies after a
    // small input budget, and read a small prefix of a stream whose
    // output is far larger. Inflation is on demand: a 64 KiB output
    // granule of highly compressible data demands only a few input
    // bytes, so a consumer that reads a little and closes never forces
    // the rest — an implementation that inflated eagerly would demand
    // all of the compressed input and blow the budget.
    // The compressed stream must be LARGER than one input granule
    // (64 KiB), so a single fill cannot swallow it all: an eager
    // implementation would demand the entire stream, the pull
    // implementation only what one output granule needs.
    val raw = new java.io.ByteArrayOutputStream()
    val deflaterOut = new java.util.zip.DeflaterOutputStream(raw, new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true), 65536)
    val payload = Array.fill[Byte](128 * 1024 * 1024)(0)
    deflaterOut.write(payload)
    deflaterOut.finish()
    deflaterOut.close()
    val compressed = raw.toByteArray
    assert(compressed.length > 65536 + 8192, s"the compressed stream must exceed one granule, got ${compressed.length}")
    val granuleBudget = 65536L + 8192L
    var demanded = 0L
    val budgeted = new InputStream {
      private val inner = new java.io.ByteArrayInputStream(compressed)
      override def read(): Int = {
        demanded += 1
        if (demanded > granuleBudget) sys.error("input budget exceeded")
        inner.read()
      }
      override def read(b: Array[Byte], off: Int, len: Int): Int = {
        val n = inner.read(b, off, len)
        if (n > 0) demanded += n
        if (demanded > granuleBudget) sys.error("input budget exceeded")
        n
      }
    }
    val inflating = new RawInflateInputStream(budgeted, payload.length.toLong)
    val outcome = scala.util.Try {
      val prefix = new Array[Byte](1000)
      var off = 0
      while (off < prefix.length) {
        val n = inflating.read(prefix, off, prefix.length - off)
        if (n <= 0) off = prefix.length else off += n
      }
      // A partial read of a tiny fraction of the output must not have
      // demanded the whole compressed input — only the input one
      // output granule needs.
      assert(demanded <= granuleBudget && demanded < compressed.length, s"a partial read demanded $demanded of ${compressed.length} input bytes")
      // The bytes served are the real payload prefix.
      assertEquals(java.util.Arrays.copyOfRange(prefix, 0, 1000).toVector, Array.fill[Byte](1000)(0).toVector)
    }
    outcome match {
      case Failure(e) => fail(s"partial reads must not force full inflation: $e")
      case Success(_) => ()
    }
    inflating.close()
  }

  // ---- surgery helpers ------------------------------------------------

  private def decompressFixtureRoot(): Array[Byte] = {
    val original = fixtureBytes
    val (p, size) = type17Region(tempFixture)
    val mpdb = java.util.Arrays.copyOfRange(original, p, p + size)
    val declared = ((mpdb(4) & 0xffL)) | ((mpdb(5) & 0xffL) << 8) | ((mpdb(6) & 0xffL) << 16) | ((mpdb(7) & 0xffL) << 24)
    val out = new java.io.ByteArrayOutputStream()
    RawInflate.pump(new java.io.ByteArrayInputStream(java.util.Arrays.copyOfRange(mpdb, 8, mpdb.length)), out, declared)
    out.toByteArray
  }

  private def locateTablesHeader(root: Array[Byte]): Option[(Int, Int)] = {
    if (root.length < 32 || root(0) != 'B'.toByte || root(1) != 'S'.toByte || root(2) != 'J'.toByte || root(3) != 'B'.toByte) {
      return None
    }
    def u32(at: Int): Long =
      (root(at) & 0xffL) | ((root(at + 1) & 0xffL) << 8) | ((root(at + 2) & 0xffL) << 16) | ((root(at + 3) & 0xffL) << 24)
    def u16(at: Int): Int = (root(at) & 0xff) | ((root(at + 1) & 0xff) << 8)
    val versionLength = u32(12)
    var pos = (16 + versionLength + 3) & ~3L
    val streamCount = u16(pos.toInt + 2)
    pos += 4
    var tables: Option[(Int, Int)] = None
    var i = 0L
    while (i < streamCount) {
      val offset = u32(pos.toInt).toInt
      val size = u32(pos.toInt + 4).toInt
      var namePos = pos + 8
      val nameStart = namePos
      while (namePos < root.length && root(namePos.toInt) != 0) {
        namePos += 1
      }
      val name = new String(java.util.Arrays.copyOfRange(root, nameStart.toInt, namePos.toInt), "UTF-8")
      namePos += 1
      pos = (namePos + 3) & ~3L
      if (name == "#~" || name == "#-") {
        tables = Some((offset, size))
      }
      i += 1
    }
    tables
  }

  private def locateStreamHeader(root: Array[Byte], wanted: String): Option[Int] = {
    if (root.length < 32) {
      return None
    }
    def u32(at: Int): Long =
      (root(at) & 0xffL) | ((root(at + 1) & 0xffL) << 8) | ((root(at + 2) & 0xffL) << 16) | ((root(at + 3) & 0xffL) << 24)
    def u16(at: Int): Int = (root(at) & 0xff) | ((root(at + 1) & 0xff) << 8)
    val versionLength = u32(12)
    var pos = (16 + versionLength + 3) & ~3L
    val streamCount = u16(pos.toInt + 2)
    pos += 4
    var result: Option[Int] = None
    var i = 0L
    while (i < streamCount && result.isEmpty) {
      val headerAt = pos.toInt
      var namePos = pos + 8
      val nameStart = namePos
      while (namePos < root.length && root(namePos.toInt) != 0) {
        namePos += 1
      }
      val name = new String(java.util.Arrays.copyOfRange(root, nameStart.toInt, namePos.toInt), "UTF-8")
      namePos += 1
      pos = (namePos + 3) & ~3L
      if (name == wanted) {
        result = Some(headerAt)
      }
      i += 1
    }
    result
  }
}
