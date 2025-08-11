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

abstract class MemberReference(var _name : String) extends MetadataTokenProvider {
    def this() = this(null)

    private var declaring_type: TypeReference = null

    var token: MetadataToken = null
    var projection: Any = null

    def name = _name
    def name_=(value: String) = {
        if (isWindowsRuntimeProjection && value != name)
            throw new OperationNotSupportedException()
        _name = value
    }

    def fullName: String

    def declaringType = declaring_type
    def declaringType_=(value: TypeReference) = declaring_type = value

    def metadataToken = token
    def metadataToken_=(value: MetadataToken) = token = value

    def isWindowsRuntimeProjection = projection != null

    def hasImage =
        val module = this.module
        module != null && module.hasImage

    def module: ModuleDefinition = if declaring_type != null then declaring_type.module else null

    def isDefinition = false

    def containsGenericParameter:Boolean = declaring_type != null && declaring_type.containsGenericParameter

    def memberFullName() =
        if (declaring_type == null)
            _name
        else
            declaring_type.fullName + "::" + _name

    def resolve(): MemberDefinition =
        resolveDefinition()
    
    def resolveDefinition() : MemberDefinition

    override def toString(): String = fullName
}
