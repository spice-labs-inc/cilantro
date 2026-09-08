// PayloadSource — the uniform streaming payload contract (plan
// 2026_09_02, phase B; ADR-0014, decision D-8).
//
// Every payload cilantro exposes — a certificate blob, a win32
// resource leaf, a debug blob, a managed resource, a class's
// canonical bytes, an embedded source — is delivered through
// processStream, never as a whole Array[Byte]:
//
//   - f receives a fresh, independent InputStream positioned at the
//     payload's start. Calling processStream again yields another
//     independent stream from the start.
//   - Cleanup (closing the stream, releasing map references, deleting
//     cilantro-created partial scratch) is guaranteed when
//     processStream exits, whether f returns or throws.
//   - f's exceptions propagate untouched.
//   - The 1 MiB in-memory rule (D-11): cilantro never holds more than
//     1 MiB of any single payload in memory at once; all internal
//     reads are chunked with fixed buffers. Zero-copy slice payloads
//     hold nothing at all between read() calls.

package io.spicelabs.cilantro

import java.io.InputStream
import java.util.zip.DataFormatException
import io.spicelabs.cilantro.PE.MappedSliceSource

trait PayloadSource {
    def processStream[T](f: InputStream => T): T
}

// Internal byte-faithful length seam (2026_09_04, B-4/B-11). Entry
// lengths are CAPTURED at slice/entry construction and never
// re-derived from the file. The walk's file-backed payloads are all
// MappedSliceSources built from a validated declared length; the
// exact length lives on the slice. Deliberately NOT on the
// PayloadSource trait: the PDB reader's deflate wrapper's exact
// length is unknowable before inflation, so a trait-level length
// could lie.
private[cilantro] object PayloadBytes {
    def lengthOf(p: PayloadSource): Long = p match {
        case m: MappedSliceSource => m.regionLength.toLong
        case _ => throw DataFormatException()
    }
}
