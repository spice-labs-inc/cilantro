// PortablePdbFile — opening a STANDALONE portable PDB as a container
// of source files (2026-09-04 follow-up; ADR-0014 amendment).
//
// A portable PDB is a file whose bytes ARE the metadata root (BSJB +
// streams) — the same bytes an embedded MPDB envelope holds once
// decompressed. The embedded path (MetadataReader
// .readEmbeddedPortablePdb) is anchored to a type-17 entry inside a
// parsed assembly; this entry point serves PDB bytes that arrive on
// their own (a .pdb artifact next to an assembly in a package).
//
//   PortablePdbFile.open(file | path): Try[Option[PDBView]]
//     None     = not a portable PDB (fail-closed: native MSF PDBs, PE
//                files, junk, truncated — never throws);
//     Failure  = hostile structure (the parser's clean refusals);
//     Some     = the view; PDBView.sources are the source files
//                (name + streamed text), exactly as the embedded
//                path yields them.
//   PortablePdbFile.isPortablePdb(...): Boolean — the bounded BSJB
//     probe (reads 4 bytes, never throws, no temp files).
//
// Because the file is already inflated, nothing is decompressed and
// nothing is spooled: the file is memory-mapped directly and the
// shared PDB parser (PortablePdbSpool.parseRoot) walks the tables
// from the map. The caller's file is never created, modified, or
// deleted by cilantro; the view must outlive the reads (the same
// immutable-file contract as the assembly walk), and files above the
// 2^31-1 map ceiling refuse cleanly (the D-12 representational rule).

package io.spicelabs.cilantro

import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.util.zip.DataFormatException
import scala.util.Try

object PortablePdbFile {

    def isPortablePdb(file: File): Boolean = isPortablePdb(file.toPath)
    def isPortablePdb(path: Path): Boolean = isPortablePdb(path.toString)
    def isPortablePdb(fileName: String): Boolean = {
        val r = Try {
            val channel = FileChannel.open(java.nio.file.Paths.get(fileName), java.nio.file.StandardOpenOption.READ)
            try {
                PortablePdbSpool.isBsjbRoot(channel)
            } finally {
                channel.close()
            }
        }
        r.getOrElse(false)
    }

    def open(file: File): Try[Option[PDBView]] = open(file.toPath)

    def open(path: Path): Try[Option[PDBView]] = {
        Try {
            val channel = FileChannel.open(path, java.nio.file.StandardOpenOption.READ)
            try {
                val size = channel.size()
                if (!PortablePdbSpool.isBsjbRoot(channel)) {
                    None
                }
                else if (size > Int.MaxValue) {
                    throw DataFormatException()
                }
                else {
                    val map = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                    map.order(ByteOrder.LITTLE_ENDIAN)
                    Some(PortablePdbSpool.parseRoot(map, None))
                }
            } finally {
                channel.close()
            }
        }
    }
}
