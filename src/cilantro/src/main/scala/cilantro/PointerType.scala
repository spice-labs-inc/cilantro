//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/PointerType.cs

package io.spicelabs.cilantro

import javax.naming.OperationNotSupportedException
import io.spicelabs.cilantro.metadata.ElementType

sealed class PointerType(`type`: TypeReference) extends TypeSpecification(`type`) {
    this.etype = ElementType.ptr
    override def name = super.name + "*"

    override def fullName = super.fullName + "*"

    override def isValueType = false
    override def isValueType_=(value: Boolean) = throw OperationNotSupportedException()

    override def isPointer = true
}
