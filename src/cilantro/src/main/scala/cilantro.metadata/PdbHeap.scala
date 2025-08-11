//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.Metadata/PdbHeap.cs

package io.spicelabs.cilantro.metadata

class PdbHeap(_data: Array[Byte]) extends Heap(_data)
{
    var id: Array[Byte] = Array.emptyByteArray
    var entryPoint: Int = 0
    var typeSystemTables: Long = 0
    var typeSystemTableRows: Array[Int] = Array.emptyIntArray

    def hasTable (table: Table) =
        (typeSystemTables & (1L << table.value)) != 0
}

