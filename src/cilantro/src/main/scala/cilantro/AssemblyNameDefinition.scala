//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from 

package io.spicelabs.cilantro

class AssemblyNameDefinition(name: String, version: CSVersion) extends AssemblyNameReference(name, version, MetadataToken(TokenType.assembly, 1)) {

    override def hash: Array[Byte] = Array.emptyByteArray

    def this() =
        this(null, null)
}
