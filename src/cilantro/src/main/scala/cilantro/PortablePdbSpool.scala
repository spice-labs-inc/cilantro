// PortablePdbSpool — the spooled embedded-portable-PDB path
// (plan 2026_09_02, phase D; ADR-0014, D-6/D-12/D-13).
//
// The embedded portable PDB is a raw-deflate blob (the MPDB envelope)
// and must be decompressed before its tables can be walked — there is
// no random access into a deflate stream. The streaming design
// decompresses the root into a scratch file inside the CALLER's spool
// directory (never cilantro-owned temp state; cilantro deletes only
// the files it created, D-6), memory-maps it, and walks the PDB
// tables (#~ / #Strings / #GUID / #Blob) from the map.
//
// Rules (all structural — there are no byte budgets):
//   - Envelope: "MPDB" magic; the declared uncompressed size is read
//     as an unsigned 32-bit value; a declaration >= 2^31 refuses
//     BEFORE any write (the map ceiling, D-12); output can never
//     exceed the declared size during inflation; a stream that ends
//     short of the declaration refuses; IO failures refuse.
//   - Tables: all extent arithmetic is Long; row counts must fit the
//     tables extent; index widths (heaps, tables, coded indexes) are
//     computed exactly as the assembly reader computes them; a
//     source-count object guard (1,000,000, D-10) bounds the result
//     vector.
//   - Sources: format-0 values stream as map slices; deflate-format
//     values are pull-inflated inside processStream, bounded by
//     their declared format length (D-13). A mid-stream source
//     refusal is an IOException to the reader — not a walk refusal.
//
// The parser mirrors the table semantics of the pre-spool
// implementation so the pinned fixture sources and their order are
// unchanged.

package io.spicelabs.cilantro

import io.spicelabs.cilantro.PE.{MappedSliceSource, RawInflate, RawInflateInputStream}
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.zip.DataFormatException
import scala.collection.mutable

private[cilantro] object PortablePdbSpool {

    // The embedded-source marker GUID (the kind row's GUID heap value).
    private val embeddedSourceKind: Array[Byte] = Array(
        0x1b.toByte, 0x57.toByte, 0x8a.toByte, 0x0e.toByte, 0x26.toByte, 0x69.toByte, 0x6e.toByte, 0x46.toByte,
        0xb4.toByte, 0xad.toByte, 0x8a.toByte, 0xb0.toByte, 0x46.toByte, 0x11.toByte, 0xf5.toByte, 0xfe.toByte
    )

    private val maxSources = 1000000

    private val documentTable = 0x30
    private val customDebugTable = 0x37

    // Returns true when the payload is NOT an MPDB envelope (the
    // benign-absent case); false + the unsigned declared size when the
    // magic matches.
    private def envelope(blob: PayloadSource): (Boolean, Long) = {
        blob.processStream { in =>
            val head = new Array[Byte](8)
            var off = 0
            while (off < 8) {
                val n = in.read(head, off, 8 - off)
                if (n <= 0) {
                    off = 8
                }
                else {
                    off += n
                }
            }
            if (off < 8 || head(0) != 'M'.toByte || head(1) != 'P'.toByte || head(2) != 'D'.toByte || head(3) != 'B'.toByte) {
                (true, 0L)
            }
            else {
                val declared =
                    (head(4) & 0xffL) | ((head(5) & 0xffL) << 8) | ((head(6) & 0xffL) << 16) | ((head(7) & 0xffL) << 24)
                (false, declared)
            }
        }
    }

    // The MPDB payload is a fresh stream per processStream call, so
    // each pass re-reads from the start: the header check consumed
    // nothing persistent.
    // None = the payload is not an MPDB envelope (benign-absent);
    // refusals (bad declaration, mismatch, IO) throw and delete the
    // partial scratch.
    def spoolAndParse(blob: PayloadSource, spoolDir: Path): Option[PDBView] = {
        val (magicInvalid, declared) = envelope(blob)
        if (magicInvalid) {
            return None
        }
        if (declared >= 0x80000000L) {
            throw DataFormatException() // refusal BEFORE any write (D-12)
        }
        val scratch = Files.createTempFile(spoolDir, "cilantro-pdb-", ".bin")
        try {
            val out = Files.newOutputStream(scratch, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                blob.processStream { in =>
                    val skip = new Array[Byte](8)
                    var off = 0
                    while (off < 8) {
                        val n = in.read(skip, off, 8 - off)
                        if (n <= 0) {
                            off = 8
                        }
                        else {
                            off += n
                        }
                    }
                    RawInflate.pump(in, out, declared)
                }
            }
            finally {
                out.close()
            }
            val channel = FileChannel.open(scratch, StandardOpenOption.READ)
            try {
                val map = channel.map(FileChannel.MapMode.READ_ONLY, 0, Files.size(scratch))
                map.order(ByteOrder.LITTLE_ENDIAN)
                Some(parseRoot(map, Some(scratch)))
            }
            finally {
                channel.close()
            }
        }
        catch {
            case t: Throwable =>
                try {
                    Files.deleteIfExists(scratch)
                    ()
                }
                catch {
                    case _: java.io.IOException => ()
                }
                throw t
        }
    }

    // ---- map primitives ----------------------------------------------

    private def at(map: ByteBuffer, from: Long, length: Long): Long = {
        if (from < 0 || length < 0 || from + length > map.limit().toLong) {
            throw DataFormatException()
        }
        from
    }

    private def readBytes(map: ByteBuffer, from: Long, length: Long): Array[Byte] = {
        at(map, from, length)
        if (length > Int.MaxValue) {
            throw DataFormatException()
        }
        val out = new Array[Byte](length.toInt)
        val saved = map.position()
        try {
            map.position(from.toInt)
            map.get(out)
        }
        finally {
            map.position(saved)
        }
        out
    }

    private def u16(map: ByteBuffer, atPos: Long): Int = {
        at(map, atPos, 2)
        (map.get(atPos.toInt) & 0xff) | ((map.get(atPos.toInt + 1) & 0xff) << 8)
    }

    private def u32(map: ByteBuffer, atPos: Long): Int = {
        at(map, atPos, 4)
        (map.get(atPos.toInt) & 0xff) | ((map.get(atPos.toInt + 1) & 0xff) << 8) |
            ((map.get(atPos.toInt + 2) & 0xff) << 16) | ((map.get(atPos.toInt + 3) & 0xff) << 24)
    }

    private def u32u(map: ByteBuffer, atPos: Long): Long = u32(map, atPos).toLong & 0xffffffffL

    private def u64(map: ByteBuffer, atPos: Long): Long = {
        at(map, atPos, 8)
        var v = 0L
        var i = 7
        while (i >= 0) {
            v = (v << 8) | (map.get(atPos.toInt + i) & 0xff).toLong
            i -= 1
        }
        v
    }

    // ---- root + tables -----------------------------------------------

    private final case class Streams(
        tables: (Long, Long),
        strings: (Long, Long),
        guids: (Long, Long),
        blobs: (Long, Long)
    )

    // Parses a memory-mapped portable-PDB root (BSJB + streams).
    // scratch = the spool file the map came from when cilantro created
    // it (deleted at view close); None for caller-owned files (the
    // standalone PortablePdbFile path — cilantro never deletes a
    // caller's file).
    private[cilantro] def parseRoot(map: ByteBuffer, scratch: Option[Path]): PDBView = {
        val mapLen = map.limit().toLong
        if (mapLen < 32) {
            throw DataFormatException()
        }
        if (map.get(0) != 'B'.toByte || map.get(1) != 'S'.toByte || map.get(2) != 'J'.toByte || map.get(3) != 'B'.toByte) {
            throw DataFormatException()
        }
        val versionLength = u32u(map, 12)
        var pos = 16L + versionLength
        pos = (pos + 3) & ~3L
        if (pos + 4 > mapLen) {
            throw DataFormatException()
        }
        val streamCount = u16(map, pos + 2)
        pos += 4
        var tables: Option[(Long, Long)] = None
        var strings: Option[(Long, Long)] = None
        var guids: Option[(Long, Long)] = None
        var blobs: Option[(Long, Long)] = None
        var i = 0L
        while (i < streamCount) {
            if (pos + 8 > mapLen) {
                throw DataFormatException()
            }
            val offset = u32u(map, pos)
            val size = u32u(map, pos + 4)
            var namePos = pos + 8
            val nameStart = namePos
            while (namePos < mapLen && map.get(namePos.toInt) != 0) {
                namePos += 1
            }
            if (namePos >= mapLen) {
                throw DataFormatException()
            }
            val name = new String(readBytes(map, nameStart, namePos - nameStart), "UTF-8")
            namePos += 1
            pos = (namePos + 3) & ~3L
            at(map, offset, size)
            name match {
                case "#~" | "#-" => tables = Some((offset, size))
                case "#Strings" => strings = Some((offset, size))
                case "#GUID" => guids = Some((offset, size))
                case "#Blob" => blobs = Some((offset, size))
                case _ => ()
            }
            i += 1
        }
        (tables, strings, guids, blobs) match {
            case (Some(t), Some(s), Some(g), Some(b)) => parseTables(map, t, s, g, b, scratch)
            case _ => throw DataFormatException()
        }
    }

    // Reads only the metadata-root magic: true when the file starts
    // with BSJB (a portable PDB root). Bounded, never throws, no temp
    // files — the standalone classifier's probe.
    private[cilantro] def isBsjbRoot(channel: FileChannel): Boolean = {
        try {
            if (channel.size() < 4) {
                false
            }
            else {
                val head = java.nio.ByteBuffer.allocate(4)
                var off = 0
                while (off < 4) {
                    val n = channel.read(head, off.toLong)
                    if (n <= 0) {
                        off = 4
                    }
                    else {
                        off += n
                    }
                }
                head.flip()
                head.get(0) == 'B'.toByte && head.get(1) == 'S'.toByte && head.get(2) == 'J'.toByte && head.get(3) == 'B'.toByte
            }
        } catch {
            case _: java.io.IOException => false
        }
    }

    private def indexWidth(count: Long): Int = if (count < 65536) 2 else 4

    private def codedWidth(bits: Int, counts: collection.Map[Int, Long], tables: List[Int]): Int = {
        val max = tables.map(t => counts.getOrElse(t, 0L)).max
        if (max < (1L << (16 - bits))) 2 else 4
    }

    private final case class TableLayout(
        counts: collection.Map[Int, Long],
        rowSizeOf: collection.Map[Int, Long],
        rowStartOf: collection.Map[Int, Long],
        strW: Int,
        guidW: Int,
        blobW: Int
    )

    private def parseTables(
        map: ByteBuffer,
        tablesRegion: (Long, Long),
        stringsRegion: (Long, Long),
        guidsRegion: (Long, Long),
        blobsRegion: (Long, Long),
        scratch: Option[Path]
    ): PDBView = {
        val (tablesOff, tablesSize) = tablesRegion
        if (tablesSize < 24) {
            throw DataFormatException()
        }
        val tablesEnd = tablesOff + tablesSize
        val heapSizes = map.get((tablesOff + 6).toInt) & 0xff
        val strW = if ((heapSizes & 0x1) != 0) 4 else 2
        val guidW = if ((heapSizes & 0x2) != 0) 4 else 2
        val blobW = if ((heapSizes & 0x4) != 0) 4 else 2
        val valid = u64(map, tablesOff + 8)
        if ((valid & (1L << documentTable)) == 0 || (valid & (1L << customDebugTable)) == 0) {
            throw DataFormatException()
        }
        val counts = mutable.HashMap[Int, Long]()
        var pos = tablesOff + 24
        var tableId = 0
        while (tableId < 64) {
            if ((valid & (1L << tableId)) != 0) {
                if (pos + 4 > tablesEnd) {
                    throw DataFormatException()
                }
                val count = u32u(map, pos)
                counts.put(tableId, count)
                pos += 4
            }
            tableId += 1
        }
        // Row sizes and row-data starts. All arithmetic is Long; the
        // claimed rows must fit the tables extent or this refuses.
        val rowSizes = mutable.HashMap[Int, Long]()
        val rowStarts = mutable.HashMap[Int, Long]()
        val customCodedTables = List(0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x02, 0x04, 0x06, 0x01, 0x1a, 0x05,
            0x0c, 0x0e, 0x0f, 0x11, 0x14, 0x1c, 0x1d, 0x1e, 0x1f)
        val customParentW = codedWidth(5, counts, customCodedTables)
        var dataPos = pos
        var tid = 0
        while (tid < 64) {
            counts.get(tid).foreach { count =>
                val rowSize = tid match {
                    case 0x30 => blobW.toLong + guidW + blobW + guidW // Document
                    case 0x31 => indexWidth(counts.getOrElse(documentTable, 0L)).toLong + blobW // MethodDebugInformation
                    case 0x32 => 2L + 2 + 2 + 2 + 4 + 4 // LocalScope
                    case 0x33 => 2L + 2 + strW // LocalVariable
                    case 0x34 => strW.toLong + blobW // LocalConstant
                    case 0x35 => 2L + blobW // ImportScope
                    case 0x36 => 2L + 2 // StateMachineMethod
                    case 0x37 => customParentW.toLong + guidW + blobW // CustomDebugInformation
                    case _ => 4L
                }
                if (rowSize <= 0) {
                    throw DataFormatException()
                }
                if (count != 0 && count > (tablesEnd - dataPos) / rowSize) {
                    throw DataFormatException() // rows cannot fit the extent
                }
                rowSizes.put(tid, rowSize)
                rowStarts.put(tid, dataPos)
                if (count > (Long.MaxValue - dataPos) / rowSize) {
                    throw DataFormatException()
                }
                dataPos += count * rowSize
            }
            tid += 1
        }
        val layout = TableLayout(counts.toMap, rowSizes.toMap, rowStarts.toMap, strW, guidW, blobW)
        parseDocuments(map, layout, stringsRegion, guidsRegion, blobsRegion, scratch)
    }

    // A blob-heap entry at a compressed-length index; returns its
    // region (no copy).
    private def blobEntry(map: ByteBuffer, blobsOff: Long, blobsEnd: Long, index: Long): Option[(Long, Long)] = {
        if (index <= 0) {
            return None
        }
        val entryAt = blobsOff + index
        if (entryAt + 1 > blobsEnd) {
            return None
        }
        val first = map.get(entryAt.toInt) & 0xff
        if (first < 0x80) {
            val len = first.toLong
            if (entryAt + 1 + len > blobsEnd) {
                None
            }
            else {
                Some((entryAt + 1, len))
            }
        }
        else {
            if (entryAt + 2 > blobsEnd) {
                return None
            }
            val len = (((first & 0x7f).toLong) << 8) | (map.get(entryAt.toInt + 1) & 0xff).toLong
            if (entryAt + 2 + len > blobsEnd) {
                None
            }
            else {
                Some((entryAt + 2, len))
            }
        }
    }

    private def guidEquals(map: ByteBuffer, guidsOff: Long, guidsEnd: Long, index: Long): Boolean = {
        if (index <= 0) {
            return false
        }
        val base = guidsOff + (index - 1) * 16
        if (base + 16 > guidsEnd) {
            return false
        }
        var i = 0
        while (i < 16) {
            if (map.get((base + i).toInt) != embeddedSourceKind(i)) {
                return false
            }
            i += 1
        }
        true
    }

    // The document name blob: [separator byte][compressed-uint parts],
    // each part a blob-heap index whose entry is a
    // compressed-length-prefixed UTF-8 string; a zero part is an
    // empty path segment.
    private def documentName(map: ByteBuffer, blobsOff: Long, blobsEnd: Long, nameIndex: Long): Option[String] = {
        blobEntry(map, blobsOff, blobsEnd, nameIndex).flatMap { case (atPos, len) =>
            if (len < 1) {
                None
            }
            else {
                val separator = (map.get(atPos.toInt) & 0xff).toChar
                val builder = StringBuilder()
                var p = atPos + 1
                val end = atPos + len
                var keepGoing = true
                var failed = false
                var partIndex = 0
                while (p < end && keepGoing && !failed) {
                    val first = map.get(p.toInt) & 0xff
                    var value = 0L
                    if (first < 0x80) {
                        value = first.toLong
                        p += 1
                    }
                    else {
                        if (p + 1 >= end) {
                            failed = true
                            value = 0
                        }
                        else {
                            value = (((first & 0x7f).toLong) << 8) | (map.get(p.toInt + 1) & 0xff).toLong
                            p += 2
                        }
                    }
                    if (partIndex > 0 && separator != 0) {
                        builder.append(separator)
                    }
                    if (value != 0) {
                        blobEntry(map, blobsOff, blobsEnd, value) match {
                            case Some((sat, slen)) =>
                                if (slen > Int.MaxValue) {
                                    failed = true
                                }
                                else {
                                    builder.append(new String(readBytes(map, sat, slen), "UTF-8"))
                                }
                            case None => failed = true
                        }
                    }
                    partIndex += 1
                }
                if (failed) None else Some(builder.toString)
            }
        }
    }

    private def readIndexAt(map: ByteBuffer, width: Int, atPos: Long): Long = {
        if (width == 4) u32u(map, atPos) else u16(map, atPos).toLong
    }

    private def parseDocuments(
        map: ByteBuffer,
        layout: TableLayout,
        stringsRegion: (Long, Long),
        guidsRegion: (Long, Long),
        blobsRegion: (Long, Long),
        scratch: Option[Path]
    ): PDBView = {
        val documentCount = layout.counts.getOrElse(documentTable, 0L)
        val customCount = layout.counts.getOrElse(customDebugTable, 0L)
        if (documentCount > maxSources || customCount > maxSources) {
            throw DataFormatException()
        }
        val documentStart = layout.rowStartOf.getOrElse(documentTable, throw DataFormatException())
        val documentRowSize = layout.rowSizeOf.getOrElse(documentTable, throw DataFormatException())
        val customStart = layout.rowStartOf.getOrElse(customDebugTable, throw DataFormatException())
        val customRowSize = layout.rowSizeOf.getOrElse(customDebugTable, throw DataFormatException())
        val (guidsOff, guidsEnd) = (guidsRegion._1, guidsRegion._1 + guidsRegion._2)
        val (blobsOff, blobsEnd) = (blobsRegion._1, blobsRegion._1 + blobsRegion._2)
        val customParentW = layout.rowSizeOf
            .get(customDebugTable)
            .map(_ => (layout.rowSizeOf(customDebugTable) - layout.guidW - layout.blobW).toInt)
            .getOrElse(2)

        val sources = Vector.newBuilder[EmbeddedSourceFile]
        var row = 0L
        while (row < customCount) {
            val rowAt = customStart + row * customRowSize
            val parentCoded = readIndexAt(map, customParentW, rowAt)
            val kindIndex = readIndexAt(map, layout.guidW, rowAt + customParentW)
            val valueIndex = readIndexAt(map, layout.blobW, rowAt + customParentW + layout.guidW)
            if (guidEquals(map, guidsOff, guidsEnd, kindIndex)) {
                val parentTag = parentCoded & 0x1f
                val parentRid = parentCoded >>> 5
                if (parentTag == 22 && parentRid >= 1 && parentRid <= documentCount) {
                    val documentRow = documentStart + (parentRid - 1) * documentRowSize
                    val nameIndex = readIndexAt(map, layout.blobW, documentRow)
                    documentName(map, blobsOff, blobsEnd, nameIndex).foreach { name =>
                        blobEntry(map, blobsOff, blobsEnd, valueIndex) match {
                            case Some((vat, vlen)) if vlen >= 8 =>
                                // [format u32][payload]; a positive format
                                // is the DECOMPRESSED length.
                                val format = u32u(map, vat)
                                val restAt = vat + 4
                                val restLen = vlen - 4
                                if (format == 0) {
                                    sources += new EmbeddedSourceFile(name, sliceSource(map, restAt, restLen))
                                }
                                else if (format >= 0x80000000L) {
                                    throw DataFormatException()
                                }
                                else {
                                    sources += new EmbeddedSourceFile(name, deflateSource(map, restAt, restLen, format))
                                }
                            case _ => ()
                        }
                    }
                }
            }
            row += 1
        }
        new PDBView(sources.result(), scratch, Some(map))
    }

    private def sliceSource(map: ByteBuffer, offset: Long, length: Long): PayloadSource = {
        if (offset < 0 || length < 0 || offset + length > map.limit().toLong || length > Int.MaxValue) {
            throw DataFormatException()
        }
        MappedSliceSource(map, offset.toInt, length.toInt)
    }

    private def deflateSource(map: ByteBuffer, offset: Long, length: Long, expected: Long): PayloadSource = {
        val slice = sliceSource(map, offset, length)
        new PayloadSource {
            def processStream[T](f: InputStream => T): T = {
                slice.processStream { in =>
                    val out = new RawInflateInputStream(in, expected)
                    try {
                        f(out)
                    } finally {
                        out.close()
                    }
                }
            }
        }
    }
}
