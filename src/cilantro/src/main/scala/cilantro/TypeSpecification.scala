//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/TypeSpecification.cs

package io.spicelabs.cilantro

import javax.naming.OperationNotSupportedException

abstract class TypeSpecification(val _element_type: TypeReference) extends TypeReference(null, null) {
    this.token = MetadataToken(TokenType.typeSpec)
    
    def elementType = _element_type

    override def name = _element_type.name
    override def name_=(value: String) = throw OperationNotSupportedException()
    
    override def nameSpace = _element_type.nameSpace
    override def nameSpace_=(value: String) = throw OperationNotSupportedException()

    override def scope = _element_type.scope
    override def scope_=(value: MetadataScope) = throw OperationNotSupportedException()

    override def module = _element_type.module

    override def fullName: String = _element_type.fullName

    override def containsGenericParameter: Boolean = _element_type.containsGenericParameter

    override def metadataType: MetadataType = _element_type.metadataType

    override def getElementType(): TypeReference = _element_type.getElementType()
}
