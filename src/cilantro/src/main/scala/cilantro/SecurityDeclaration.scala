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
import scala.util.boundary
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
    var _fields: Option[ArrayBuffer[CustomAttributeNamedArgument]] = None
    var _properties: Option[ArrayBuffer[CustomAttributeNamedArgument]] = None

    def attributeType: Option[TypeReference] = Some(_attribute_type)
    def attributeType_=(value: TypeReference) = _attribute_type = value

    def hasFields = _fields.exists(_.length > 0)

    def fields = {
        _fields match {
            case Some(f) => f
            case None =>
                val f = ArrayBuffer[CustomAttributeNamedArgument]()
                _fields = Some(f)
                f
        }
    
    }
    def hasProperties: Boolean = _properties.exists(_.length > 0)
    override def properties: ArrayBuffer[CustomAttributeNamedArgument] = {
        _properties match {
            case Some(p) => p
            case None =>
                val p = ArrayBuffer[CustomAttributeNamedArgument]()
                _properties = Some(p)
                p

        }
    }
    override def hasConstructorArguments: Boolean = false
    override def constructorArguments: ArrayBuffer[CustomAttributeArgument] = {
        throw OperationNotSupportedException()
    }
}

sealed class SecurityDeclaration(private var _action: SecurityAction, private val signature: Int, private var module: Option[ModuleDefinition]) {
    private var _blob: Option[Array[Byte]] = None
    var _resolved: Boolean = false
    var _security_attributes: Option[ArrayBuffer[SecurityAttribute]] = None

    def securityAction = _action
    def securityAction_=(value: SecurityAction) = _action = value

    def hasSecurityAttributes = {
        resolve()
        _security_attributes.exists(_.length > 0)

    }
    def securityAttributes = {
        resolve()

        _security_attributes match {
            case Some(a) => a
            case None =>
                val a = ArrayBuffer[SecurityAttribute]()
                _security_attributes = Some(a)
                a
        }
    
    }
    def hasImage = module.exists(_.hasImage)

    def this(action: SecurityAction) = {
        this(action, 0, None)
        _resolved = true
    
    }
    def this(action: SecurityAction, blob: Array[Byte]) = {
        this(action, 0, None)
        _resolved = false
        _blob = Some(blob)
    
    }
    def getBlob(): Array[Byte] = {
        _blob match {
            case Some(b) => return b
            case None => ()
        }
        if (!hasImage || signature == 0) {
            throw new OperationNotSupportedException()
        
        }
        module.foreach { m =>
            _blob = Some(m.read(this, (declaration, reader) => reader.readSecurityDeclarationBlob(declaration.signature)))
        }
        _blob.getOrElse(Array.emptyByteArray)

    }
    private def resolve(): Unit = {
        if (_resolved || !hasImage) {
            return ()
        
        }
        boundary {
            module.foreach { m =>
                m.syncRoot.synchronized {
                    if (_resolved) {
                        boundary.break()
                    }
                    m.read(this, (declaration, reader) => reader.readSecurityDeclarationSignature(declaration))
                }
            }
        }
    }
}

extension(sdp: SecurityDeclarationProvider) {
    def getHasSecurityDeclarations(module: Option[ModuleDefinition]): Boolean = {
        module.exists(m => m.hasImage && m.read(sdp, (provider, reader) => reader.hasSecurityDeclarations(provider)))
    }

    def getSecurityDeclarations(variable: ArrayBuffer[SecurityDeclaration], module: Option[ModuleDefinition]): ArrayBuffer[SecurityDeclaration] = {
        module match {
            case Some(m) if m.hasImage =>
                m.read(variable, sdp, (provider, reader) => reader.readSecurityDeclarations(provider))
            case _ => variable
        }
    }
}
