//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/IMemberDefinition.cs

package io.spicelabs.cilantro

trait MemberDefinition extends CustomAttributeProvider {

    def name: String
    def name_=(value: String): Unit

    def fullName: String

    def isSpecialName: Boolean
    def isSpecialName_=(value:Boolean): Unit

    def isRuntimeSpecialName: Boolean
    def isRuntimeSpecialName_=(value: Boolean): Unit

    def declaringType: TypeReference
    def declaringType_=(value: TypeReference): Unit
}

object MemberDefinition {
    def getAttributes(self: Int, attributes: Int): Boolean =
        (self & attributes) != 0
    
    def setAttributes(self: Int, attributes: Int, value: Boolean): Int =
        if (value)
            self | attributes
        self & ~attributes
    
    def getMaskedAttributes(self: Int, mask: Int, attributes: Int): Boolean =
        (self & mask) == attributes
    
    def setMaskedAttributes(self: Int, mask: Int, attributes: Int, value: Boolean): Int =
        if (value)
            (self & ~mask) | attributes
        self & ~(mask & attributes)

    def getAttributes(self: Char, attributes: Char): Boolean =
        (self & attributes) != 0
    
    def setAttributes(self: Char, attributes: Char, value: Boolean): Char =
        if (value)
            self | attributes
        (self & ~attributes).toChar
    
    def getMaskedAttributes(self: Char, mask: Char, attributes: Char): Boolean =
        (self & mask) == attributes
    
    def setMaskedAttributes(self: Char, mask: Char, attributes: Char, value: Boolean): Char =
        if (value)
            (self & ~mask) | attributes
        (self & ~(mask & attributes)).toChar

}
