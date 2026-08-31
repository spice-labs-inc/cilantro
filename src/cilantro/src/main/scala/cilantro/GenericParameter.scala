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
import io.spicelabs.cilantro.AnyExtension.as

sealed class GenericParameter(initialName: String, var _owner: Option[GenericParameterProvider])
    extends TypeReference("", initialName) with CustomAttributeProvider {

        var _position = -1
        var _type:GenericParameterType = _owner.map(_.genericParameterType).getOrElse(GenericParameterType.`type`)

        this.etype = GenericParameter.convertGenericParameterType(_type)
        this.token = Some(MetadataToken(TokenType.genericParam))

        private var _attributes:Char = 0
        private var _constraints: Option[GenericParameterConstraintCollection] = None
        private var _custom_attributes: Option[ArrayBuffer[CustomAttribute]] = None

        def attributes = _attributes
        def attributes_(value: Char) = _attributes = value

        def position = _position
        
        def parameterType: GenericParameterType = _type
        def owner = _owner

        def hasConstraints = {
            if (_constraints.exists(_.length > 0)) {
                true
            }
            else {
                hasImage && module.exists(m => m.read(this, (generic_parameter, reader) => false /* reader.hasConstraints */))
            }
        
        }
        def constraints: ArrayBuffer[GenericParameterConstraint] = {
            _constraints match {
                case Some(c) => c

                case None =>
                    val loaded = if (hasImage) module.flatMap(m => m.read(this, (generic_parameter, reader) => reader.readGenericConstraints(generic_parameter))).getOrElse(GenericParameterConstraintCollection(this))
                                 else GenericParameterConstraintCollection(this)
                    _constraints = Some(loaded)
                    loaded
            }
        }
        def hasCustomAttributes = {
            _custom_attributes match {
                case Some(a) => a.length > 0

                case None => getHasCustomAttributes(module)
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
        override def scope: Option[MetadataScope] = {
            owner match {
                case None => None
                case Some(o) if o.genericParameterType == GenericParameterType.method =>
                    o.asInstanceOf[MethodReference].declaringType.flatMap(_.scope)
                case Some(o) =>
                    o.asInstanceOf[TypeReference].scope
            }
        }
        override def scope_=(value: MetadataScope) = {
            throw OperationNotSupportedException()

        }
        override def declaringType: Option[TypeReference] = owner.flatMap(o => o.as[TypeReference])
        override def declaringType_=(value: TypeReference) = throw OperationNotSupportedException()

        def declaringMethod: Option[MethodReference] = owner.flatMap(o => o.as[MethodReference])

        override def module = {
            _module.orElse(owner.flatMap(_.module))

        }
        override def name = {
            if (super.name.length > 0) {
                return super.name
            }
            super.name = (if _type == GenericParameterType.method then "!!" else "!") + _position
            super.name

        }
        override def nameSpace = ""
        override def nameSpace_=(value: String) = throw OperationNotSupportedException()

        override def fullName = name

        override def isGenericParameter = true

        override def containsGenericParameter = true

        override def metadataType = etype.asMetadataType

        // GenericParameterAttributes
        def isNonVariant = MemberDefinition.getMaskedAttributes(_attributes, GenericParameterAttributes.varianceMask.value, GenericParameterAttributes.nonVariant.value)
        def isNonVariant_=(value: Boolean) = {
            _attributes = MemberDefinition.setMaskedAttributes(_attributes, GenericParameterAttributes.varianceMask.value, GenericParameterAttributes.nonVariant.value, value)

        }
        def isCovariant = MemberDefinition.getMaskedAttributes(_attributes, GenericParameterAttributes.varianceMask.value, GenericParameterAttributes.covariant.value)
        def isCovariant_=(value: Boolean) = {
            _attributes = MemberDefinition.setMaskedAttributes(_attributes, GenericParameterAttributes.varianceMask.value, GenericParameterAttributes.covariant.value, value)

        }
        def isContravariant = MemberDefinition.getMaskedAttributes(_attributes, GenericParameterAttributes.varianceMask.value, GenericParameterAttributes.contravariant.value)
        def isContravariant_=(value: Boolean) = {
            _attributes = MemberDefinition.setMaskedAttributes(_attributes, GenericParameterAttributes.varianceMask.value, GenericParameterAttributes.contravariant.value, value)

        }
        def hasReferenceTypeConstraint = MemberDefinition.getAttributes(_attributes, GenericParameterAttributes.referenceTypeConstraint.value)
        def hasReferenceTypeConstraint_=(value: Boolean) = {
            _attributes = MemberDefinition.setAttributes(_attributes, GenericParameterAttributes.referenceTypeConstraint.value, value)

        }
        def hasNotNullableValueTypeConstraint = MemberDefinition.getAttributes(_attributes, GenericParameterAttributes.notNullableValueTypeConstraint.value)
        def hasNotNullableValueTypeConstraint_=(value: Boolean) = {
            _attributes = MemberDefinition.setAttributes(_attributes, GenericParameterAttributes.notNullableValueTypeConstraint.value, value)

        }
        def hasDefaultConstructorConstraint = MemberDefinition.getAttributes(_attributes, GenericParameterAttributes.defaultConstructorConstraint.value)
        def hasDefaultConstructorConstraint_=(value: Boolean) = {
            _attributes = MemberDefinition.setAttributes(_attributes, GenericParameterAttributes.defaultConstructorConstraint.value, value)

        }
        def allowByRefLike = MemberDefinition.getAttributes(_attributes, GenericParameterAttributes.allowByRefLikeConstraint.value)
        def allowByRefLike_=(value: Boolean) = {
            _attributes = MemberDefinition.setAttributes(_attributes, GenericParameterAttributes.allowByRefLikeConstraint.value, value)

        }
        def this(owner: GenericParameterProvider) = {
            this("", Some(owner))
        
        }
        def this (position: Int, `type`: GenericParameterType, module: ModuleDefinition) = {
            this("", None)
            _position = position
            this._type = `type`
            this.etype = GenericParameter.convertGenericParameterType(`type`)
            this._module = Some(module)

        }
        override def resolve(): TypeDefinition = throw OperationNotSupportedException()
}

object GenericParameter {
    def convertGenericParameterType(`type`: GenericParameterType) = {
        `type` match {
            case GenericParameterType.`type` => ElementType.`var`
            case GenericParameterType.method => ElementType.mVar

        
        }
    }
}

sealed class GenericParameterCollection(private val owner: GenericParameterProvider, capacity: Int = 0) extends ArrayBuffer[GenericParameter](capacity) {

    override def addOne(elem: GenericParameter): this.type = {
        val result = super.addOne(elem)
        updateGenericParameter(elem, result.length - 1)
        this
    
    }
    override def insert(index: Int, elem: GenericParameter): Unit = {
        super.insert(index, elem)
        updateGenericParameter(elem, index)
        for i <- index + 1 until length do {
            this(i)._position = i
    
        }
    }
    override def update(index: Int, elem: GenericParameter): Unit = {
        super.update(index, elem)
        updateGenericParameter(elem, index)
    
    }
    def updateGenericParameter(elem: GenericParameter, index: Int) = {
        elem._owner = Some(owner)
        elem._position = index
        elem._type = owner.genericParameterType
    
    }
    override def remove(index: Int): GenericParameter = {
        val elem = super.remove(index)
        elem._owner = None
        elem._position = -1
        elem._type = GenericParameterType.`type`

        for i <- index until length do {
            this(i)._position = i

        }
        elem
    }
}

sealed class GenericParameterConstraint(private var _constraint_type: TypeReference, var _token: Option[MetadataToken] ) extends CustomAttributeProvider {
    var _generic_parameter: Option[GenericParameter] = None
    
    private var _custom_attributes: Option[ArrayBuffer[CustomAttribute]] = None

    def constraintType = _constraint_type
    def constraintType_=(value: TypeReference) = _constraint_type = value

    def hasCustomAttributes:Boolean = {
        _custom_attributes match {
            case Some(a) => a.length > 0

            case None =>
                _generic_parameter match {
                    case Some(gp) => this.getHasCustomAttributes(gp.module)
                    case None => false
                }
        }

    }
    def customAttributes = {
        _custom_attributes match {
            case Some(a) => a

            case None =>
                val loaded = _generic_parameter match {
                    case Some(gp) => getCustomAttributes(ArrayBuffer.empty[CustomAttribute], gp.module)
                    case None => ArrayBuffer.empty[CustomAttribute]
                }
                _custom_attributes = Some(loaded)
                loaded
        }
    }
    def metadataToken: Option[MetadataToken] = _token
    def metadataToken_=(value: MetadataToken) = _token = Some(value)
}

class GenericParameterConstraintCollection(private val _generic_parameter: GenericParameter, initializSize: Int = 0 ) extends ArrayBuffer[GenericParameterConstraint](initializSize) {

    override def addOne(elem: GenericParameterConstraint): this.type = {
        val result = super.addOne(elem)
        elem._generic_parameter = Some(_generic_parameter)
        this
    
    }
    override def insert(index: Int, elem: GenericParameterConstraint): Unit = {
        super.insert(index, elem)
        elem._generic_parameter = Some(_generic_parameter)

    }
    override def update(index: Int, elem: GenericParameterConstraint): Unit = {
        super.update(index, elem)
        elem._generic_parameter = Some(_generic_parameter)
    
    }
    override def remove(index: Int): GenericParameterConstraint = {
        val elem = super.remove(index)
        elem._generic_parameter = None
        elem
    }
}