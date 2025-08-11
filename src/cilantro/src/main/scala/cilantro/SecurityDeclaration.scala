//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/SecurityDeclaration.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable
import javax.naming.OperationNotSupportedException

enum SecurityAction(val value: Char) {
    case request extends SecurityAction(1)
    case demand extends SecurityAction(2)
    case assert extends SecurityAction(3)
    case deny extends SecurityAction(4)
    case permitOnly extends SecurityAction(5)
    case linkDemand extends SecurityAction(6)
    case inheritDemand extends SecurityAction(7)
    case requestMinimum extends SecurityAction(8)
    case requestOptional extends SecurityAction(9)
    case requestRefuse extends SecurityAction(10)
    case preJitGrant extends SecurityAction(11)
    case preJitDeny extends SecurityAction(12)
    case nonCasDemand extends SecurityAction(13)
    case nonCasLinkDemand extends SecurityAction(14)
    case nonCasInheritance extends SecurityAction(15)
}

trait SecurityDeclarationProvider extends MetadataTokenProvider {
    def hasSecurityDeclarations: Boolean
    def securityDeclarations: ArrayBuffer[SecurityDeclaration]
}

sealed class SecurityAttribute(private var _attribute_type: TypeReference) extends CustomAttributeTrait {
    var _fields: ArrayBuffer[CustomAttributeNamedArgument] = null
    var _properties: ArrayBuffer[CustomAttributeNamedArgument] = null

    def attributeType: TypeReference = _attribute_type
    def attributeType_=(value: TypeReference) = _attribute_type = value

    def hasFields = _fields != null && _fields.length > 0

    def fields =
        if (_fields == null)
            _fields = ArrayBuffer[CustomAttributeNamedArgument]()
        _fields
    
    def hasProperties: Boolean = _properties != null && _properties.length > 0
    override def properties: ArrayBuffer[CustomAttributeNamedArgument] =
        if (_properties != null)
            _properties
        else
            _properties = ArrayBuffer[CustomAttributeNamedArgument]()
            _properties

    override def hasConstructorArguments: Boolean = false
    override def constructorArguments: ArrayBuffer[CustomAttributeArgument] =
        throw OperationNotSupportedException()
}

sealed class SecurityDeclaration(private var _action: SecurityAction, private val signature: Int, private var module: ModuleDefinition) {
    private var _blob: Array[Byte] = null
    var _resolved: Boolean = false
    var _security_attributes: ArrayBuffer[SecurityAttribute] = null

    def securityAction = _action
    def securityAction_=(value: SecurityAction) = _action = value

    def hasSecurityAttributes =
        resolve()
        _security_attributes != null && _security_attributes.length > 0

    def securityAttributes =
        resolve()

        if (_security_attributes == null)
            _security_attributes = ArrayBuffer[SecurityAttribute]()
        _security_attributes
    
    def hasImage = module != null && module.hasImage

    def this(action: SecurityAction) =
        this(action, 0, null)
        _resolved = true
    
    def this(action: SecurityAction, blob: Array[Byte]) =
        this(action, 0, null)
        _resolved = false
        _blob = blob
    
    def getBlob(): Array[Byte] =
        if (_blob != null)
            return _blob
        
        if (!hasImage || signature == 0)
            throw new OperationNotSupportedException()
        
        _blob = module.read(_blob, this, (declaration, reader) => reader.readSecurityDeclarationBlob(declaration.signature))
        _blob

    private def resolve(): Unit =
        if (_resolved || !hasImage)
            return ()
        
        module.syncRoot.synchronized {
            if (_resolved)
                return ()
            module.read(this, (declaration, reader) => reader.readSecurityDeclarationSignature(declaration))
        }
}
