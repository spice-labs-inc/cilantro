//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/GenericParameter.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer
import javax.naming.OperationNotSupportedException
import io.spicelabs.cilantro.metadata.ElementType
import scala.collection.mutable

sealed class GenericParameter(_name:String, var _owner: GenericParameterProvider)
    extends TypeReference("", _name) with CustomAttributeProvider {

        var _position = -1
        var _type:GenericParameterType = _owner.genericParameterType

        this.etype = GenericParameter.convertGenericParameterType(_owner.genericParameterType)
        this.token = MetadataToken(TokenType.genericParam)

        private var _attributes:Char = 0
        private var _constraints: GenericParameterConstraintCollection = null
        private var _custom_attributes: ArrayBuffer[CustomAttribute] = null

        def attributes = _attributes
        def attributes_(value: Char) = _attributes = value

        def position = _position
        
        def parameterType: GenericParameterType = _type
        def owner = _owner

        def hasConstraints =
            if (constraints != null)
                constraints.length > 0

            hasImage && module.read(this, (generic_parameter, reader) => false /* reader.hasConstraints */)
        
        def constraints: ArrayBuffer[CustomAttribute] =
            if (_constraints != null)
                return _constraints.asInstanceOf[ArrayBuffer[CustomAttribute]]
            
            if (hasImage)
                _constraints = module.read(_constraints, this, (generic_parameter, reader) => reader.readGenericConstraints(generic_parameter))
                return _constraints.asInstanceOf[ArrayBuffer[CustomAttribute]]
            
            _constraints = GenericParameterConstraintCollection(this)
            _constraints.asInstanceOf[ArrayBuffer[CustomAttribute]]

        def hasCustomAttributes =
            if (_custom_attributes != null)
                _custom_attributes
            
            getHasCustomAttributes(module)
        
        def customAttributes =
            if (_custom_attributes != null)
                _custom_attributes
            _custom_attributes = getCustomAttributes(_custom_attributes, module)
            _custom_attributes
        
        override def scope: MetadataScope = null
            // TODO
            // if (owner == null)
            //     return null
            // if (owner.genericParameterType == GenericParameterType.method)
            //     owner.asInstanceOf[MethodReference].declaringType.scope
            // else
            //     owner.asInstanceOf[TypeReference].scope
        override def scope_=(value: MetadataScope) =
            throw OperationNotSupportedException()
        
        override def name =
            if (super.name != null && super.name.length > 0)
                super.name
            super.name = (if `type` == GenericParameterType.method then "!!" else "!") + _position
            super.name

        override def nameSpace = ""
        override def nameSpace_=(value: String) = throw OperationNotSupportedException()

        override def fullName = name

        override def isGenericParameter = true

        override def containsGenericParameter = true

        override def metadataType = etype.asMetadataType

        // GenericParameterAttributes
        def isNonVariant = MemberDefinition.getMaskedAttributes(_attributes, GenericParameterAttributes.varianceMask.value, GenericParameterAttributes.nonVariant.value)
        def isNonVariant_=(value: Boolean) =
            _attributes = MemberDefinition.setMaskedAttributes(_attributes, GenericParameterAttributes.varianceMask.value, GenericParameterAttributes.nonVariant.value, value)

        def isCovariant = MemberDefinition.getMaskedAttributes(_attributes, GenericParameterAttributes.varianceMask.value, GenericParameterAttributes.covariant.value)
        def isCovariant_=(value: Boolean) =
            _attributes = MemberDefinition.setMaskedAttributes(_attributes, GenericParameterAttributes.varianceMask.value, GenericParameterAttributes.covariant.value, value)

        def isContravariant = MemberDefinition.getMaskedAttributes(_attributes, GenericParameterAttributes.varianceMask.value, GenericParameterAttributes.contravariant.value)
        def isContravariant_=(value: Boolean) =
            _attributes = MemberDefinition.setMaskedAttributes(_attributes, GenericParameterAttributes.varianceMask.value, GenericParameterAttributes.contravariant.value, value)

        def hasReferenceTypeConstraint = MemberDefinition.getAttributes(_attributes, GenericParameterAttributes.referenceTypeConstraint.value)
        def hasReferenceTypeConstraint_=(value: Boolean) =
            _attributes = MemberDefinition.setAttributes(_attributes, GenericParameterAttributes.referenceTypeConstraint.value, value)

        def hasNotNullableValueTypeConstraint = MemberDefinition.getAttributes(_attributes, GenericParameterAttributes.notNullableValueTypeConstraint.value)
        def hasNotNullableValueTypeConstraint_=(value: Boolean) =
            _attributes = MemberDefinition.setAttributes(_attributes, GenericParameterAttributes.notNullableValueTypeConstraint.value, value)

        def hasDefaultConstructorConstraint = MemberDefinition.getAttributes(_attributes, GenericParameterAttributes.defaultConstructorConstraint.value)
        def hasDefaultConstructorConstraint_=(value: Boolean) =
            _attributes = MemberDefinition.setAttributes(_attributes, GenericParameterAttributes.defaultConstructorConstraint.value, value)

        def allowByRefLike = MemberDefinition.getAttributes(_attributes, GenericParameterAttributes.allowByRefLikeConstraint.value)
        def allowByRefLike_=(value: Boolean) =
            _attributes = MemberDefinition.setAttributes(_attributes, GenericParameterAttributes.allowByRefLikeConstraint.value, value)

        def this(owner: GenericParameterProvider) =
            this("", owner)
        
        def this (position: Int, `type`: GenericParameterType, module: ModuleDefinition) =
            this("", null)
            _position = position
            this._type = `type`
            this.etype = GenericParameter.convertGenericParameterType(`type`)
            this._module = module

        override def resolve(): MemberDefinition = null
}

object GenericParameter {
    def convertGenericParameterType(`type`: GenericParameterType) =
        `type` match
            case GenericParameterType.`type` => ElementType.`var`
            case GenericParameterType.method => ElementType.mVar
            case null => throw IllegalArgumentException()

        
}

sealed class GenericParameterCollection(private val owner: GenericParameterProvider, capacity: Int = 0) extends ArrayBuffer[GenericParameter](capacity) {

    override def addOne(elem: GenericParameter): this.type =
        val result = super.addOne(elem)
        updateGenericParameter(elem, result.length - 1)
        this
    
    override def insert(index: Int, elem: GenericParameter): Unit =
        super.insert(index, elem)
        updateGenericParameter(elem, index)
        for i <- index + 1 until length do
            this(i)._position = i + 1
    
    override def update(index: Int, elem: GenericParameter): Unit =
        super.update(index, elem)
        updateGenericParameter(elem, index)
    
    def updateGenericParameter(elem: GenericParameter, index: Int) =
        elem._owner = owner
        elem._position = index
        elem._type = owner.genericParameterType
    
    override def remove(index: Int): GenericParameter =
        val elem = super.remove(index)
        elem._owner = null
        elem._position = -1
        elem._type = GenericParameterType.`type`

        for i <- index until length do
            this(i)._position = i - 1

        elem
}

sealed class GenericParameterConstraint(private var _constraint_type: TypeReference, var _token: MetadataToken ) extends CustomAttributeProvider {
    var _generic_parameter: GenericParameter = null
    
    private var _custom_attributes: ArrayBuffer[CustomAttribute] = null

    def constraintType = _constraint_type
    def constraintType_=(value: TypeReference) = _constraint_type = value

    def hasCustomAttributes:Boolean =
        if (_custom_attributes != null)
            _custom_attributes.length > 0
        
        if (_generic_parameter == null)
            return false
        
        this.getHasCustomAttributes(_generic_parameter.module)

    def customAttributes =
        if (_generic_parameter == null)
            if (_custom_attributes == null)
                _custom_attributes = ArrayBuffer()
            _custom_attributes
        
        if (_custom_attributes != null)
            _custom_attributes
        else
            _custom_attributes = getCustomAttributes(_custom_attributes, _generic_parameter.module)
            _custom_attributes

    def metadataToken = _token
    def metadataToken_=(value: MetadataToken) = _token = value
}

class GenericParameterConstraintCollection(private val _generic_parameter: GenericParameter, initializSize: Int = 0 ) extends ArrayBuffer[GenericParameterConstraint](initializSize) {

    override def addOne(elem: GenericParameterConstraint): this.type =
        val result = super.addOne(elem)
        elem._generic_parameter = _generic_parameter
        this
    
    override def insert(index: Int, elem: GenericParameterConstraint): Unit =
        super.insert(index, elem)
        elem._generic_parameter = _generic_parameter

    override def update(index: Int, elem: GenericParameterConstraint): Unit =
        super.update(index, elem)
        elem._generic_parameter = _generic_parameter
    
    override def remove(index: Int): GenericParameterConstraint =
        val elem = super.remove(index)
        elem._generic_parameter = null
        elem
}