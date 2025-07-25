//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.Metadata/GuidHeap.cs

package io.spicelabs.cilantro.metadata

import io.spicelabs.dotnet_support.Guid

class GuidHeap(data: Array[Byte]) extends Heap(data) {
    def read(index: Int) =
        val guid_size = 16
        if (index == 0 || ((index - 1) + guid_size) > data.length)
            Guid.empty // this might not be right
        val buffer = Array.ofDim[Byte](guid_size)
        Array.copy(data, index - 1, buffer, 0, guid_size)
        Guid(buffer)
}
