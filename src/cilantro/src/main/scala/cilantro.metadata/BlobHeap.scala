//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.Metadata/BlobHeap.cs

package io.spicelabs.cilantro.metadata

import io.spicelabs.cilantro.readCompressedInt32

class BlobHeap(data: Array[Byte]) extends Heap(data) {

    def read(index: Int) : Array[Byte] =
        if (index == 0 || index > data.length)
            return Array.emptyByteArray
        var position = index
        val (length, newPosition) = data.readCompressedInt32(position)

        if (length > data.length - newPosition)
            return Array.emptyByteArray
        
        val buffer = Array.ofDim[Byte](length)
        Array.copy(data, newPosition, buffer, 0, length)

        return buffer

    def getView(signature: Int) : (b: Array[Byte], idx: Int, len: Int) =
        if (signature == 0 || signature > data.length)
            return (Array.emptyByteArray, 0, 0)
        val (length, index) = data.readCompressedInt32(signature)
        return (data, index, length)
}
