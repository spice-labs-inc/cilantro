//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.Metadata/StringHeap.cs

package io.spicelabs.cilantro.metadata
import scala.collection.mutable.Map
import java.nio.charset.StandardCharsets


class StringHeap(data: Array[Byte]) extends Heap(data)
{
    val strings = Map[Int, String]()

    def read(index: Int) : String =
        if (index == 0)
            return ""
        val result = strings.get(index)
        if (result.isDefined)
            return result.get
        
        val str = readStringAt(index)
        if (str.length() != 0)
            strings.addOne((index, str))
        return str
    
    protected def readStringAt(index: Int) : String =
        var length = 0
        for
            i <- index to data.length
            if (data(i) != 0)
        do
            length = length + 1

        return String(data, index, length, StandardCharsets.UTF_8)
}

