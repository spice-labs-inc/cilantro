//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/IConstantProvider.cs

package io.spicelabs.cilantro


trait ConstantProvider extends MetadataTokenProvider {
    def hasConstant: Boolean
    def hasConstant_=(value: Boolean): Unit

    def constant: Any
    def constant_=(value: Any): Unit

    def resolveConstant(constant: Any, module: ModuleDefinition): Any =
        if (module == null)
            ConstantProvider.noValue
        else
            module.syncRoot.synchronized {
                if (constant != ConstantProvider.notResolved)
                    return constant
                return ConstantProvider.noValue
                // TODO
                // if (module.hasImage)
                //     module.read(this, (provider, reader) => reader.readConstant(provider))
                // else
                //     ConstantProvider.noValue
            }
}

object ConstantProvider {
    val noValue = Object()
    val notResolved = Object()
}
