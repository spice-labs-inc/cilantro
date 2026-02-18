//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/Resource.cs

package io.spicelabs.cilantro

import io.spicelabs.cilantro.MemberDefinition.getMaskedAttributes
import io.spicelabs.cilantro.MemberDefinition.setMaskedAttributes

enum ResourceType {
    case linked, embedded, assemblyLinked
}

abstract class Resource(private var _name: String, private var _attributes: Int) {

    def name:String = _name
    def name_=(value: String) = _name = value

    def attributes:Int = _attributes
    def attribute_=(value: Int) = _attributes = value

    def resourceType: ResourceType

    def isPublic:Boolean = getMaskedAttributes(_attributes, ManifestResourceAttributes.visibilityMask.value, ManifestResourceAttributes.public.value)
    def isPublic_=(value: Boolean) = {
        _attributes = setMaskedAttributes(_attributes, ManifestResourceAttributes.visibilityMask.value, ManifestResourceAttributes.public.value, value)
    }

    def isPrivate:Boolean = getMaskedAttributes(_attributes, ManifestResourceAttributes.visibilityMask.value, ManifestResourceAttributes.`private`.value)
    def isPrivate_=(value: Boolean) = {
        _attributes = setMaskedAttributes(_attributes, ManifestResourceAttributes.visibilityMask.value, ManifestResourceAttributes.`private`.value, value)
    }


}
