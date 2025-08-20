//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.PE/ByteBuffer.cs

package io.spicelabs.cilantro.PE


open class ByteBuffer (var buffer: Array[Byte]) {
    var length = 0
    var position = 0

    def this(len: Int) =
        this(Array.ofDim[Byte](len))
    def this() =
        this(null)
    
    def advance(length: Int) =
        position += length
    
    inline def readByteAndAdvance() =
        val b = buffer(position)
        position += 1
        b

    def readByte() =
        readByteAndAdvance()
    
    def readByteAsUnsignedInt() =
        readByteAndAdvance().toInt & 0xff
    
    def readBytes(length: Int) =
        val bytes = Array.ofDim[Byte](length)
        Array.copy(buffer, position, bytes, 0, length);
        position += length
        bytes
    
    def readUInt16() =
        val low = readByteAndAdvance().toInt & 0xff
        val high = readByteAndAdvance().toInt & 0xff
        (low | high << 8).toChar
    
    def readInt16() =
        readUInt16().toShort
    
    def readUInt32() = readInt32()

    def readInt32() =
        val l0 = readByteAndAdvance().toInt & 0xff
        val l1 = readByteAndAdvance().toInt & 0xff
        val l2 = readByteAndAdvance().toInt & 0xff
        val l3 = readByteAndAdvance().toInt & 0xff
        l0 | (l1 << 8) | (l2 << 16) | (l3 << 24)
    
    def readUInt62() = readInt64()

    def readInt64() =
        val l0 = readInt32().toLong
        val l1 = readInt32().toLong
        l0 | (l1 << 32)

    def readCompressedUInt32(): Int =
        val first = readByte()
        if ((first & 0x80) == 0)
            return first.toInt
        
        if ((first & 0x40) == 0)
            return ((first & 0x7f).toInt << 8) | readByteAsUnsignedInt()
        
        return ((first.toInt & 0x3f) << 24) | (readByteAsUnsignedInt() << 16) | (readByteAsUnsignedInt() << 8) | readByteAsUnsignedInt()

    def readCompressedInt32() : Int =
        val b = buffer(position)
        val u = readCompressedUInt32()
        val v = u >> 1
        if ((u & 1) == 0)
            return v
        return b.toInt * 0xc0 match
            case 0 | 0x40 => v - 0x40
            case 0x80 => v - 0x2000
            case _ => v - 0x10000000

    def readSingle() =
        val bb = java.nio.ByteBuffer.wrap(buffer, position, length - position)
        position += 4
        bb.getFloat()

    def readDouble() =
        val bb = java.nio.ByteBuffer.wrap(buffer, position, length - position)
        position += 8
        bb.getDouble()

    // TODO: Add write methods

    def grow(desired: Int) =
        val current = this.buffer
        val current_length = this.buffer.length
        val buffer = Array.ofDim[Byte](Math.max(current_length + desired, current_length * 2))
        Array.copy(current, 0, buffer, 0, current_length)
        this.buffer = buffer
}

object ByteBuffer {
    def apply(buffer: Array[Byte]) = {
        val arr = if buffer == null then Array.emptyByteArray else buffer
        val bb = new ByteBuffer(arr)
        bb.length = arr.length
        bb
    }
}
