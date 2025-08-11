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
import io.spicelabs.cilantro.AssemblyNameReference.getAttributes

sealed class EventDefinition(name: String, attrs: Char, _eventType: TypeReference) extends EventReference(name, _eventType), MemberDefinition {
    this.token = MetadataToken(TokenType.event)
    private var _attributes:Char = attrs

    var _custom_attributes: ArrayBuffer[CustomAttribute] = null

    var _add_method: MethodDefinition = null
    var _invoke_method: MethodDefinition = null
    var _remove_method: MethodDefinition = null
    var _other_methods: ArrayBuffer[MethodDefinition] = null

    def attributes = _attributes
    def attributes_=(value: Char) = _attributes = value

    def addMethod =
        if (_add_method != null)
            _add_method
        else
            initializeMethods()
            _add_method
    def addMethod_=(value: MethodDefinition) = _add_method = value

    def invokeMethod =
        if (_invoke_method != null)
            _invoke_method
        else
            initializeMethods()
            _invoke_method
    def invokeMethod_=(value: MethodDefinition) = _invoke_method = value

    def removeMethod =
        if (_remove_method != null)
            _remove_method
        else
            initializeMethods()
            _remove_method
    def removeMethod_=(value: MethodDefinition) = _remove_method = value

    def hasOtherMethods =
        if (_other_methods != null)
            _other_methods.length > 0
        else
            initializeMethods()
            _other_methods != null && _other_methods.length > 0
    
    def otherMethods =
        if (_other_methods != null)
            _other_methods
        else
            initializeMethods()
            if (_other_methods == null)
                _other_methods = ArrayBuffer[MethodDefinition]()
                _other_methods
    
    def hasCustomAttributes =
        if (_custom_attributes != null)
            _custom_attributes.length > 0
        else
            this.getHasCustomAttributes(module)
    
    def customAttributes =
        if (_custom_attributes != null)
            _custom_attributes
        else
            _custom_attributes = getCustomAttributes(_custom_attributes, module)
            _custom_attributes
    
    def isSpecialName:Boolean = false // TODO
    def isSpecialName_=(value: Boolean) = { }

    def isRuntimeSpecialName:Boolean = false // TODO
    def isRuntimeSpecialName_=(value: Boolean) = { }

    override def isDefinition = true

    def initializeMethods(): Unit =
        var module = this.module
        if (module == null)
            return ()

        module.syncRoot.synchronized {
            if (_add_method != null || _invoke_method != null || _remove_method != null)
                return ()
            
            if (!module.hasImage)
                return()

            // TODO            
            // module.read(this, (event, reader) => reader.readMethods(event))
        }

    override def resolve() = this
}
