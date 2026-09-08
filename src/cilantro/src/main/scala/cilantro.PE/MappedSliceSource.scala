// MappedSliceSource — a bounded, zero-copy view over a region of a
// memory-mapped file (plan 2026_09_02, phase B; ADR-0014).
//
// The whole file is mapped once per owning reader/accessor call; each
// payload entry holds a MappedSliceSource over the shared map. The
// source keeps the mapped buffer reachable, so entries returned by an
// accessor stay readable after the accessor returns (and after the
// module's stream is closed — mapped regions remain usable once the
// channel is closed; verified JDK behavior, see the Phase A record).
//
// Semantics:
//   - processStream yields a fresh InputStream over the slice region
//     on every call; the stream reads directly from the mapped bytes
//     with no intermediate allocation (the 1 MiB in-memory rule,
//     D-11) and no byte budget: the only rule is that the region lies
//     inside the file.
//   - The region is validated at construction: a negative length or a
//     region running past the mapped extent is a clean
//     DataFormatException BEFORE any stream exists — no allocation,
//     no partial state. Callers (the accessors, the walk) refuse
//     hostile declarations with exactly this exception.
//   - Lengths are Int (every declared length in the PE/PDB formats is
//     a 32-bit field); offset arithmetic is Long until the final Int
//     bounds check so no hostile sum can wrap.

package io.spicelabs.cilantro.PE

import java.io.{IOException, InputStream}
import java.nio.ByteBuffer
import java.util.zip.DataFormatException
import io.spicelabs.cilantro.PayloadSource

final class MappedSliceSource private[cilantro] (
    private val map: ByteBuffer,
    private val offset: Int,
    private val length: Int
) extends PayloadSource {

    // The region is validated by the factory against the map extent.
    // length is never negative here (the factory refuses it); the
    // length == 0 region is legal (an empty payload).

    def processStream[T](f: InputStream => T): T = {
        val view = map.duplicate()
        view.position(offset)
        view.limit(offset + length)
        val in = new BufferSliceInputStream(Some(view))
        try {
            f(in)
        } finally {
            in.close()
        }
    }

    // The exact in-file byte count of this slice (2026_09_04, B-4):
    // the walk's byte-faithful length seam reads it from here.
    def regionLength: Int = length
}

object MappedSliceSource {
    // Bounded factory: DataFormatException for a negative length or a
    // region past the mapped extent — the shared refusal shape of
    // every declared-length check (ADR-0013; phase B CP-3).
    private[cilantro] def apply(map: ByteBuffer, offset: Int, length: Int): MappedSliceSource = {
        if (length < 0 || offset < 0) {
            throw DataFormatException()
        }
        if (offset.toLong + length.toLong > map.limit().toLong) {
            throw DataFormatException()
        }
        new MappedSliceSource(map, offset, length)
    }
}

// A sequential InputStream over a ByteBuffer view. Reads directly
// from the buffer: no copy, no per-read allocation, no mark/reset
// support (each processStream call hands out its own view). Not
// thread-safe — a single stream is read by one consumer. Reads after
// close() refuse with IOException; close is idempotent.
final class BufferSliceInputStream(private var view: Option[ByteBuffer]) extends InputStream {

    private def buffer: ByteBuffer = view.getOrElse(throw IOException("stream closed"))

    override def read(): Int = {
        val v = buffer
        if (!v.hasRemaining) {
            -1
        }
        else {
            v.get() & 0xff
        }
    }

    override def read(b: Array[Byte], off: Int, len: Int): Int = {
        if (off < 0 || len < 0 || len > b.length - off) {
            throw IndexOutOfBoundsException()
        }
        val v = buffer
        if (len == 0) {
            0
        }
        else if (!v.hasRemaining) {
            -1
        }
        else {
            val n = Math.min(len, v.remaining())
            v.get(b, off, n)
            n
        }
    }

    override def skip(n: Long): Long = {
        val v = buffer
        if (n <= 0) {
            0L
        }
        else {
            val skipped = Math.min(n, v.remaining().toLong)
            v.position(v.position() + skipped.toInt)
            skipped
        }
    }

    override def available(): Int = {
        val v = buffer
        v.remaining()
    }

    override def close(): Unit = {
        view = None
    }
}
