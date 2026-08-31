//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/PropertyDefinition.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer
import io.spicelabs.cilantro.PropertyDefinition.mirrorParameters

sealed class PropertyDefinition(name: String, private var _attributes: Char, propertyType: TypeReference) extends PropertyReference(name, propertyType) with MemberDefinition with ConstantProvider {
    private var _has_this: Option[Boolean] = None

    private var _custom_attributes: Option[ArrayBuffer[CustomAttribute]] = None

    var _get_method: Option[MethodDefinition] = None
    var _set_method: Option[MethodDefinition] = None
    var _other_methods: Option[ArrayBuffer[MethodDefinition]] = None

    var _constant:Any = ConstantProvider.notResolved

    def attributes = _attributes
    def attributes_=(value: Char) = _attributes = value

    def hasThis = {
        _has_this match {
            case Some(has) => has
            case None =>
                false // TODO
        }
    }
    def hasThis_(value: Boolean) = {
        _has_this = Some(value)
    
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
    def getMethod: Option[MethodDefinition] = {
        _get_method match {
            case Some(m) => Some(m)
            case None =>
                initializeMethods()
                _get_method
        }
    }
    def getMethod_=(value: MethodDefinition) = _get_method = Some(value)

    def setMethod: Option[MethodDefinition] = {
        _set_method match {
            case Some(m) => Some(m)
            case None =>
                initializeMethods()
                _set_method
        }
    }
    def setMethod_=(value: MethodDefinition) = _set_method = Some(value)


    def hasOtherMethods = {
        _other_methods match {
            case Some(m) => m.length > 0
            case None =>
                initializeMethods()
                _other_methods.exists(_.length > 0)

        }
    }
    def otherMethods = {
        _other_methods match {
            case Some(m) => m
            case None =>
                initializeMethods()
                val loaded = ArrayBuffer[MethodDefinition]()
                _other_methods = Some(loaded)
                loaded

    // TODO
        }
    }
    def hasParameters = false
    def hasParameters_=(value: Boolean) = { }

    override def parameters: ArrayBuffer[ParameterDefinition] = {
        initializeMethods()
        if (_get_method.isDefined) {
            mirrorParameters(_get_method.get, 0)
        }
        else if (_set_method.isDefined) {
            mirrorParameters(_set_method.get, 1)
        }
        else {
            ArrayBuffer[ParameterDefinition]()

        }
    }
    def hasConstant = {
        _constant = this.resolveConstant(_constant, module)
        _constant != ConstantProvider.noValue
    }
    def hasConstant_=(value: Boolean) = {
        if (!value) {
            _constant = ConstantProvider.noValue
    
        }
    }
    override def constant: Any = {
        if hasConstant then _constant else ConstantProvider.noValue
    }
    override def constant_=(value: Any) = _constant = value

    def isSpecialName = MemberDefinition.getAttributes(_attributes, PropertyAttributes.specialName.value)
    def isSpecialName_=(value: Boolean) = _attributes = MemberDefinition.setAttributes(_attributes, PropertyAttributes.specialName.value, value)

    def isRuntimeSpecialName = MemberDefinition.getAttributes(_attributes, PropertyAttributes.rtSpecialName.value)
    def isRuntimeSpecialName_=(value: Boolean) = _attributes = MemberDefinition.setAttributes(_attributes, PropertyAttributes.rtSpecialName.value, value)

    def hasDefault = MemberDefinition.getAttributes(_attributes, PropertyAttributes.hasDefault.value)
    def hasDefault_=(value: Boolean) = _attributes = MemberDefinition.setAttributes(_attributes, PropertyAttributes.hasDefault.value, value)

    override def fullName = {
        propertyType.toString() + "(" +
            // TODO parameters
            ")"

    }
    private def initializeMethods(): Unit = {
        this.module match {
        case None => return ()
        case Some(module) =>
        module.syncRoot.synchronized {
            if (_get_method.isDefined || _set_method.isDefined) {
                return ()
            
            }
            if (!module.hasImage) {
                return ()
            // TODO
            // module.read(this, (property, reader) => reader.readMethods(property))
            }
        }
        }

    }
    override def resolve(): MemberDefinition = this
}

object PropertyDefinition {
    // TODO
    def mirrorParameters(method: MethodDefinition, bound: Int):ArrayBuffer[ParameterDefinition] = {
        ArrayBuffer.empty[ParameterDefinition]

    }
}
