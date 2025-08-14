//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/Modifiers.cs

package io.spicelabs.cilantro

import io.spicelabs.cilantro.metadata.ElementType
import javax.naming.OperationNotSupportedException

trait ModifierType {
    def modifierType: TypeReference
    def elementType: TypeReference
}

sealed class OptionalModifierType(private var _modifier_type: TypeReference, `type`: TypeReference) extends TypeSpecification(`type`) with ModifierType {
    this.etype = ElementType.cModOpt

    def modifierType = _modifier_type
    def modifierType_=(value: TypeReference) = _modifier_type = value

    override def name = super.name + suffix

    override def fullName = super.fullName + suffix

    private def suffix =
        " modopt(" + modifierType.toString() + ")"
    
    override def isValueType = false
    override def isValueType_=(value: Boolean) = throw OperationNotSupportedException()

    override def isOptionalModifier = true

    override def containsGenericParameter =
        _modifier_type.containsGenericParameter || super.containsGenericParameter
}

sealed class RequiredModifierType(private var _modifier_type: TypeReference, `type`: TypeReference) extends TypeSpecification(`type`) with ModifierType {
    this.etype = ElementType.cModReqD

    def modifierType = _modifier_type
    def modifierType_=(value: TypeReference) = _modifier_type = value

    override def name = super.name + suffix

    override def fullName = super.fullName + suffix

    private def suffix =
        " modreq(" + modifierType.toString() + ")"
    
    override def isValueType = false
    override def isValueType_=(value: Boolean) = throw OperationNotSupportedException()

    override def isRequiredModifier = true

    override def containsGenericParameter =
        _modifier_type.containsGenericParameter || super.containsGenericParameter

}