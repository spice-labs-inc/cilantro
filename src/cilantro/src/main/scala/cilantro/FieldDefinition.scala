//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/FieldDefinition.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer
import scala.util.boundary
import javax.naming.OperationNotSupportedException

class FieldDefinition(name: String, private var _attributes: Char, fieldType: TypeReference)
    extends FieldReference(name, fieldType) with MemberDefinition with ConstantProvider /* with MarshalInfoProvider */ {

        private var _custom_attributes: Option[ArrayBuffer[CustomAttribute]] = None

        private var _offset: Int = MetadataConsts.notResolvedMarker
        var _rva: Int = MetadataConsts.notResolvedMarker

        private var _initial_value: Option[Array[Byte]] = None

        private var _constant: Any = ConstantProvider.notResolved

        // TODO
        // private var _marshal_info: MarshalInfo = null

        private def resolveLayout(): Unit = {
            if (_offset != MetadataConsts.notResolvedMarker) {
                return ()
            
            }
            if (!hasImage) {
                _offset = MetadataConsts.noDataMarker
                return ()
            
            }
            boundary {
                this.module.foreach { m => m.syncRoot.synchronized {
                    if (_offset != MetadataConsts.notResolvedMarker) {
                        boundary.break()
                    }
                    _offset = m.read(this, (field, reader) => reader.readFieldLayout(field))
                } }
            }
        

        }
        def hasLayoutInfo = {
            if (_offset >=0) {
                true
            }
            else {
                resolveLayout()
                _offset >= 0
        
            }
        }
        def offset = {
            if (_offset >= 0) {
                _offset
            }
            else {
                resolveLayout()
                if _offset >= 0 then _offset else -1
            }
        }
        def offset_=(value: Int) = _offset = value

        def windowsRuntimeProjection = projection.map(_.asInstanceOf[FieldDefinitionProjection])
        def windowsRuntimeProjection_=(value: FieldDefinitionProjection) = projection = Some(value)

        private def resolveRVA(): Unit = {
            if (_rva != MetadataConsts.notResolvedMarker) {
                return ()
            
            }
            if (!hasImage) {
                return ()
            
            }
            boundary {
                module.foreach { m => m.syncRoot.synchronized {
                    if (_rva != MetadataConsts.notResolvedMarker) {
                        boundary.break()
                    // TODO
                    // _rva = module.read(this, (field, reader) => readFieldRVA(field))
                    }
                } }
            }

        }
        def RVA = {
            if (_rva > 0) {
                _rva
            }
            resolveRVA()
            if _rva > 0 then _rva else 0
        

        }
        def initialValue: Array[Byte] = {
            _initial_value match {
                case Some(v) => v

                case None =>
                    resolveRVA()
                    val v = Array.emptyByteArray
                    _initial_value = Some(v)
                    v
            }

        }
        def initialValue_=(value: Array[Byte]) = {
            _initial_value = Some(value)
            hasFieldRVA = _initial_value.exists(_.length > 0)

        }
        def attributes = _attributes
        def attributes_=(value: Char) = {
            if (isWindowsRuntimeProjection && value != attributes) {
                throw OperationNotSupportedException()
            }
            _attributes = value

        }
        def hasConstant = {
            _constant != ConstantProvider.noValue
        }
        def hasConstant_=(value: Boolean) = {
            if (!value) _constant = ConstantProvider.noValue

        }
        def constant = if hasConstant then _constant else ConstantProvider.noValue
        def constant_=(value: Any) = _constant = value

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
        // TODO        
        // def hasMarshalInfo =
        //     if (_marshalInfo != null)
        //         true
        //     this.getMarshalInfo(module)

        // def marshalInfo = ...
        // def marshalInto_=(value: MarshalInfo) = ...

        def isCompilerControlled = MemberDefinition.getMaskedAttributes(_attributes, FieldAttributes.fieldAccessMask.value, FieldAttributes.compilerControlled.value)
        def isCompilerControlled_=(value: Boolean) = _attributes = MemberDefinition.setMaskedAttributes(_attributes, FieldAttributes.fieldAccessMask.value, FieldAttributes.compilerControlled.value, value)

        def isPrivate = MemberDefinition.getMaskedAttributes(_attributes, FieldAttributes.fieldAccessMask.value, FieldAttributes.`private`.value)
        def isPrivate_=(value: Boolean) = _attributes = MemberDefinition.setMaskedAttributes(_attributes, FieldAttributes.fieldAccessMask.value, FieldAttributes.`private`.value, value)

        def isFamilyAndAssembly = MemberDefinition.getMaskedAttributes(_attributes, FieldAttributes.fieldAccessMask.value, FieldAttributes.famANDAssem.value)
        def isFamilyAndAssembly_=(value: Boolean) = _attributes = MemberDefinition.setMaskedAttributes(_attributes, FieldAttributes.fieldAccessMask.value, FieldAttributes.famANDAssem.value, value)

        def isAssembly = MemberDefinition.getMaskedAttributes(_attributes, FieldAttributes.fieldAccessMask.value, FieldAttributes.assembly.value)
        def isAssembly_=(value: Boolean) = _attributes = MemberDefinition.setMaskedAttributes(_attributes, FieldAttributes.fieldAccessMask.value, FieldAttributes.assembly.value, value)

        def isFamilyOrAssembly = MemberDefinition.getMaskedAttributes(_attributes, FieldAttributes.fieldAccessMask.value, FieldAttributes.famORAssem.value)
        def isFamilyOrAssembly_=(value: Boolean) = _attributes = MemberDefinition.setMaskedAttributes(_attributes, FieldAttributes.fieldAccessMask.value, FieldAttributes.famORAssem.value, value)

        def isPublic = MemberDefinition.getMaskedAttributes(_attributes, FieldAttributes.fieldAccessMask.value, FieldAttributes.`public`.value)
        def isPublic_=(value: Boolean) = _attributes = MemberDefinition.setMaskedAttributes(_attributes, FieldAttributes.fieldAccessMask.value, FieldAttributes.`public`.value, value)

        def isStatic = MemberDefinition.getAttributes(_attributes, FieldAttributes.static.value)
        def isStatic_=(value: Boolean) = _attributes = MemberDefinition.setAttributes(_attributes, FieldAttributes.static.value, value)

        def isInitOnly = MemberDefinition.getAttributes(_attributes, FieldAttributes.initOnly.value)
        def isInitOnly_=(value: Boolean) = _attributes = MemberDefinition.setAttributes(_attributes, FieldAttributes.initOnly.value, value)

        def isLiteral = MemberDefinition.getAttributes(_attributes, FieldAttributes.literal.value)
        def isLiteral_=(value: Boolean) = _attributes = MemberDefinition.setAttributes(_attributes, FieldAttributes.literal.value, value)

        def isNotSerialized = MemberDefinition.getAttributes(_attributes, FieldAttributes.notSerialized.value)
        def isNotSerialized_=(value: Boolean) = _attributes = MemberDefinition.setAttributes(_attributes, FieldAttributes.notSerialized.value, value)

        def isSpecialName = MemberDefinition.getAttributes(_attributes, FieldAttributes.specialName.value)
        def isSpecialName_=(value: Boolean) = _attributes = MemberDefinition.setAttributes(_attributes, FieldAttributes.specialName.value, value)

        def isPInvokeImpl = MemberDefinition.getAttributes(_attributes, FieldAttributes.pInvokeImpl.value)
        def isPInvokeImpl_=(value: Boolean) = _attributes = MemberDefinition.setAttributes(_attributes, FieldAttributes.pInvokeImpl.value, value)

        def isRuntimeSpecialName = MemberDefinition.getAttributes(_attributes, FieldAttributes.rtSpecialName.value)
        def isRuntimeSpecialName_=(value: Boolean) = _attributes = MemberDefinition.setAttributes(_attributes, FieldAttributes.rtSpecialName.value, value)

        def hasDefault = MemberDefinition.getAttributes(_attributes, FieldAttributes.hasDefault.value)
        def hasDefault_=(value: Boolean) = _attributes = MemberDefinition.setAttributes(_attributes, FieldAttributes.hasDefault.value, value)

        def hasFieldRVA = MemberDefinition.getAttributes(_attributes, FieldAttributes.hasFieldRVA.value)
        def hasFieldRVA_=(value: Boolean) = _attributes = MemberDefinition.setAttributes(_attributes, FieldAttributes.hasFieldRVA.value, value)

        override def isDefinition = true

        override def resolve() = this.asInstanceOf[MemberDefinition]
}


