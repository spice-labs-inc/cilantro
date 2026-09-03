//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.PE/BinaryStreamReader.cs

package io.spicelabs.cilantro.PE

import java.io.FileInputStream
import java.nio.channels.FileChannel.MapMode
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import io.spicelabs.cilantro.PE.DataDirectory

class BinaryStreamReader(protected val fileInputStream: FileInputStream) {
    private val channel = fileInputStream.getChannel()
    private val byteBuffer = {
        val bb =channel.map(MapMode.READ_ONLY, 0, channel.size)
        bb.order(ByteOrder.LITTLE_ENDIAN)
        bb
    }

    

    def position = byteBuffer.position()  //fileInputStream.getChannel().position().toInt
    def position_=(value: Int) = byteBuffer.position(value) //fileInputStream.getChannel().position(value.toLong & 0xffffffff)

    def length = fileInputStream.getChannel().size().toInt

    def readByte() = byteBuffer.get()

    def readInt16() = byteBuffer.getShort()
    def readUInt16() = byteBuffer.getShort().toChar
    def readInt32() = byteBuffer.getInt()
    def readInt64() = byteBuffer.getLong()
    def readBoolean() = byteBuffer.get() != 0
    // H1 (ADR-0013): hostile lengths must fail BEFORE the allocation.
    // The check is the shared guard for every consumer (heaps, debug
    // data, certificates, win32 blobs, managed resources): negative
    // lengths fail instead of NegativeArraySizeException, and lengths
    // past the remaining buffer fail instead of BufferUnderflowException
    // after a multi-GiB Array.ofDim.
    def readBytes(length: Int) = {
        if (length < 0 || length > byteBuffer.remaining()) {
            throw DataFormatException()
        }
        val bytes = Array.ofDim[Byte](length)
        byteBuffer.get(bytes)
        bytes

    }
    def advance(bytes: Int) = {
        byteBuffer.position(byteBuffer.position() + bytes)

    }
    def moveTo(position: Int) = {
        byteBuffer.position(position)
    
    }
    def align(align: Int) = {
        val aa = align - 1
        val pos = byteBuffer.position()
        advance(((pos + aa) & ~aa) - pos)

    }
    def readDataDirectory() = {
        DataDirectory(readInt32(), readInt32())
    }

    // Streaming slice view (plan 2026_09_02, phase B): a bounded,
    // zero-copy PayloadSource over [position, position + length) of
    // the mapped file. The declared length must fit the mapped
    // extent or this refuses with DataFormatException BEFORE any
    // allocation or stream exists (CP-3). The buffer position is not
    // advanced (the slice carries its own offset). Scope: internal
    // seam for the accessors, the walk, and the in-repo test
    // packages; not part of the frozen public surface (CP-7).
    private[cilantro] def payloadSlice(length: Int) = {
        val pos = byteBuffer.position()
        MappedSliceSource(byteBuffer, pos, length)
    }
}
