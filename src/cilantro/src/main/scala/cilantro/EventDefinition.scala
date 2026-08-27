//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/EventDefinition.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer

sealed class EventDefinition(name: String, attrs: Char, _eventType: TypeReference) extends EventReference(name, _eventType), MemberDefinition {
    this.token = Some(MetadataToken(TokenType.event))
    private var _attributes:Char = attrs

    var _custom_attributes: Option[ArrayBuffer[CustomAttribute]] = None

    var _add_method: Option[MethodDefinition] = None
    var _invoke_method: Option[MethodDefinition] = None
    var _remove_method: Option[MethodDefinition] = None
    var _other_methods: Option[ArrayBuffer[MethodDefinition]] = None

    def attributes = _attributes
    def attributes_=(value: Char) = _attributes = value

    def addMethod: Option[MethodDefinition] = {
        _add_method match {
            case Some(m) => Some(m)
            case None =>
                initializeMethods()
                _add_method
        }
    }
    def addMethod_=(value: MethodDefinition) = _add_method = Some(value)

    def invokeMethod: Option[MethodDefinition] = {
        _invoke_method match {
            case Some(m) => Some(m)
            case None =>
                initializeMethods()
                _invoke_method
        }
    }
    def invokeMethod_=(value: MethodDefinition) = _invoke_method = Some(value)

    def removeMethod: Option[MethodDefinition] = {
        _remove_method match {
            case Some(m) => Some(m)
            case None =>
                initializeMethods()
                _remove_method
        }
    }
    def removeMethod_=(value: MethodDefinition) = _remove_method = Some(value)

    def hasOtherMethods = {
        _other_methods match {
            case Some(m) => m.length > 0
            case None =>
                initializeMethods()
                _other_methods.exists(_.length > 0)
    
        }
    }
    def otherMethods: ArrayBuffer[MethodDefinition] = {
        _other_methods match {
            case Some(m) => m
            case None =>
                initializeMethods()
                val loaded = ArrayBuffer[MethodDefinition]()
                _other_methods = Some(loaded)
                loaded
        }
    }
    def hasCustomAttributes = {
        _custom_attributes match {
            case Some(a) => a.length > 0
            case None => this.getHasCustomAttributes(module)
    
        }
    }
    def customAttributes = {
        _custom_attributes match {
            case Some(a) => a
            case None =>
                val loaded = getCustomAttributes(ArrayBuffer.empty[CustomAttribute], module)
                _custom_attributes = Some(loaded)
                loaded
    
        }
    }
    def isSpecialName:Boolean = false // TODO
    def isSpecialName_=(value: Boolean) = { }

    def isRuntimeSpecialName:Boolean = false // TODO
    def isRuntimeSpecialName_=(value: Boolean) = { }

    override def isDefinition = true

    def initializeMethods(): Unit = {
        this.module match {
        case None => return ()
        case Some(module) =>
        module.syncRoot.synchronized {
            if (_add_method.isDefined || _invoke_method.isDefined || _remove_method.isDefined) {
                return ()
            
            }
            if (!module.hasImage) {
                return()

            // TODO            
            // module.read(this, (event, reader) => reader.readMethods(event))
            }
        }
        }

    }
    override def resolve() = this
}
