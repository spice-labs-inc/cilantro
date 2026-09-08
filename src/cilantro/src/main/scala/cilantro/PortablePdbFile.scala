// PortablePdbFile — reading a portable PDB from a standalone file
// (2026-09-04; ADR-0014 amendment B-8/B-11).
//
// A portable PDB is a file whose bytes ARE the metadata root (BSJB +
// streams) — the same bytes an embedded MPDB envelope holds once
// decompressed. PDB reading (which may include decompression) is 100%
// separate from AssemblyEntry and from the Assembly: the decompressed
// portable PDB is self-contained (no back-references require an
// assembly). This entry point serves PDB bytes that arrive on their
// own (a .pdb artifact next to an assembly).
//
// The reader is callback-owned and envelope-detecting:
//
//   PortablePdbFile.withPdb[T](file, spoolDir)(f: Try[Option[PDBView]] => T):
//       Try[Option[T]]
//     f is ALWAYS invoked exactly once with the outcome:
//       Success(Some(view)) = a portable PDB was opened. BSJB roots
//                             map directly (no spool, no
//                             decompression); MPDB envelopes are
//                             decompressed into the caller's spool
//                             directory (D-6/D-12/D-13 guards: magic,
//                             declared >= 2^31 refuses before any
//                             write, output never exceeds declared,
//                             exact size verified). The view is live
//                             only inside f.
//       Success(None)        = not a portable PDB in either envelope
//                             (native MSF PDBs, PE files, junk,
//                             truncated) — never throws.
//       Failure(e)           = hostile refusal (MPDB without a spool
//                             dir, bad declaration, mismatch, IO,
//                             > 2^31-1 file) — never silent.
//     Cleanup is automatic when f returns or throws: the map is
//     released, cilantro's scratch is deleted, the caller's file and
//     spool DIRECTORY are never created, modified, or deleted.
//     f's exceptions propagate (the outer Try[Option[T]] passes f's
//     value back).
//
//   PortablePdbFile.isPortablePdb(...): Boolean — the bounded 4-byte
//     probe (BSJB root or MPDB envelope), never throws.

package io.spicelabs.cilantro

import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path}
import java.util.zip.DataFormatException
import scala.util.Try
import io.spicelabs.cilantro.PE.MappedSliceSource

object PortablePdbFile {

    def isPortablePdb(file: File): Boolean = isPortablePdb(file.toPath)
    def isPortablePdb(path: Path): Boolean = isPortablePdb(path.toString)
    def isPortablePdb(fileName: String): Boolean = {
        Try {
            val channel = FileChannel.open(java.nio.file.Paths.get(fileName), java.nio.file.StandardOpenOption.READ)
            try {
                isPortableMagic(magic(channel))
            } finally {
                channel.close()
            }
        }.getOrElse(false)
    }

    def withPdb[T](file: File, spoolDir: Option[Path])(f: Try[Option[PDBView]] => T): Try[Option[T]] =
        withPdb(file.toPath, spoolDir)(f)

    def withPdb[T](path: Path, spoolDir: Option[Path])(f: Try[Option[PDBView]] => T): Try[Option[T]] = {
        Try {
            val outcome = Try(openLogic(path, spoolDir))
            val view = outcome.toOption.flatten
            try {
                Some(f(outcome))
            } finally {
                view.foreach(_.closeNow())
            }
        }
    }

    // ---- internals ---------------------------------------------------

    private def isPortableMagic(bytes: Array[Byte]): Boolean = {
        bytes.length == 4 &&
        ((bytes(0) == 'B'.toByte && bytes(1) == 'S'.toByte && bytes(2) == 'J'.toByte && bytes(3) == 'B'.toByte) ||
         (bytes(0) == 'M'.toByte && bytes(1) == 'P'.toByte && bytes(2) == 'D'.toByte && bytes(3) == 'B'.toByte))
    }

    // Reads the first 4 bytes (fewer if the file is shorter). Never
    // throws.
    private def magic(channel: FileChannel): Array[Byte] = {
        try {
            if (channel.size() < 4) {
                Array.emptyByteArray
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
                val out = new Array[Byte](4)
                head.get(out)
                out
            }
        } catch {
            case _: java.io.IOException => Array.emptyByteArray
        }
    }

    private def openLogic(path: Path, spoolDir: Option[Path]): Option[PDBView] = {
        val channel = FileChannel.open(path, java.nio.file.StandardOpenOption.READ)
        try {
            val size = channel.size()
            val head = magic(channel)
            if (!isPortableMagic(head)) {
                None
            }
            else if (head(0) == 'B'.toByte) {
                // A plain BSJB root: the file IS the tables. Map it
                // directly — no decompression, no spool.
                if (size > Int.MaxValue) {
                    throw DataFormatException()
                }
                val map = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                map.order(ByteOrder.LITTLE_ENDIAN)
                Some(PortablePdbSpool.parseRoot(map, None))
            }
            else {
                // An MPDB envelope: decompression required; it goes to
                // a scratch file the CALLER's spool directory provides.
                spoolDir match {
                    case None => throw DataFormatException()
                    case Some(dir) =>
                        if (!Files.isDirectory(dir)) {
                            throw DataFormatException()
                        }
                        if (size > Int.MaxValue) {
                            throw DataFormatException()
                        }
                        val map = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                        map.order(ByteOrder.LITTLE_ENDIAN)
                        val blob = MappedSliceSource(map, 0, size.toInt)
                        PortablePdbSpool.spoolAndParse(blob, dir)
                }
            }
        } finally {
            channel.close()
        }
    }
}
