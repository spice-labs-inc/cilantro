
//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/ParameterDefinitionCollection.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer
import io.spicelabs.cilantro.metadata.Table

class ParameterDefinitionCollection(val _method: MethodSignature, capacity:Int = 0) extends ArrayBuffer[ParameterDefinition]() {
    override def addOne(elem: ParameterDefinition): this.type =
        val retval = super.addOne(elem)
        elem._method = _method
        elem._index = this.length - 1
        this
    
    override def insert(index: Int, elem: ParameterDefinition): Unit =
        super.insert(index, elem)
        elem._method = _method
        elem._index = index
        for i <- index + 1 until length do
            this(i)._index = i
    
    override def update(index: Int, elem: ParameterDefinition): Unit =
        super.update(index, elem)
        elem._method = _method
        elem._index = index
    
    override def remove(index: Int): ParameterDefinition =
        val elem = super.remove(index)
        elem._method = null
        elem._index = -1

        for i <- index until length do
            this(i)._index = i
        elem

}
