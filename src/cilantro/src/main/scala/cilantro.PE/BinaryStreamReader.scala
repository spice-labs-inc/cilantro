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

import java.io.{FileInputStream, IOException}
import java.nio.channels.FileChannel.MapMode
import java.nio.{ByteBuffer => NioByteBuffer, ByteOrder}
import java.nio.file.{Path, Paths}

/**
 * SECURITY INVARIANT: ResourceHandle
 * Abstracts over file and buffer sources to enable position-independent reader creation.
 * NOT THREAD-SAFE - callers must synchronize concurrent access.
 */
private[PE] sealed trait ResourceHandle {
    /** Creates a new BinaryStreamReader positioned at the start of the resource */
    def createReader(): BinaryStreamReader
    /** Returns the length of the resource in bytes */
    def length: Long
    /** Closes any underlying resources */
    def close(): Unit
}

/**
 * File-based ResourceHandle that memory-maps the file.
 * Each call to createReader() creates a new memory-mapped view.
 */
private[PE] class FileResourceHandle(file: FileInputStream) extends ResourceHandle {
    private val channel = file.getChannel()

    def createReader(): BinaryStreamReader = {
        val buffer = channel.map(MapMode.READ_ONLY, 0, channel.size())
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        new BinaryStreamReader(buffer, Some(this))
    }

    def length: Long = channel.size()

    def close(): Unit = {
        try {
            channel.close()
        } finally {
            file.close()
        }
    }
}

/**
 * Buffer-based ResourceHandle that slices the buffer for each reader.
 * Each call to createReader() creates an independent slice.
 */
private[PE] class BufferResourceHandle(buffer: NioByteBuffer) extends ResourceHandle {
    def createReader(): BinaryStreamReader = {
        // Create a slice to isolate position changes
        val slice = buffer.slice()
        slice.order(ByteOrder.LITTLE_ENDIAN)
        new BinaryStreamReader(slice, Some(this))
    }

    def length: Long = buffer.limit().toLong

    def close(): Unit = {
        // Nothing to close for a ByteBuffer
    }
}

/**
 * BinaryStreamReader provides little-endian binary data reading from a ByteBuffer.
 *
 * SECURITY INVARIANTS:
 * - Max buffer size: 100MB default, configurable
 * - Max read operation: 100MB for readBytes()
 * - Position bounds: 0 <= position <= buffer.limit()
 * - Integer overflow: Math.addExact on all arithmetic
 * - Alignment validation: Power of 2 only
 * - Defensive copying: Default for fromByteArray
 *
 * LIMITATION: Due to java.nio.ByteBuffer using Int for position/limit,
 * BinaryStreamReader is limited to 2GB maximum buffer size.
 */
class BinaryStreamReader private[PE] (
    private[PE] val buffer: NioByteBuffer,
    private val resourceHandle: Option[ResourceHandle] = None
) {
    import BinaryStreamReader._

    // Validate buffer is not null
    if (buffer == null) {
        throw new IllegalArgumentException("Buffer cannot be null")
    }

    /** Returns the current position in the buffer */
    def position: Int = buffer.position()

    /**
     * SECURITY INVARIANT: Position bounds
     * Sets the position with validation: 0 <= value <= buffer.limit()
     */
    def position_=(value: Int): Unit = {
        if (value < 0 || value > buffer.limit()) {
            throw new IllegalArgumentException(
                s"Position $value out of bounds [0, ${buffer.limit()}]"
            )
        }
        buffer.position(value)
    }

    /** Returns the length (limit) of the buffer */
    def length: Int = buffer.limit()

    /** Reads a signed byte */
    def readByte(): Byte = buffer.get()

    /** Reads a little-endian signed short (16-bit) */
    def readInt16(): Short = buffer.getShort()

    /** Reads a little-endian unsigned short (16-bit) as Char */
    def readUInt16(): Char = buffer.getShort().toChar

    /** Reads a little-endian signed int (32-bit) */
    def readInt32(): Int = buffer.getInt()

    /** Reads a little-endian signed long (64-bit) */
    def readInt64(): Long = buffer.getLong()

    /** Reads a boolean (non-zero = true) */
    def readBoolean(): Boolean = buffer.get() != 0

    /**
     * SECURITY INVARIANT: Max read operation size
     * Reads a byte array with allocation protection.
     * Rejects lengths exceeding MAX_READ_SIZE before allocation.
     */
    def readBytes(length: Int): Array[Byte] = {
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative")
        }
        if (length > MAX_READ_SIZE) {
            throw new IllegalArgumentException(
                s"Read size $length exceeds maximum $MAX_READ_SIZE"
            )
        }
        if (length > buffer.remaining()) {
            throw new java.nio.BufferUnderflowException()
        }
        val bytes = Array.ofDim[Byte](length)
        buffer.get(bytes)
        bytes
    }

    /**
     * SECURITY INVARIANT: Integer overflow
     * Advances the position by the specified number of bytes.
     * Uses Math.addExact to prevent integer overflow.
     */
    def advance(bytes: Int): Unit = {
        val newPos = Math.addExact(buffer.position(), bytes)
        position = newPos
    }

    /** Moves to an absolute position */
    def moveTo(position: Int): Unit = {
        this.position = position
    }

    /**
     * SECURITY INVARIANT: Alignment validation + Integer overflow
     * Aligns the current position to the specified boundary.
     * Alignment must be a positive power of 2.
     */
    def align(alignment: Int): Unit = {
        if (alignment <= 0) {
            throw new IllegalArgumentException("Alignment must be positive")
        }
        if ((alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("Alignment must be power of 2")
        }
        val aa = alignment - 1
        val pos = buffer.position()
        val newPos = Math.addExact(pos, aa) & ~aa  // Check overflow
        position = newPos
    }

    /** Reads a DataDirectory (8 bytes: VirtualAddress + Size) */
    def readDataDirectory(): DataDirectory =
        DataDirectory(readInt32(), readInt32())
}

object BinaryStreamReader {
    /** Default maximum buffer size: 100MB */
    val DEFAULT_MAX_BUFFER_SIZE = 100L * 1024 * 1024

    /** Maximum read size for readBytes(): 100MB */
    val MAX_READ_SIZE = 100 * 1024 * 1024

    /**
     * SECURITY INVARIANT: Size validation
     * Creates a BinaryStreamReader from a ByteBuffer.
     *
     * Buffer is sliced to isolate position/limit changes.
     * Order is set to LITTLE_ENDIAN; original buffer position is unaffected.
     * Size limit enforced on BOTH capacity and limit.
     *
     * @param buffer the buffer to read from
     * @param maxSize maximum allowed buffer size (default 100MB)
     * @throws IllegalArgumentException if buffer capacity or limit exceeds maxSize
     * @throws IllegalArgumentException if buffer is null
     */
    def fromByteBuffer(
        buffer: NioByteBuffer,
        maxSize: Long = DEFAULT_MAX_BUFFER_SIZE
    ): BinaryStreamReader = {
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer cannot be null")
        }
        // SECURITY: Check BOTH capacity and limit for security
        if (buffer.capacity() > maxSize || buffer.limit() > maxSize) {
            throw new IllegalArgumentException(
                s"Buffer size ${buffer.capacity()}/${buffer.limit()} exceeds maximum $maxSize"
            )
        }
        // Defensive slice: isolates position/limit changes from original buffer
        val slice = buffer.slice()
        slice.order(ByteOrder.LITTLE_ENDIAN)
        new BinaryStreamReader(slice)
    }

    /**
     * SECURITY INVARIANT: Defensive copying
     * Creates a BinaryStreamReader from a byte array.
     *
     * By default (copy=true), creates a defensive copy to prevent TOCTOU attacks.
     * Use copy=false for performance when caller guarantees immutability.
     *
     * @param array the byte array to wrap
     * @param copy if true (default), creates defensive copy
     */
    def fromByteArray(array: Array[Byte], copy: Boolean = true): BinaryStreamReader = {
        val data = if (copy) array.clone() else array
        fromByteBuffer(NioByteBuffer.wrap(data))
    }

    /**
     * Creates a BinaryStreamReader from a FileInputStream via memory-mapped I/O.
     * Backward compatible with existing file-based entry points.
     *
     * @param stream the FileInputStream to read from
     */
    def fromFileInputStream(stream: FileInputStream): BinaryStreamReader = {
        if (stream == null) {
            throw new IllegalArgumentException("Stream cannot be null")
        }
        val handle = new FileResourceHandle(stream)
        handle.createReader()
    }

    /**
     * Creates a BinaryStreamReader from a file path via memory-mapped I/O.
     *
     * @param path the file path to read from
     */
    def fromFile(path: String): BinaryStreamReader = {
        fromFileInputStream(new FileInputStream(path))
    }

    /**
     * Creates a BinaryStreamReader from a file Path via memory-mapped I/O.
     *
     * @param path the file Path to read from
     */
    def fromFile(path: Path): BinaryStreamReader = {
        fromFileInputStream(new FileInputStream(path.toFile))
    }
}
