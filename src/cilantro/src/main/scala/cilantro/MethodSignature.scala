//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/IMethodSignature.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer

trait MethodSignature extends MetadataTokenProvider {
    def hasThis: Boolean
    def hasThis_=(value: Boolean): Unit
    
    def explicitThis: Boolean
    def explicitThis_=(value: Boolean): Unit
    
    def callingConvention: MethodCallingConvention
    def callingConvention_=(value: MethodCallingConvention): Unit

    def hasParameters: Boolean
    def parameters: ArrayBuffer[ParameterDefinition]
    def returnType: TypeReference
    def returnType_=(value: TypeReference): Unit
    def methodReturnType: MethodReturnType

    def hasImplicitThis() = this.hasThis && !this.explicitThis

    def methodSignatureFullName(builder: StringBuilder) =
        builder.append("(")
        if (hasParameters)
            for i <- 0 until parameters.length do
                val parameter = parameters(i)
                if (i > 0)
                    builder.append(",")
                if (parameter.parameterType.isSentinel)
                    builder.append("...,")
                builder.append(parameter.parameterType.fullName)
        builder.append(")")
}
