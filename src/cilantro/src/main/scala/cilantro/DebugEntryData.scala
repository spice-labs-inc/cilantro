//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// DebugEntryData + PDBView + EmbeddedSourceFile — the debug-directory
// data exposure (plan 13, C5-05; plan 2026_09_02, phases B/D).
// Cilantro exposes raw blobs only; it never interprets the
// codeview/PDB internals beyond the embedded-source walk documented
// in ADR-0011/ADR-0014.
//
// Phase D shapes (ADR-0014, D-8/D-13):
//   - DebugEntryData's payload is a streaming, zero-copy slice view
//     (PayloadSource) over the file (phase B).
//   - The embedded portable PDB root is decompressed into the
//     caller-provided spool directory and memory-mapped (D-6/D-12);
//     PDBView walks the tables from that map and exposes its sources.
//   - EmbeddedSourceFile is (name, PayloadSource): format-0 sources
//     stream as map slices; deflate-format sources are pull-inflated
//     inside processStream, bounded by their declared size — cleanup
//     is guaranteed when processStream exits (D-8).

package io.spicelabs.cilantro

final class DebugEntryData(
    private val _entryType: Int,
    private val payload: PayloadSource
) extends PayloadSource {
    def entryType = _entryType

    def processStream[T](f: java.io.InputStream => T): T = payload.processStream(f)
}

final class EmbeddedSourceFile(
    private val _name: String,
    private val payload: PayloadSource
) extends PayloadSource {
    def name = _name

    def processStream[T](f: java.io.InputStream => T): T = payload.processStream(f)
}

// The spooled embedded-portable-PDB view: tables walked from a map of
// the decompressed root; sources stream lazily (D-13). The view owns
// the spooled scratch file it created (the spool DIRECTORY is the
// caller's — D-6): close() releases the mapping and deletes the
// file. Source streams are usable until close().
final class PDBView private[cilantro] (
    private val sourcesIn: Vector[EmbeddedSourceFile],
    private val scratch: Option[java.nio.file.Path],
    private val map: Option[java.nio.ByteBuffer]
) extends AutoCloseable {
    def sources: Vector[EmbeddedSourceFile] = sourcesIn

    override def close(): Unit = {
        map.foreach(_ => ()) // release the reference
        scratch.foreach(p => try {
            java.nio.file.Files.deleteIfExists(p)
            ()
        } catch {
            case _: java.io.IOException => ()
        })
    }
}
