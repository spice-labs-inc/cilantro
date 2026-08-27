
//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/ParameterDefinition.cs

package io.spicelabs.cilantro

import io.spicelabs.cilantro.ConstantProvider.notResolved
import scala.collection.mutable.ArrayBuffer
import io.spicelabs.cilantro.ConstantProvider.noValue
import io.spicelabs.cilantro.MemberDefinition.getAttributes
import io.spicelabs.cilantro.MemberDefinition.setAttributes

sealed class ParameterDefinition(name: String, var _attributes: Char, parameterType: TypeReference) extends ParameterReference(name, parameterType) with CustomAttributeProvider with ConstantProvider /* TODO with MarshalInfoProvider */ {
    this._token = Some(MetadataToken(TokenType.param))

    var _method: Option[MethodSignature] = None

    private var _constant:Any = notResolved

    private var _custom_attributes: Option[ArrayBuffer[CustomAttribute]] = None

    def attributes = _attributes
    def attributes_=(value: Char) = _attributes = value

    def method = _method

    def sequence = {
        _method match {
            case None => -1
            case Some(m) => if m.hasImplicitThis() then index + 1 else index
    
        }
    }
    def hasConstant = {
        _constant = this.resolveConstant(_constant, parameterType.module)
        _constant != noValue
    }
    def hasConstant_=(value: Boolean):Unit = {
        if (!value) {
            _constant = noValue
    
        }
    }
    def constant = if hasConstant then _constant else noValue
    def constant_=(value: Any) = _constant = value

    def hasCustomAttributes: Boolean = {
        _custom_attributes match {
            case Some(a) => a.length > 0
            case None => this.getHasCustomAttributes(parameterType.module)
        }
    
    }
    def customAttributes = {
        _custom_attributes match {
            case Some(a) => a
            case None =>
                val loaded = getCustomAttributes(ArrayBuffer.empty[CustomAttribute], parameterType.module)
                _custom_attributes = Some(loaded)
                loaded


        }
    }
    def this(parameterType: TypeReference) = {
        this("", ParameterAttributes.none.value, parameterType)
    
    }
    def this(parameterType: TypeReference, method: MethodSignature) = {
        this("", ParameterAttributes.none.value, parameterType)
        _method = Some(method)

    // TODO    
    // def hasMarshalInfo = ...

    // def marshalInfo = ...
    // def hasMarshalInfo_=(value: MarshalInfo) = ...

    }
    def isIn = getAttributes(_attributes, ParameterAttributes.in.value)
    def isIn_=(value: Boolean) = _attributes = setAttributes(_attributes, ParameterAttributes.in.value, value)

    def isOut = getAttributes(_attributes, ParameterAttributes.out.value)
    def isOut_=(value: Boolean) = _attributes = setAttributes(_attributes, ParameterAttributes.out.value, value)

    def isLcid = getAttributes(_attributes, ParameterAttributes.lcid.value)
    def isLcid_=(value: Boolean) = _attributes = setAttributes(_attributes, ParameterAttributes.lcid.value, value)
    
    def isReturnValue = getAttributes(_attributes, ParameterAttributes.retval.value)
    def isReturnValue_=(value: Boolean) = _attributes = setAttributes(_attributes, ParameterAttributes.retval.value, value)
    
    def isOptional = getAttributes(_attributes, ParameterAttributes.optional.value)
    def isOptional_=(value: Boolean) = _attributes = setAttributes(_attributes, ParameterAttributes.optional.value, value)
    
    def hasDefault = getAttributes(_attributes, ParameterAttributes.hasDefault.value)
    def hasDefault_=(value: Boolean) = _attributes = setAttributes(_attributes, ParameterAttributes.hasDefault.value, value)
    
    def hasFieldMarshal = getAttributes(_attributes, ParameterAttributes.hasFieldMarshal.value)
    def hasFieldMarshal_=(value: Boolean) = _attributes = setAttributes(_attributes, ParameterAttributes.hasFieldMarshal.value, value)

    override def resolve() = this  
}
