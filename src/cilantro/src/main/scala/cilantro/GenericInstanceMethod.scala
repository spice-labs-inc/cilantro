//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/GenericInstanceMethod.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer

class GenericInstanceMethod(_method: MethodReference, arity: Int = 0) extends MethodSpecification(_method) with GenericInstance with GenericContext {
    private var _arguments = if arity > 0 then new ArrayBuffer[TypeReference](arity) else null

    def hasGenericArguments = _arguments != null && _arguments.length > 0

    def genericArguments =
        if (_arguments == null)
            _arguments = ArrayBuffer[TypeReference]()
        _arguments
    
    override def isGenericInstance = true

    override def method = elementMethod

    override def `type` = elementMethod.declaringType

    override def containsGenericParameter =
        this.containsGenericParameterFn() || super.containsGenericParameter
    
    override def fullName =
        val signature = StringBuilder()
        val method = elementMethod
        signature.append(method.returnType.fullName)
            .append(" ")
            .append(method.declaringType.fullName)
            .append("::")
            .append(method.name)
        genericInstanceFullName(signature)
        methodSignatureFullName(signature)
        signature.toString()
}
