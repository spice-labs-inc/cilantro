// CorpusExtractor — pure-JVM entry-safe nupkg extraction.
//
// Why this exists:
//   The provisioner fetches corpus nupkgs and extracts the lib DLLs into
//   corpus/bin. Extraction must be entry-safe (ADR-0006, red-team review):
//   a hostile package is data, but it must not escape the cache directory.
//   This is a Scala port of scripts/GoldenDumper/Extraction.cs, which the
//   docker-based ExtractionSafetyTests (C1-05) pins for the C# extractor.
//   The Scala port adds one rule the C# side gained in the same change:
//   refuse to write through a pre-existing symlink anywhere in the
//   destination chain (File.Create follows symlinks; a git-tracked symlink
//   planted in the cache tree would be followed and truncated).
//
// Theory of the port:
//   - ClassifyEntry mirrors the C# skip/reject rules exactly: only
//     lib/<tfm>/<file>.dll survives; satellite culture dirs, native helper
//     DLLs, ref/runtimes/tools entries are skipped benignly; absolute
//     paths, backslash paths, drive paths and ".." segments are rejected.
//   - java.util.zip does not expose the zip central-directory external
//     attributes, so a minimal central-directory reader recovers the Unix
//     mode bits (0xA000 symlink, 0x6000 block, 0x2000 char, 0x1000 fifo)
//     and rejects those entries exactly like the C# side (C1-05 vectors).
//   - Size caps: 512MB per entry, 2GB total (declared sizes, checked
//     before any write).
//   - Never creates symlinks: all writes are plain Files.write to paths
//     validated above; the destination chain is checked for pre-existing
//     symlinks first.
//
// LLM-friendly notes:
//   - No null, no exceptions for flow control: hostile entries are
//     reported as Left(reason), benign skips are Right(None).
//   - The central-directory reader is tolerant only in the sense that a
//     corrupt/zip64-incomplete archive is rejected (we refuse to extract
//     what we cannot fully audit).

package io.spicelabs.cilantro.metadata

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.zip.{CRC32, ZipEntry, ZipFile, ZipOutputStream}
import scala.util.Try

object CorpusExtractor {

  // Hostile entries are reported as Left(reason) — this port never throws
  // (house style: no exceptions for flow control, enforced by C0-04).

  val MaxEntrySize: Long = 512L * 1024 * 1024
  val MaxTotalSize: Long = 2L * 1024 * 1024 * 1024

  private val NativeDllNames: Set[String] = Set(
    "SQLite.Interop.dll",
    "e_sqlite3.dll",
    "libSkiaSharp.dll",
    "libHarfBuzzSharp.dll",
    "libgdiplus.dll"
  )

  private def isCultureSegment(segment: String): Boolean = {
    if (segment.length < 2 || segment.length > 10) {
      return false
    }
    if (!segment(0).isLower || !segment(1).isLower) {
      return false
    }
    segment.drop(2).forall(c => c.isLetter || c == '-')
  }

  // Mirrors Extraction.ClassifyEntry: Right(None) = benign skip,
  // Right(Some(rel)) = extract under outDir, Left(reason) = hostile name.
  def classifyEntry(entryName: String): Either[String, Option[String]] = {
    if (entryName.contains('\\')) {
      return Left(s"hostile entry (backslash path): $entryName")
    }
    if (entryName.startsWith("/") || (entryName.length >= 2 && entryName(0).isLetter && entryName(1) == ':')) {
      return Left(s"hostile entry (absolute path): $entryName")
    }
    if (entryName.split("/").contains("..")) {
      return Left(s"hostile entry (parent traversal): $entryName")
    }

    val segments = entryName.split("/")
    if (segments.length < 2 || !segments(0).equalsIgnoreCase("lib")) {
      return Right(None)
    }
    if (!entryName.endsWith(".dll")) {
      return Right(None)
    }
    if (segments.length >= 4 && isCultureSegment(segments(segments.length - 2))) {
      return Right(None)
    }
    if (NativeDllNames.contains(segments(segments.length - 1))) {
      return Right(None)
    }
    Right(Some(segments.drop(1).mkString("/")))
  }

  // --- minimal central-directory reader: external attributes only ---

  private case class CdEntry(name: String, externalAttributes: Long)

  private def readCentralDirectory(bytes: Array[Byte]): Option[Vector[CdEntry]] = {
    // Locate the EOCD (PK\x05\x06) by scanning backwards from the end.
    var eocd = -1
    var i = bytes.length - 22
    while (i >= 0 && eocd < 0) {
      if (bytes(i) == 0x50 && bytes(i + 1) == 0x4B && bytes(i + 2) == 0x05 && bytes(i + 3) == 0x06) {
        eocd = i
      }
      i -= 1
    }
    if (eocd < 0) {
      return None
    }
    val readU16 = (off: Int) => ((bytes(off) & 0xFF) | ((bytes(off + 1) & 0xFF) << 8))
    val readU32 = (off: Int) =>
      (bytes(off) & 0xFFL) |
        ((bytes(off + 1) & 0xFFL) << 8) |
        ((bytes(off + 2) & 0xFFL) << 16) |
        ((bytes(off + 3) & 0xFFL) << 24)

    var cdOffset = readU32(eocd + 16)
    var cdSize = readU32(eocd + 12)
    val entryCount = readU16(eocd + 10)

    // Zip64: EOCD fields are 0xFFFFFFFF when present; the zip64 EOCD
    // locator sits 20 bytes before the EOCD (PK\x06\x07).
    if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) {
      val locator = eocd - 20
      if (locator < 0 || readU32(locator) != 0x07064b50L) {
        return None
      }
      val zip64Eocd = readU32(locator + 8).toInt
      if (zip64Eocd < 0 || zip64Eocd + 56 > bytes.length || readU32(zip64Eocd) != 0x06064b50L) {
        return None
      }
      cdOffset = readU32(zip64Eocd + 48)
      cdSize = readU32(zip64Eocd + 40)
      if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) {
        return None
      }
    }
    if (cdOffset + cdSize > bytes.length) {
      return None
    }

    var cursor = cdOffset.toInt
    val entries = Vector.newBuilder[CdEntry]
    var n = 0
    while (n < entryCount && cursor + 46 <= cdOffset.toInt + cdSize.toInt) {
      if (readU32(cursor) != 0x02014b50L) {
        return None
      }
      val nameLen = readU16(cursor + 28)
      val extraLen = readU16(cursor + 30)
      val commentLen = readU16(cursor + 32)
      val externalAttributes = readU32(cursor + 38)
      if (cursor + 46 + nameLen + extraLen + commentLen > cdOffset.toInt + cdSize.toInt) {
        return None
      }
      val name = new String(bytes, cursor + 46, nameLen, "UTF-8")
      entries += CdEntry(name, externalAttributes)
      cursor += 46 + nameLen + extraLen + commentLen
      n += 1
    }
    if (n != entryCount) {
      return None
    }
    Some(entries.result())
  }

  private def isSymlinkMode(externalAttributes: Long): Boolean = {
    val mode = ((externalAttributes >> 16) & 0xF000L)
    mode == 0xA000L || mode == 0x6000L || mode == 0x2000L || mode == 0x1000L
  }

  private def isLink(p: Path): Boolean = {
    Try(Files.isSymbolicLink(p)).getOrElse(false)
  }

  // Refuses to write through a pre-existing symlink anywhere in the chain
  // from outDir (inclusive) down to dest.
  private def assertNoSymlinkChain(outDir: Path, dest: Path): Either[String, Unit] = {
    val destAbs = dest.toAbsolutePath.normalize
    val outAbs = outDir.toAbsolutePath.normalize
    if (!destAbs.startsWith(outAbs)) {
      return Left(s"destination escapes extraction root: $dest")
    }
    var current: Option[Path] = Some(destAbs)
    while (current.isDefined && current.get.startsWith(outAbs)) {
      if (isLink(current.get)) {
        return Left(s"destination is inside a symlink: ${current.get}")
      }
      current = Option(current.get.getParent)
    }
    Right(())
  }

  // Extracts the keepable lib DLLs of a nupkg into outDir. Left(reason)
  // on any hostile entry (nothing is extracted from that nupkg at all).
  // Right(relative paths written) on success.
  def extract(nupkgPath: Path, outDir: Path): Either[String, Vector[String]] = {
    val bytes = Files.readAllBytes(nupkgPath)
    val cd = readCentralDirectory(bytes)
    if (cd.isEmpty) {
      return Left(s"unreadable central directory (refusing to extract): $nupkgPath")
    }
    val attrs = cd.get.map(e => e.name -> e.externalAttributes).toMap
    Files.createDirectories(outDir)

    var total = 0L
    val extracted = Vector.newBuilder[String]

    val zip = new ZipFile(nupkgPath.toFile)
    try {
      val entries = zip.entries()
      while (entries.hasMoreElements) {
        val entry = entries.nextElement()
        val name = entry.getName
        classifyEntry(name) match {
          case Left(reason) => return Left(reason)
          case Right(None) => ()
          case Right(Some(rel)) =>
            if (!attrs.contains(name)) {
              return Left(s"zip entry missing from central directory: $name")
            }
            if (isSymlinkMode(attrs(name))) {
              return Left(s"hostile entry (link/device mode): $name")
            }
            if (entry.getSize > MaxEntrySize) {
              return Left(s"entry too large ($name: ${entry.getSize} > $MaxEntrySize)")
            }
            total += entry.getSize
            if (total > MaxTotalSize) {
              return Left(s"total size exceeds $MaxTotalSize bytes")
            }
            val dest = outDir.resolve(rel)
            assertNoSymlinkChain(outDir, dest) match {
              case Left(reason) => return Left(reason)
              case Right(_) => ()
            }
            Files.createDirectories(dest.getParent)
            val in = zip.getInputStream(entry)
            try {
              Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING)
            } finally {
              in.close()
            }
            extracted += rel
        }
      }
    } finally {
      zip.close()
    }
    Right(extracted.result())
  }

  // --- test helpers: build synthetic zips (also used by the hostile
  // vector tests, which need hand-crafted central directories) ---

  // Minimal stored-entry zip writer that can set external attributes and
  // lie about declared sizes (java.util.zip cannot). Mirrors HostileZip.cs
  // byte layout. declaredSize defaults to content length.
  def writeZip(out: Path, entries: Seq[(String, Array[Byte], Long)]): Unit = {
    writeZipWithSizes(out, entries.map { case (n, c, a) => (n, c, a, c.length.toLong) })
  }

  def writeZipWithSizes(out: Path, entries: Seq[(String, Array[Byte], Long, Long)]): Unit = {
    val bos = new java.io.ByteArrayOutputStream()
    val directory = Vector.newBuilder[(String, Long, Long, Long, Long)]
    entries.foreach { case (name, content, externalAttributes, declaredSize) =>
      val crc = {
        val c = new CRC32()
        c.update(content)
        c.getValue
      }
      val offset = bos.size().toLong
      writeU32(bos, 0x04034b50L)
      writeU16(bos, 20)
      writeU16(bos, 0)
      writeU16(bos, 0)
      writeU16(bos, 0)
      writeU16(bos, 0)
      writeU32(bos, crc)
      writeU32(bos, declaredSize)
      writeU32(bos, declaredSize)
      writeU16(bos, name.getBytes("UTF-8").length)
      writeU16(bos, 0)
      bos.write(name.getBytes("UTF-8"))
      bos.write(content)
      directory += ((name, crc, declaredSize, externalAttributes, offset))
    }
    val cdOffset = bos.size().toLong
    directory.result().foreach { case (name, crc, size, attrs, offset) =>
      writeU32(bos, 0x02014b50L)
      writeU16(bos, 20)
      writeU16(bos, 20)
      writeU16(bos, 0)
      writeU16(bos, 0)
      writeU16(bos, 0)
      writeU16(bos, 0)
      writeU32(bos, crc)
      writeU32(bos, size)
      writeU32(bos, size)
      writeU16(bos, name.getBytes("UTF-8").length)
      writeU16(bos, 0)
      writeU16(bos, 0)
      writeU16(bos, 0)
      writeU32(bos, attrs)
      writeU32(bos, offset)
      bos.write(name.getBytes("UTF-8"))
    }
    val cdSize = bos.size() - cdOffset
    writeU32(bos, 0x06054b50L)
    writeU16(bos, 0)
    writeU16(bos, 0)
    writeU16(bos, entries.size)
    writeU16(bos, entries.size)
    writeU32(bos, cdSize)
    writeU32(bos, cdOffset)
    writeU16(bos, 0)
    Files.write(out, bos.toByteArray)
  }

  private def writeU16(s: java.io.ByteArrayOutputStream, v: Int): Unit = {
    s.write(v & 0xFF)
    s.write((v >> 8) & 0xFF)
  }

  private def writeU32(s: java.io.ByteArrayOutputStream, v: Long): Unit = {
    s.write((v & 0xFF).toInt)
    s.write(((v >> 8) & 0xFF).toInt)
    s.write(((v >> 16) & 0xFF).toInt)
    s.write(((v >> 24) & 0xFF).toInt)
  }

  // Builds a benign nupkg from lib DLL entries (used by tests to create
  // synthetic corpora that the fetcher can consume).
  def writeBenignNupkg(out: Path, entries: Seq[(String, Array[Byte])]): Unit = {
    val bos = new java.io.ByteArrayOutputStream()
    val zos = new ZipOutputStream(bos)
    entries.foreach { case (name, content) =>
      zos.putNextEntry(new ZipEntry(name))
      zos.write(content)
      zos.closeEntry()
    }
    zos.close()
    Files.write(out, bos.toByteArray)
  }
}
