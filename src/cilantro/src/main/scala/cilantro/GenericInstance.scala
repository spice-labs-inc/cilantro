//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/GenericInstanceType.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer

trait GenericInstance extends MetadataTokenProvider {
    def hasGenericArguments: Boolean
    def genericArguments: ArrayBuffer[TypeReference]

    def containsGenericParameterFn(): Boolean =
        genericArguments.find((gp) => gp.containsGenericParameter).isDefined
    
    def genericInstanceFullName(builder: StringBuilder) =
        builder.append("<")
        for i <- 0 until genericArguments.length do
            if (i > 0)
                builder.append(",")
            builder.append(genericArguments(i).fullName)
        builder.append(">")
  
}
