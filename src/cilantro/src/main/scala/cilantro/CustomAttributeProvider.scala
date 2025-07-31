//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/ICustomAttributeProvider.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ListBuffer

trait CustomAttributeProvider {
    def customAttributes: ListBuffer[CustomAttribute]

    def getHasCustomAttributes(module: ModuleDefinition) =
        module.hasImage && module.read(this, (provider, reader) => reader.hasCustomAttributes(provider))

    def getCustomAttributes(variable: ListBuffer[CustomAttribute], module: ModuleDefinition) =
        if (module.hasImage)
            module.read(variable, this, (provider, reader) => reader.readCustomAttributes(provider))
        ListBuffer.empty[CustomAttribute]
}
