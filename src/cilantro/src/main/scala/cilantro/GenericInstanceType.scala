//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/FunctionPointerType.cs

package io.spicelabs.cilantro

import javax.naming.OperationNotSupportedException
import scala.collection.mutable.ArrayBuffer
import io.spicelabs.cilantro.metadata.ElementType

class GenericInstanceType(_type: TypeReference, arity: Int = 0) extends TypeSpecification(_type) with GenericInstance with GenericContext {
    private var _arguments = if arity > 0 then new ArrayBuffer[TypeReference](arity) else null
    this.etype = ElementType.genericInst

    def hasGenericArguments = _arguments != null && _arguments.length > 0

    def genericArguments =
        if (_arguments != null)
            _arguments
        else
            _arguments = ArrayBuffer[TypeReference]()
            _arguments
    
    override def declaringType = elementType.declaringType
    override def declaringType_=(value: TypeReference) = throw OperationNotSupportedException()

    override def fullName =
        val name = StringBuilder()
        name.append(super.fullName)
        this.genericInstanceFullName(name)
        name.toString()
    
    override def isGenericInstance = true

    override def containsGenericParameter: Boolean = this.containsGenericParameterFn() || super.containsGenericParameter
    
    override def `type`: GenericParameterProvider = elementType
}
