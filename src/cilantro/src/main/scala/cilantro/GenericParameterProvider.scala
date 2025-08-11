//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/IGenericParameterProvider.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer

trait GenericParameterProvider extends MetadataTokenProvider {
    def hasGenericParameters: Boolean
    def isDefinition: Boolean
    def module: ModuleDefinition
    def genericParameters: ArrayBuffer[GenericParameter]
    def genericParameterType: GenericParameterType

    def getHasGenericParameters(module: ModuleDefinition) =
        module.hasImage && module.read(this, (provider, reader) => reader.hasGenericParameters(provider))

    def getGenericParameters(collection: ArrayBuffer[GenericParameter], module: ModuleDefinition): ArrayBuffer[GenericParameter] =
        if (module.hasImage)
            module.read(collection, this, (provider, reader) => reader.readGenericParameters(provider))
        else
            GenericParameterCollection(this)        

}

enum GenericParameterType {
    case `type`, method
}

trait GenericContext {
    def isDefinition: Boolean
    def `type`: GenericParameterProvider
    def method: GenericParameterProvider
}
