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

class FieldReference(name: String, _fieldType: TypeReference, _declaringType: TypeReference = null) extends MemberReference(name) {
    private var _field_type = _fieldType
    token = MetadataToken(TokenType.memberRef)
    declaringType = _declaringType

    def fieldType = _field_type
    def fieldType_=(value: TypeReference) = _field_type = value

    override def fullName = _field_type.fullName + " " + memberFullName()

    override def containsGenericParameter =
        _field_type.containsGenericParameter || super.containsGenericParameter
    
    override def resolveDefinition() = this.resolve()

    override def resolve(): MemberDefinition =
        var module = this.module
        if (module == null)
            throw OperationNotSupportedException()
        module.resolve(this).asInstanceOf[TypeDefinition]
  
    def this() =
        this(null, null, null)
}
