//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.Metadata/UserStringHeap.cs

package io.spicelabs.cilantro.metadata

import io.spicelabs.cilantro.readCompressedInt32

class UserStringHeap(data: Array[Byte]) extends StringHeap(data) {
  protected override def readStringAt(index: Int) =
    val (len, start) = data.readCompressedInt32(index)
    val length = len & ~1

    val chars = Array.ofDim[Char](length / 2)

    var j = 0
    for i <- start to start + length by 2 do
        chars(j) = (data(i).toInt | ((data(i + 1).toInt & 0xff) << 8)).toChar
    
    String(chars)
}
