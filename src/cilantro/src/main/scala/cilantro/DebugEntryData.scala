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
// data exposure (plan 13, C5-05; plans 2026_09_02/2026_09_04).
// Cilantro exposes raw blobs only; it never interprets the
// codeview/PDB internals beyond the embedded-source walk documented
// in ADR-0011/ADR-0014.
//
// Phase D shapes (ADR-0014, D-8/D-13) + the 2026_09_04 byte-faithful
// amendment (B-8/B-11):
//   - DebugEntryData's payload is a streaming, zero-copy slice view
//     (PayloadSource) over the file; its exact in-file length is
//     available through the internal payloadByteLength seam.
//   - PDB reading is a callback-owned capability entirely separate
//     from the walk: PortablePdbFile.withPdb and
//     MetadataReader.withEmbeddedPdb hand f the open/parse outcome
//     (Try[Option[PDBView]]); the view is live only inside f; when f
//     returns or throws, cleanup is automatic (map released,
//     cilantro's scratch deleted, the caller's spool directory never
//     touched). PDBView has no public close() — liveness is
//     session-gated like walk entries; retained sources refuse
//     cleanly after the owning call.
//   - EmbeddedSourceFile is (name, PayloadSource): format-0 sources
//     stream as map slices; deflate-format sources are pull-inflated
//     inside processStream, bounded by their declared size.

package io.spicelabs.cilantro


final class DebugEntryData(
    private val _entryType: Int,
    private val payload: PayloadSource
) extends PayloadSource {
    def entryType = _entryType

    def processStream[T](f: java.io.InputStream => T): T = payload.processStream(f)

    // Internal byte-faithful length (2026_09_04, B-4): the exact
    // in-file byte count (sizeOfData) of the data this entry
    // delivers.
    private[cilantro] def payloadByteLength: Long = PayloadBytes.lengthOf(payload)
}

final class EmbeddedSourceFile(
    private val _name: String,
    private val payload: PayloadSource
) extends PayloadSource {
    def name = _name

    def processStream[T](f: java.io.InputStream => T): T = payload.processStream(f)
}

// The portable-PDB view: sources walked from a map of the root bytes.
// The view is callback-owned (withPdb / withEmbeddedPdb): it is live
// only inside the callback and refuses afterwards (session gate on
// every source). The scratch file is cilantro's own creation inside
// the caller's spool DIRECTORY (which cilantro never creates or
// deletes — D-6); cleanup deletes the scratch and releases the map.
final class PDBView private[cilantro] (
    rawSources: Vector[EmbeddedSourceFile],
    private val scratch: Option[java.nio.file.Path],
    private val map: Option[Any]
) {
    private val session = new AssemblyWalkSession(
        "portable pdb view ended; source no longer usable"
    )

    // Session-gated sources: usable inside the owning call only.
    val sources: Vector[EmbeddedSourceFile] = rawSources.map { source =>
        new EmbeddedSourceFile(source.name, new PayloadSource {
            def processStream[T](f: java.io.InputStream => T): T = {
                session.ensureOpen()
                source.processStream(f)
            }
        })
    }

    private[cilantro] def closeNow(): Unit = {
        session.close()
        map.foreach(_ => ()) // release the reference
        scratch.foreach(p => try {
            java.nio.file.Files.deleteIfExists(p)
            ()
        } catch {
            case _: java.io.IOException => ()
        })
    }
}
