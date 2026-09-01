// DotnetAssemblyProbe — cheap .NET-assembly classifier (plan
// 2026_09_01, phase-08; ADR-0012).
//
// Goat Rodeo's DotnetDetector used to run the FULL
// AssemblyDefinition.readAssembly (every metadata heap, the whole
// model) just to decide the
// application/x-msdownload; format=pe32-dotnet MIME. This probe
// answers "is this a .NET assembly?" from the PE headers + CLI header
// + metadata-root magic with a hard bound on how many bytes it reads
// (the header region is one bounded read of maxHeaderReadBudget; the
// BSJB/version check is one small positioned read at the resolved
// metadata offset — never the heaps, never the model). The primary
// entry point takes a plain java.io.InputStream (user decision
// 2026-09-01, no path required); File/Path/String overloads open one.
//
// Semantics (ADR-0012): .NET iff
//   1. MZ at offset 0 and a sane e_lfanew;
//   2. PE\0\0 at e_lfanew and a sane optional header (magic
//      0x10b/0x20b, enough data directories);
//   3. the CLI header data directory (index 14) is nonzero and
//      resolves into a section;
//   4. the CLI header's Metadata directory resolves to a raw file
//      offset whose first 4 bytes are BSJB, with a sane (<= 255 byte)
//      runtime-version length.
// Deliberately cheaper than "full read succeeds": a header-valid file
// with a corrupt table heap probes .NET (the full read would fail) —
// GR's walker still runs the full read for extraction. Fail-closed
// everywhere: hostile or truncated input -> false/None.
//
// Stream contract:
//   - FileInputStream: the header region and the metadata chunk are
//     read via absolute channel reads — the caller's stream position
//     is untouched (pinned by D1-07).
//   - Other streams: the region is read sequentially, then the probe
//     skips to the metadata offset (bounded by maxMetadataSkip) and
//     reads the chunk. If the stream supports mark, the position is
//     restored best-effort (mark(Int.MaxValue) / reset); otherwise
//     the position advances by at most
//     maxHeaderReadBudget + maxMetadataSkip + 272 bytes (documented,
//     pinned by D1-14).

package io.spicelabs.cilantro

import java.io.{File, FileInputStream, IOException, InputStream}
import java.nio.ByteBuffer
import java.nio.file.{Path, Paths}
import io.spicelabs.cilantro.PE.DataDirectory
import scala.util.Try

final case class DotnetAssemblyHeader(
    architecture: TargetArchitecture,
    moduleKind: ModuleKind,
    runtimeVersion: String,
    entryPointToken: Int,
    attributes: Int,
    resources: Option[DataDirectory],
    strongName: Option[DataDirectory],
    metadata: Option[DataDirectory],
    metadataFileOffset: Option[Int]
)

object DotnetAssemblyProbe {

    // The header region: every structural field (DOS, PE, COFF,
    // optional header, data directories, section table, CLI header)
    // lives inside the first 64 KiB of real files.
    val maxHeaderReadBudget: Int = 64 * 1024

    // The metadata root may sit far into the first section (Newtonsoft
    // net20: offset 213,012). The probe reaches it with one small read;
    // on a generic (non-FileInputStream) stream it skips sequentially,
    // bounded by this cap.
    val maxMetadataSkip: Int = 64 * 1024 * 1024

    private val metadataChunkSize = 272 // BSJB(4) + verLen(4) + version(<=255) + margin

    def isDotnetAssembly(stream: InputStream): Boolean = dotnetHeader(stream).isDefined
    def isDotnetAssembly(file: File): Boolean = dotnetHeader(file).isDefined
    def isDotnetAssembly(path: Path): Boolean = dotnetHeader(path).isDefined
    def isDotnetAssembly(fileName: String): Boolean = dotnetHeader(fileName).isDefined

    def dotnetHeader(stream: InputStream): Option[DotnetAssemblyHeader] = {
        val marked = stream.markSupported()
        if (marked) {
            stream.mark(Int.MaxValue)
        }
        try {
            Try {
                val region = loadRegion(stream)
                parseRegion(region).flatMap { pending =>
                    metadataChunk(stream, region, pending.metaRawOffset).flatMap { chunk =>
                        finishProbe(chunk, pending)
                    }
                }
            }.toOption.flatten
        } finally {
            if (marked) {
                try {
                    stream.reset()
                } catch {
                    case _: IOException => ()
                }
            }
        }
    }

    def dotnetHeader(file: File): Option[DotnetAssemblyHeader] = {
        Try(scala.util.Using.resource(new FileInputStream(file)) { in => dotnetHeader(in) }).toOption.flatten
    }

    def dotnetHeader(path: Path): Option[DotnetAssemblyHeader] = dotnetHeader(path.toFile)

    def dotnetHeader(fileName: String): Option[DotnetAssemblyHeader] = dotnetHeader(Paths.get(fileName))

    // ---- reading ---------------------------------------------------------

    private def loadRegion(stream: InputStream): Array[Byte] = {
        val region = Array.ofDim[Byte](maxHeaderReadBudget)
        stream match {
            case fis: FileInputStream =>
                // Absolute channel reads leave the caller's position
                // untouched.
                val channel = fis.getChannel
                var off = 0
                var keepGoing = true
                while (keepGoing && off < region.length) {
                    val n = channel.read(ByteBuffer.wrap(region, off, region.length - off), off.toLong)
                    if (n <= 0) {
                        keepGoing = false
                    }
                    else {
                        off += n
                    }
                }
                java.util.Arrays.copyOf(region, off)
            case other =>
                var off = 0
                var keepGoing = true
                while (keepGoing && off < region.length) {
                    val n = other.read(region, off, region.length - off)
                    if (n <= 0) {
                        keepGoing = false
                    }
                    else {
                        off += n
                    }
                }
                java.util.Arrays.copyOf(region, off)
        }
    }

    // Reads the metadata-root chunk (BSJB + version length + version
    // string). When the root lies inside the header region it is sliced
    // from memory; otherwise FileInputStream gets one bounded absolute
    // read, and other streams skip forward (bounded) from where the
    // region read left off.
    private def metadataChunk(stream: InputStream, region: Array[Byte], metaRawOffset: Long): Option[Array[Byte]] = {
        if (metaRawOffset < 0) {
            None
        }
        else if (metaRawOffset + 4 <= region.length) {
            val want = Math.min(metadataChunkSize, region.length - metaRawOffset.toInt)
            Some(java.util.Arrays.copyOfRange(region, metaRawOffset.toInt, metaRawOffset.toInt + want))
        }
        else {
            stream match {
                case fis: FileInputStream =>
                    val channel = fis.getChannel
                    val fileSize = channel.size()
                    if (metaRawOffset + 4 > fileSize) {
                        None
                    }
                    else {
                        val want = Math.min(metadataChunkSize.toLong, fileSize - metaRawOffset).toInt
                        val buf = Array.ofDim[Byte](want)
                        var off = 0
                        var keepGoing = true
                        while (keepGoing && off < want) {
                            val n = channel.read(ByteBuffer.wrap(buf, off, want - off), metaRawOffset + off)
                            if (n <= 0) {
                                keepGoing = false
                            }
                            else {
                                off += n
                            }
                        }
                        Some(java.util.Arrays.copyOf(buf, off))
                    }
                case other =>
                    // The region read consumed the first region.length
                    // bytes of the stream.
                    val skip = metaRawOffset - region.length
                    if (skip > maxMetadataSkip) {
                        None
                    }
                    else {
                        discard(other, skip)
                        val chunk = Array.ofDim[Byte](metadataChunkSize)
                        var off = 0
                        var keepGoing = true
                        while (keepGoing && off < chunk.length) {
                            val n = other.read(chunk, off, chunk.length - off)
                            if (n <= 0) {
                                keepGoing = false
                            }
                            else {
                                off += n
                            }
                        }
                        Some(java.util.Arrays.copyOf(chunk, off))
                    }
            }
        }
    }

    private def discard(stream: InputStream, count: Long): Unit = {
        var remaining = count
        val buffer = new Array[Byte](8192)
        while (remaining > 0) {
            val n = stream.read(buffer, 0, Math.min(buffer.length.toLong, remaining).toInt)
            if (n <= 0) {
                remaining = 0
            }
            else {
                remaining -= n
            }
        }
    }

    // ---- parsing (in-memory, fail-closed) --------------------------------

    private def u16(b: Array[Byte], off: Int): Int = (b(off) & 0xff) | ((b(off + 1) & 0xff) << 8)

    private def u32(b: Array[Byte], off: Int): Long =
        (b(off) & 0xffL) | ((b(off + 1) & 0xffL) << 8) | ((b(off + 2) & 0xffL) << 16) | ((b(off + 3) & 0xffL) << 24)

    // The structural parse result: everything known before the
    // metadata-root check.
    private final case class Pending(
        metaRawOffset: Long,
        machine: Int,
        characteristics: Int,
        subsystem: Int,
        cli: (Long, Long, Int, Int, (Long, Long), (Long, Long))
    )

    private def parseRegion(b: Array[Byte]): Option[Pending] = {
        if (b.length < 64) {
            return None
        }
        if (b(0) != 'M'.toByte || b(1) != 'Z'.toByte) {
            return None
        }
        val peOff = u32(b, 0x3c)
        if (peOff <= 0 || peOff + 24 > b.length) {
            return None
        }
        if (u32(b, peOff.toInt) != 0x00004550L) {
            return None
        }
        val machine = u16(b, peOff.toInt + 4)
        val sectionCount = u16(b, peOff.toInt + 6)
        val optionalSize = u16(b, peOff.toInt + 20)
        val characteristics = u16(b, peOff.toInt + 22)
        val optOff = peOff.toInt + 24
        if (optionalSize < 2 || optOff.toLong + optionalSize.toLong > b.length) {
            return None
        }
        val magic = u16(b, optOff)
        val pe64 = magic == 0x20b
        val pe32 = magic == 0x10b
        if (!pe64 && !pe32) {
            return None
        }
        // Mirror ImageReader.readOptionalHeaders field layout exactly.
        var pos = optOff + 2 // magic
        pos += 2 // linker version
        pos += 44
        pos += 4 // subSystemMajor + subSystemMinor
        pos += 16
        val subsystem = u16(b, pos)
        pos += 2
        pos += 2 // dll characteristics
        pos += (if (pe64) 56 else 40)
        // The advance lands on the Resource directory (index 2); back up
        // so dir(14) is the CLI header directory (ImageReader skips the
        // Export and Import entries inside that advance).
        pos -= 16
        if (pos.toLong + 15 * 8 > b.length) {
            return None
        }
        def dir(index: Int): (Long, Long) = {
            val d = pos + index * 8
            (u32(b, d), u32(b, d + 4))
        }
        val (cliRva, cliSize) = dir(14)
        if (cliRva == 0 && cliSize == 0) {
            return None
        }
        val sectionTable = optOff + optionalSize
        def resolveRva(rva: Long): Option[Int] = {
            var i = 0
            var result: Option[Int] = None
            while (i < sectionCount && result.isEmpty) {
                val s = sectionTable + i * 40
                if (s.toLong + 40 <= b.length) {
                    val virtualAddress = u32(b, s + 12)
                    val sizeOfRawData = u32(b, s + 16)
                    val pointerToRawData = u32(b, s + 20)
                    if (rva >= virtualAddress && rva < virtualAddress + sizeOfRawData) {
                        val raw = rva + pointerToRawData - virtualAddress
                        if (raw >= 0) {
                            result = Some(raw.toInt)
                        }
                    }
                }
                i += 1
            }
            result
        }
        for {
            cliRaw <- resolveRva(cliRva)
            cli <- readCliHeader(b, cliRaw)
            metaRaw <- resolveRva(cli._1)
        } yield {
            Pending(metaRaw.toLong, machine, characteristics, subsystem, cli)
        }
    }

    private def finishProbe(chunk: Array[Byte], pending: Pending): Option[DotnetAssemblyHeader] = {
        // The chunk starts at the metadata root: BSJB(4) + version
        // length(4 at +12) + version string(<= 255 at +16).
        if (chunk.length < 20) {
            return None
        }
        if (chunk(0) != 'B'.toByte || chunk(1) != 'S'.toByte || chunk(2) != 'J'.toByte || chunk(3) != 'B'.toByte) {
            return None
        }
        val versionLength = u32(chunk, 12)
        if (versionLength <= 0 || versionLength > 255 || 16 + versionLength > chunk.length) {
            return None
        }
        // The field is null-terminated (the reader trims at the first
        // null); match that exactly.
        val rawVersion = new String(chunk, 16, versionLength.toInt, "UTF-8")
        val version = {
            val end = rawVersion.indexOf('\u0000')
            if (end >= 0) rawVersion.substring(0, end) else rawVersion
        }
        val cli = pending.cli
        val resources = if (cli._5._1 == 0 && cli._5._2 == 0) None else Some(DataDirectory(cli._5._1.toInt, cli._5._2.toInt))
        val strongName = if (cli._6._1 == 0 && cli._6._2 == 0) None else Some(DataDirectory(cli._6._1.toInt, cli._6._2.toInt))
        Some(
            DotnetAssemblyHeader(
                TargetArchitecture.fromOrdinalValue(pending.machine),
                getModuleKind(pending.characteristics, pending.subsystem),
                version,
                cli._4,
                cli._3,
                resources,
                strongName,
                Some(DataDirectory(cli._1.toInt, cli._2.toInt)),
                if (pending.metaRawOffset <= Int.MaxValue) Some(pending.metaRawOffset.toInt) else None
            )
        )
    }

    // CLI header (40 bytes minimum): Cb(4) Major(2) Minor(2) Metadata(8)
    // Flags(4) EntryPointToken(4) Resources(8) StrongName(8).
    private def readCliHeader(b: Array[Byte], raw: Int): Option[(Long, Long, Int, Int, (Long, Long), (Long, Long))] = {
        if (raw.toLong + 40 > b.length) {
            None
        }
        else {
            val metadata = (u32(b, raw + 8), u32(b, raw + 12))
            val attributes = u32(b, raw + 16).toInt
            val entryPoint = u32(b, raw + 20).toInt
            val resources = (u32(b, raw + 24), u32(b, raw + 28))
            val strongName = (u32(b, raw + 32), u32(b, raw + 36))
            Some((metadata._1, metadata._2, attributes, entryPoint, resources, strongName))
        }
    }

    // Mirrors ImageReader.getModuleKind.
    private def getModuleKind(characteristics: Int, subsystem: Int): ModuleKind = {
        if ((characteristics & 0x2000) != 0) {
            ModuleKind.dll
        }
        else if (subsystem == 0x02 || subsystem == 0x9) {
            ModuleKind.windows
        }
        else {
            ModuleKind.console
        }
    }
}
