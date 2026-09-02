// RawInflate — streaming raw (no zlib header) inflation with a
// structural output bound (plan 2026_09_02, phase D; ADR-0014
// D-12/D-13).
//
// The embedded portable PDB root and its deflate-format embedded
// sources are raw-deflate blobs. Under the streaming design nothing
// decompressed is materialized on the heap: the root is pumped into
// the caller's spool file (push mode, RawInflate.pump) and deflate-
// format sources are served on demand (pull mode,
// RawInflateInputStream) — a consumer that stops reading stops the
// work.
//
// The only checks are structural, on the DECLARED uncompressed size:
//   - output can never exceed the declared size (work is bounded even
//     when the deflate stream could keep producing);
//   - a stream that ends before the declared size is met, or whose
//     input ends mid-stream, refuses;
//   - the final output must equal the declared size exactly.
// Push-mode refusals are DataFormatException (the hostile-input
// contract); pull-mode refusals surface to the stream's reader as
// IOException (source content is only read at stream time — the
// documented D-13 divergence). Fixed chunk buffers only — no
// allocation from hostile sizes.

package io.spicelabs.cilantro.PE

import java.io.{IOException, InputStream, OutputStream}
import java.util.zip.{DataFormatException, Inflater}

object RawInflate {

    private val chunkSize = 64 * 1024

    def pump(input: InputStream, out: OutputStream, expected: Long): Long = {
        val inflater = new Inflater(true)
        try {
            val inBuf = new Array[Byte](chunkSize)
            val outBuf = new Array[Byte](chunkSize)
            var total = 0L
            var inputEnded = false
            var done = false
            while (!done) {
                if (inflater.needsInput() && !inputEnded) {
                    val n = input.read(inBuf)
                    if (n < 0) {
                        inputEnded = true
                    }
                    else if (n > 0) {
                        inflater.setInput(inBuf, 0, n)
                    }
                }
                val n = inflater.inflate(outBuf)
                if (n > 0) {
                    total += n
                    if (total > expected) {
                        throw DataFormatException()
                    }
                    out.write(outBuf, 0, n)
                }
                else if (inflater.finished()) {
                    done = true
                }
                else if (inflater.needsDictionary()) {
                    throw DataFormatException()
                }
                else if (inflater.needsInput() && inputEnded) {
                    throw DataFormatException()
                }
                else if (n == 0) {
                    // No progress and nowhere to get input: corrupt.
                    throw DataFormatException()
                }
            }
            if (total != expected) {
                throw DataFormatException()
            }
            total
        }
        finally {
            inflater.end()
        }
    }
}

// Pull-mode raw inflation: an InputStream that serves the inflated
// bytes on demand, bounded by the declared uncompressed size. Violations
// (output past the declared size, premature stream end, input
// exhaustion mid-stream, final mismatch) raise IOException to the
// reader — stream-level refusals, per D-13.
final class RawInflateInputStream private[cilantro] (
    input: InputStream,
    expected: Long
) extends InputStream {

    private val inflater = new Inflater(true)
    private val inBuf = new Array[Byte](64 * 1024)
    private val outBuf = new Array[Byte](64 * 1024)
    private var outPos = 0
    private var outLen = 0
    private var total = 0L
    private var ended = false
    private var closed = false
    private var inputEnded = false

    private def refuse(message: String): IOException = IOException(message)

    // Refill the output buffer from the inflater; sets ended when the
    // verified end is reached. Throws IOException on any violation.
    private def fill(): Unit = {
        if (ended || closed) {
            return ()
        }
        while (outPos >= outLen && !ended) {
            if (inflater.needsInput() && !inputEnded) {
                val n = input.read(inBuf)
                if (n < 0) {
                    inputEnded = true
                }
                else if (n > 0) {
                    inflater.setInput(inBuf, 0, n)
                }
            }
            val n = inflater.inflate(outBuf)
            if (n > 0) {
                total += n
                if (total > expected) {
                    throw refuse("embedded source output exceeds its declared size")
                }
                outPos = 0
                outLen = n
            }
            else if (inflater.finished()) {
                if (total != expected) {
                    throw refuse("embedded source output does not match its declared size")
                }
                ended = true
            }
            else if (inflater.needsDictionary()) {
                throw refuse("embedded source inflate requires a dictionary")
            }
            else if (inflater.needsInput() && inputEnded) {
                throw refuse("embedded source deflate stream ends prematurely")
            }
            else if (n == 0) {
                throw refuse("embedded source inflate made no progress")
            }
        }
    }

    override def read(): Int = {
        if (closed) {
            throw IOException("stream closed")
        }
        if (outPos >= outLen) {
            fill()
        }
        if (ended) {
            -1
        }
        else {
            val b = outBuf(outPos) & 0xff
            outPos += 1
            b
        }
    }

    override def read(b: Array[Byte], off: Int, len: Int): Int = {
        if (closed) {
            throw IOException("stream closed")
        }
        if (off < 0 || len < 0 || len > b.length - off) {
            throw IndexOutOfBoundsException()
        }
        if (len == 0) {
            return 0
        }
        if (outPos >= outLen) {
            fill()
        }
        if (ended) {
            -1
        }
        else {
            val n = Math.min(len, outLen - outPos)
            System.arraycopy(outBuf, outPos, b, off, n)
            outPos += n
            n
        }
    }

    override def close(): Unit = {
        if (!closed) {
            closed = true
            inflater.end()
            input.close()
        }
    }
}
