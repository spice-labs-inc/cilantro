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

import javax.naming.OperationNotSupportedException
import scala.collection.mutable.ArrayBuffer


sealed class MethodReturnType(val _method: MethodSignature) extends ConstantProvider with CustomAttributeProvider /* TODO with MarshalInfoProvider */ {
    var _parameter: Option[ParameterDefinition] = None
    var _return_type: Option[TypeReference] = None

    def method = _method

    def returnType = _return_type.getOrElse(throw OperationNotSupportedException())
    def returnType_=(value: TypeReference) = _return_type = Some(value)

    def parameter = {
        _parameter match {
            case Some(p) => p
            case None =>
                val p = ParameterDefinition(_return_type.getOrElse(throw OperationNotSupportedException()), _method)
                _parameter = Some(p)
                p
        }

    }
    def metadataToken: Option[MetadataToken] = _parameter.flatMap(_.metadataToken)
    def metadataToken_=(value: MetadataToken) = _parameter.foreach(_.metadataToken = value)

    def attributes = _parameter.map(_.attributes).getOrElse(0.toChar)
    def attributes_=(value: Char) = _parameter.foreach(_.attributes = value)

    def name = _parameter.map(_.name).getOrElse("")
    def name_=(value: String) = _parameter.foreach(_.name = value)

    def hasCustomAttributes = _parameter.exists(_.hasCustomAttributes)

    def customAttributes = _parameter.map(_.customAttributes).getOrElse(ArrayBuffer.empty[CustomAttribute])

    def hasDefault = _parameter.exists(_.hasDefault)
    def hasDefault_=(value: Boolean) = _parameter.foreach(_.hasDefault = value)

    def hasConstant = _parameter.exists(_.hasConstant)
    def hasConstant_=(value: Boolean) = _parameter.foreach(_.hasConstant = value)

    override def constant: Any = _parameter.map(_.constant).getOrElse(ConstantProvider.noValue)
    override def constant_=(value: Any): Unit = _parameter.foreach(_.constant = value)

    def hasFieldMarshal = _parameter.exists(_.hasFieldMarshal)
    def hasFieldMarshal_=(value: Boolean) = _parameter.foreach(_.hasFieldMarshal = value)

    // TODO
    // def hasMarshalInfo = ...
    // def marshalInfo = ...
    // def marshalInfo_=(value: MarshalInfo) = ...


}
