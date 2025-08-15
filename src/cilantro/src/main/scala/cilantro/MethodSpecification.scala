//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/MethodSpecification.cs

package io.spicelabs.cilantro

import javax.naming.OperationNotSupportedException

abstract class MethodSpecification(val _method: MethodReference) extends MethodReference {

    def elementMethod = _method

    override def name = _method.name
    override def name_=(value: String) = OperationNotSupportedException()

    override def callingConvention = _method.callingConvention
    override def callingConvention_=(value: MethodCallingConvention) = throw OperationNotSupportedException()

    override def hasThis: Boolean = _method.hasThis
    override def hasThis_=(value: Boolean) = throw OperationNotSupportedException()

    override def explicitThis: Boolean = _method.explicitThis
    override def explicitThis_=(value: Boolean): Unit = throw OperationNotSupportedException()

    override def methodReturnType = _method.methodReturnType
    override def methodReturnType_=(value: MethodReturnType) = throw OperationNotSupportedException()

    override def declaringType: TypeReference = _method.declaringType
    override def declaringType_=(value: TypeReference): Unit = throw OperationNotSupportedException()

    override def hasParameters: Boolean = _method.hasParameters

    override def parameters = _method.parameters

    override def containsGenericParameter: Boolean = _method.containsGenericParameter

    override def getElementMethod(): MethodReference = _method.getElementMethod()
}
