//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/MethodReturnType.cs

package io.spicelabs.cilantro


sealed class MethodReturnType(val _method: MethodSignature) extends ConstantProvider with CustomAttributeProvider /* TODO with MarshalInfoProvider */ {
    var _parameter: ParameterDefinition = null
    var _return_type: TypeReference = null

    def method = _method

    def returnType = _return_type
    def returnType_=(value: TypeReference) = _return_type = value

    def parameter =
        if (_parameter == null)
            _parameter = ParameterDefinition(_return_type, _method)
        _parameter

    def metadataToken = _parameter.metadataToken
    def metadataToken_=(value: MetadataToken) = _parameter.metadataToken = value

    def attributes = _parameter.attributes
    def attributes_=(value: Char) = _parameter.attributes = value

    def name = _parameter.name
    def name_=(value: String) = _parameter.name = value

    def hasCustomAttributes = _parameter != null && _parameter.hasCustomAttributes

    def customAttributes = _parameter.customAttributes

    def hasDefault = _parameter != null && _parameter.hasDefault
    def hasDefault_=(value: Boolean) = _parameter.hasDefault = value

    def hasConstant = _parameter != null && _parameter.hasConstant
    def hasConstant_=(value: Boolean) = _parameter.hasConstant = value

    override def constant: Any = _parameter.constant
    override def constant_=(value: Any): Unit = _parameter.constant = value

    def hasFieldMarshal = _parameter != null && _parameter.hasFieldMarshal
    def hasFieldMarshal_=(value: Boolean) = _parameter.hasFieldMarshal = value

    // TODO
    // def hasMarshalInfo = ...
    // def marshalInfo = ...
    // def marshalInfo_=(value: MarshalInfo) = ...


}
