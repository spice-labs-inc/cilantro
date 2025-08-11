//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/MemberDefinitionCollection.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer

class MemberDefinitionCollection[T <: MemberDefinition](container: TypeDefinition, capacity: Int = 0) extends ArrayBuffer[T](capacity) {

    override def addOne(elem: T): this.type =
        val result = super.addOne(elem)
        attach(elem)
        this
    
    override def update(index: Int, elem: T): Unit =
        super.update(index, elem)
        attach(elem)
    
    override def insert(index: Int, elem: T): Unit =
        super.insert(index, elem)
        attach(elem)
    
    override def remove(index: Int): T =
        val elem = super.remove(index)
        detach(elem)
        elem
    
    override def clear(): Unit =
        this.foreach(detach)
        super.clear()
    
    private def attach(element: T) =
        if (element.declaringType == container)
            ()
        else if (element.declaringType != null)
            throw IllegalArgumentException("member already attached")
        else
            element.declaringType = container
    
    private def detach(element: T) =
        element.declaringType = null
}
