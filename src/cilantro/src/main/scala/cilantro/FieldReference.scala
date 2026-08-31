//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/FieldReference.cs

package io.spicelabs.cilantro

import javax.naming.OperationNotSupportedException

class FieldReference(name: String, _fieldType: TypeReference, _declaringType: Option[TypeReference] = None) extends MemberReference(name) {
    private var _field_type = _fieldType
    token = Some(MetadataToken(TokenType.memberRef))
    _declaringType.foreach(declaringType = _)

    def fieldType = _field_type
    def fieldType_=(value: TypeReference) = _field_type = value

    override def fullName = _field_type.fullName + " " + memberFullName()

    override def containsGenericParameter = {
        _field_type.containsGenericParameter || super.containsGenericParameter
    
    }
    override def resolveDefinition() = this.resolve()

    override def resolve(): MemberDefinition = {
        this.module match {
            case Some(module) => module.resolve(this).asInstanceOf[TypeDefinition]
            case None => throw OperationNotSupportedException()
        }
  
    }
    def this() = {
        this("", TypeReference("", ""), None)
    }
}
