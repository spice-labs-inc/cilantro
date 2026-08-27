//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/MemberReference.cs

package io.spicelabs.cilantro

import javax.naming.OperationNotSupportedException

abstract class MemberReference(var _name: String) extends MetadataTokenProvider {
    def this() = this("")

    private var declaring_type: Option[TypeReference] = None

    var token: Option[MetadataToken] = None
    var projection: Option[Any] = None

    def name: String = _name
    def name_=(value: String) = {
        if (isWindowsRuntimeProjection && value != name) {
            throw new OperationNotSupportedException()
        }
        _name = value
    }

    def fullName: String

    def declaringType: Option[TypeReference] = declaring_type
    def declaringType_=(value: TypeReference) = declaring_type = Some(value)
    def declaringType_=(value: Option[TypeReference]) = declaring_type = value

    def metadataToken = token
    def metadataToken_=(value: MetadataToken) = token = Some(value)

    def isWindowsRuntimeProjection = projection.isDefined

    def hasImage = {
        val module = this.module
        module.exists(_.hasImage)

    }
    def module: Option[ModuleDefinition] = declaring_type.flatMap(_.module)

    def isDefinition = false

    def containsGenericParameter: Boolean = declaring_type.exists(_.containsGenericParameter)

    def memberFullName() = {
        declaring_type match {
            case Some(t) => t.fullName + "::" + _name
            case None => _name
        }
    }
    def resolve(): MemberDefinition = {
        resolveDefinition()

    }
    def resolveDefinition(): MemberDefinition

    override def toString(): String = fullName
}
