//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/TypeDefinitionCollection.cs

package io.spicelabs.cilantro

import scala.collection.mutable.HashMap
import io.spicelabs.cilantro.metadata.Row2
import scala.collection.mutable.ArrayBuffer

sealed class TypeDefinitionCollection(val container: ModuleDefinition, capacity: Int = 0) extends ArrayBuffer[TypeDefinition](capacity) {
    val name_cache = HashMap[Row2[String, String], TypeDefinition]()

    override def addOne(elem: TypeDefinition): this.type =
        super.addOne(elem)
        attach(elem)
        this
    
    override def update(index: Int, elem: TypeDefinition): Unit =
        super.update(index, elem)
        attach(elem)
    
    override def insert(index: Int, elem: TypeDefinition): Unit =
        super.insert(index, elem)
        attach(elem)
    
    override def remove(index: Int): TypeDefinition =
        val result = super.remove(index)
        detach(result)
        result
    
    override def clear(): Unit =
        this.foreach(detach)
        super.clear()
    
    private def attach(`type`: TypeDefinition) =
        if (`type`.module != null && `type`.module != container)
            throw IllegalArgumentException("Type already attached")
        `type`._module = container
        `type`.scope = container
        name_cache += (Row2(`type`.nameSpace, `type`.name) -> `type`)
    
    private def detach(`type`: TypeDefinition) =
        `type`._module = null
        `type`.scope = null
        name_cache -= Row2(`type`.nameSpace,`type`.name)

    def getType(fullName: String): TypeDefinition =
        null // TODO
    
    def getType(namespace: String, name: String): TypeDefinition =
        null // TODO
}
