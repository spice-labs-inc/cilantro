// Derived from //
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.Cil/VariableDefinition.cs

package io.spicelabs.cilantro.cil

import io.spicelabs.cilantro.TypeReference

sealed class VariableDefinition(variableType: TypeReference) extends VariableReference(variableType) {
    def isPinned = _variable_type.isPinned

    override def resolve(): VariableDefinition = this
}
