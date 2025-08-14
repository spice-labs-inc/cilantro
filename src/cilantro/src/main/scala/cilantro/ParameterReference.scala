//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/ParameterReference.cs

package io.spicelabs.cilantro

abstract class ParameterReference(var _name: String, protected var _parameter_type: TypeReference) extends MetadataTokenProvider {
    var _index = -1
    var _token: MetadataToken = null

    def name = _name
    def name_=(value: String) = _name = name

    def index = _index

    def parameterType = _parameter_type
    def parameterType_=(value: TypeReference) = _parameter_type = value

    def metadataToken = _token
    def metadataToken_=(value: MetadataToken) = _token = value

    override def toString() = _name

    def resolve(): ParameterDefinition
}
