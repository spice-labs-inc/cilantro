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

import io.spicelabs.cilantro.metadata.ElementType
import javax.naming.OperationNotSupportedException

sealed class FunctionPointerType extends TypeSpecification(null) with MethodSignature {
    private val _function = MethodReference()
    _function.name = "method"
    this.etype = ElementType.fnPtr

    def hasThis = _function.hasThis
    def hasThis_=(value: Boolean) = _function.hasThis = value

    def explicitThis = _function.explicitThis
    def explicitThis_=(value: Boolean) = _function.explicitThis = value

    def callingConvention = _function.callingConvention
    def callingConvention_=(value: MethodCallingConvention) = _function.callingConvention = value

    def hasParameters = _function.hasParameters

    def parameters = _function.parameters

    def returnType = _function.methodReturnType.returnType
    def returnType_=(value: TypeReference) = _function.methodReturnType.returnType = value

    def methodReturnType = _function.methodReturnType
    def methodReturnType_=(value: MethodReturnType) = _function.methodReturnType = value

    override def name = _function.name
    override def name_=(value: String): Unit = throw OperationNotSupportedException()

    override def nameSpace = ""
    override def nameSpace_=(value: String) = throw OperationNotSupportedException()

    override def module = returnType.module

    override def scope = _function.returnType.scope
    override def scope_=(value: MetadataScope) = throw OperationNotSupportedException()

    override def containsGenericParameter = _function.containsGenericParameter

    override def fullName =
        val signature = StringBuilder()
        signature.append(_function.name)
        signature.append(" ")
        signature.append(_function.returnType.fullName)
        signature.append(" *")
        methodSignatureFullName(signature)
        signature.toString()
}
