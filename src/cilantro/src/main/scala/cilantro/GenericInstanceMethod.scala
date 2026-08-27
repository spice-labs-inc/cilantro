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
    private var _arguments: Option[ArrayBuffer[TypeReference]] = if arity > 0 then Some(ArrayBuffer[TypeReference]()) else None

    def hasGenericArguments = _arguments.exists(_.length > 0)

    def genericArguments = {
        _arguments match {
            case Some(a) => a
            case None =>
                val a = ArrayBuffer[TypeReference]()
                _arguments = Some(a)
                a
        }
    
    }
    override def isGenericInstance = true

    override def method: Option[GenericParameterProvider] = Some(elementMethod)

    override def `type`: Option[GenericParameterProvider] = elementMethod.declaringType

    override def containsGenericParameter = {
        this.containsGenericParameterFn() || super.containsGenericParameter
    
    }
    override def fullName = {
        val signature = StringBuilder()
        val method = elementMethod
        signature.append(method.returnType.fullName)
            .append(" ")
            .append(method.declaringType.map(_.fullName).getOrElse(""))
            .append("::")
            .append(method.name)
        genericInstanceFullName(signature)
        methodSignatureFullName(signature)
        signature.toString()
    }
}
