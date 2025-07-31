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
import io.spicelabs.cilantro.PE.DataDirectory

class BinaryStreamReader(fileInputStream: FileInputStream) {
    private val channel = fileInputStream.getChannel()
    private val byteBuffer = {
        val bb =channel.map(MapMode.READ_ONLY, 0, channel.size)
        bb.order(ByteOrder.LITTLE_ENDIAN)
        bb
    }

    def readByte() = byteBuffer.get()

    def readInt16() = byteBuffer.getShort()
    def readUInt16() = byteBuffer.getShort().toChar
    def readInt32() = byteBuffer.getInt()
    def readBoolean() = byteBuffer.get() != 0
    def readBytes(length: Int) =
        val bytes = Array.ofDim[Byte](length)
        byteBuffer.get(bytes)
        bytes

    def advance(bytes: Int) =
        byteBuffer.position(byteBuffer.position() + bytes)

    def moveTo(position: Int) =
        byteBuffer.position(position)
    
    def align(align: Int) =
        val aa = align - 1
        val pos = byteBuffer.position()
        advance(((pos + aa) & ~aa) - pos)

    def readDataDirectory() =
        DataDirectory(readInt32(), readInt32())
}
