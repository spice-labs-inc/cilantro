//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/ModuleReference.cs

package io.spicelabs.cilantro

class ModuleReference(private var _name: String = null, var _token: MetadataToken = MetadataToken(TokenType.moduleRef)) extends MetadataScope {
    def name = _name
    def name_(value: String) = _name = value

    def metadataScopeType = MetadataScopeType.moduleReference

    override def metadataToken = _token
    override def metadataToken_(value: MetadataToken) = _token = value

    override def toString(): String = _name
}
