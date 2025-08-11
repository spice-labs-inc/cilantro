//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/PropertyReference.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer

abstract class PropertyReference(name: String, private var _property_type: TypeReference) extends MemberReference(name) {

    def propertyType = _property_type
    def propertyType_=(value: TypeReference) = _property_type = value

    def parameters: ArrayBuffer[ParameterDefinition]

    override def resolveDefinition(): MemberDefinition =
        this.resolve()
    
    def resolve(): MemberDefinition
}
