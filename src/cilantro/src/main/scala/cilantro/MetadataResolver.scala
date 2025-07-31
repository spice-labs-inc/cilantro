//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/MetadataResolver.cs

package io.spicelabs.cilantro

trait AssemblyResolver extends AutoCloseable {
    def resolve(name: AssemblyNameReference): AssemblyDefinition
    def resolve(name: AssemblyNameReference, parameters: ReaderParameters): AssemblyDefinition
}

class ResolutionException extends  Exception {
    
}

trait MetadataResolverTrait { // TODO
}
