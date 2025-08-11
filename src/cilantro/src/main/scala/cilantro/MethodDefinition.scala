//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/MethodDefinition.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer
import io.spicelabs.cilantro.cil.CustomDebugInformation
import javax.naming.OperationNotSupportedException


// TODO ctor parameters
class MethodDefinition extends MethodReference with MemberDefinition {
    this.token = MetadataToken(TokenType.method)
    private var _attributes: Char = 0
    private var _impl_attributes: Char = 0

    var _sem_attrs_ready: Boolean = false

    var _sem_attrs: Char = 0

    private var _custom_attributes: ArrayBuffer[CustomAttribute] = null
    private var _security_declarations: ArrayBuffer[SecurityDeclaration] = null

    var _rva: Int = 0
    // TODO
    // var pinvoke: PInvokeInfo = null

    private var _overrides: ArrayBuffer[MethodReference] = null

    // TODO
    // var _body: MethodBody
    // var _debug_info: MethodDebugInformation = null
    var _custom_infos: ArrayBuffer[CustomDebugInformation] = null

    override def name =
        super.name
    override def name_=(value: String) =
        if (isWindowsRuntimeProjection && value != super.name)
            throw OperationNotSupportedException()
        super.name = value
    
    def attributes:Char = _attributes
    def attributes_=(value: Char) =
        if (isWindowsRuntimeProjection && value != _attributes)
            throw OperationNotSupportedException()
        _attributes = value
    def attributes_=(value: MethodAttributes) =
        if (isWindowsRuntimeProjection && value.value != _attributes)
            throw OperationNotSupportedException()
        _attributes = value.value


    def implAttributes = _impl_attributes
    def implAttributes_=(value: Char) =
        if (isWindowsRuntimeProjection && value != _impl_attributes)
            throw OperationNotSupportedException()
        _impl_attributes = value


    def semanticAttributes =
        if (_sem_attrs_ready)
            _sem_attrs
        else if (hasImage)
                readSemantics()
                _sem_attrs
        else
            _sem_attrs = 0
            _sem_attrs_ready = true
            _sem_attrs
    

    // TODO
    // def windowsRuntimeProjection: MethodDefinitionProjection = ...
    // def windowsRuntimeProjection_=(value: MethodDefinitionProjection) = ...

    def readSemantics(): Unit = { }

    def hasSecurityDeclarations =
        if (_security_declarations != null)
            _security_declarations.length > 0
        false // TODO

    def securityDeclarations =
        if (_security_declarations != null)
            _security_declarations
        else
            _security_declarations = null // TODO getSecurityDeclarations(_security_declarations, module)
            _security_declarations

    def hasCustomAttributes =
        if (_custom_attributes != null)
            _custom_attributes.length
        false // TODO getHasCustomAttributes()

    def customAttributes =
        if (_custom_attributes != null)
            _custom_attributes
        else
            _custom_attributes = getCustomAttributes(_custom_attributes, module)
            _custom_attributes
    
    def RVA = _rva

    // TODO
    def hasBody = false

    // TODO
    def hasPInvokeInfo = false

    def hasOverrides =
        if (_overrides != null)
            _overrides.length > 0
        else
            hasImage && module.read(this, (method, reader) => reader.hasOverrides(method))

    def overrides =
        if (_overrides != null)
            _overrides
        else if (hasImage)
            _overrides = module.read(_overrides, this, (method, reader) => reader.readOverrides(method))
            _overrides
        else
            _overrides = ArrayBuffer[MethodReference]()
            _overrides
    
    override def hasGenericParameters =
        if (_generic_parameters != null)
            _generic_parameters.length > 0
        else
            getHasGenericParameters(module)
    
    override def genericParameters =
        if (_generic_parameters != null)
            _generic_parameters
        else
            _generic_parameters = getGenericParameters(_generic_parameters, module)
            _generic_parameters

    def hasCustomDebugInformations =
        // TODO
        // read(body)
        _custom_infos != null && _custom_infos.length > 0
    
    def customDebugInformations =
        // TODO
        // read(body)

        if (_custom_infos == null)
            _custom_infos = ArrayBuffer[CustomDebugInformation]()
        _custom_infos

    def isCompilerControlled = MemberDefinition.getMaskedAttributes(_attributes, MethodAttributes.memberAccessMask.value, MethodAttributes.compilerControlled.value)
    def isCompilerControlled_=(value: Boolean) = _attributes = MemberDefinition.setMaskedAttributes(_attributes, MethodAttributes.memberAccessMask.value, MethodAttributes.compilerControlled.value, value)

    def isPrivate = MemberDefinition.getMaskedAttributes(_attributes, MethodAttributes.memberAccessMask.value, MethodAttributes.`private`.value)
    def isPrivate_=(value: Boolean) = _attributes = MemberDefinition.setMaskedAttributes(_attributes, MethodAttributes.`private`.value, MethodAttributes.compilerControlled.value, value)

    def isFamilyAndAssembly = MemberDefinition.getMaskedAttributes(_attributes, MethodAttributes.memberAccessMask.value, MethodAttributes.famANDAssem.value)
    def isFamilyAndAssembly_=(value: Boolean) = _attributes = MemberDefinition.setMaskedAttributes(_attributes, MethodAttributes.memberAccessMask.value, MethodAttributes.famANDAssem.value, value)

    def isAssembly = MemberDefinition.getMaskedAttributes(_attributes, MethodAttributes.memberAccessMask.value, MethodAttributes.assembly.value)
    def isAssembly_=(value: Boolean) = _attributes = MemberDefinition.setMaskedAttributes(_attributes, MethodAttributes.memberAccessMask.value, MethodAttributes.assembly.value, value)

    def isFamily = MemberDefinition.getMaskedAttributes(_attributes, MethodAttributes.memberAccessMask.value, MethodAttributes.family.value)
    def isFamily_=(value: Boolean) = _attributes = MemberDefinition.setMaskedAttributes(_attributes, MethodAttributes.memberAccessMask.value, MethodAttributes.family.value, value)

    def isFamilyOrAssembly = MemberDefinition.getMaskedAttributes(_attributes, MethodAttributes.memberAccessMask.value, MethodAttributes.famORAssem.value)
    def isFamilyOrAssembly_=(value: Boolean) = _attributes = MemberDefinition.setMaskedAttributes(_attributes, MethodAttributes.memberAccessMask.value, MethodAttributes.famORAssem.value, value)

    def isPublic = MemberDefinition.getMaskedAttributes(_attributes, MethodAttributes.memberAccessMask.value, MethodAttributes.`public`.value)
    def isPublic_=(value: Boolean) = _attributes = MemberDefinition.setMaskedAttributes(_attributes, MethodAttributes.memberAccessMask.value, MethodAttributes.`public`.value, value)

    def isStatic = MemberDefinition.getAttributes(_attributes, MethodAttributes.static.value)
    def isStatic_=(value: Boolean) = MemberDefinition.setAttributes(_attributes, MethodAttributes.static.value, value)

    def isFinal = MemberDefinition.getAttributes(_attributes, MethodAttributes.`final`.value)
    def isFinal_=(value: Boolean) = MemberDefinition.setAttributes(_attributes, MethodAttributes.`final`.value, value)

    def isVirtual = MemberDefinition.getAttributes(_attributes, MethodAttributes.`virtual`.value)
    def isVirtual_=(value: Boolean) = MemberDefinition.setAttributes(_attributes, MethodAttributes.`virtual`.value, value)

    def isHideBySig = MemberDefinition.getAttributes(_attributes, MethodAttributes.hideBySig.value)
    def isHideBySig_=(value: Boolean) = MemberDefinition.setAttributes(_attributes, MethodAttributes.hideBySig.value, value)

    def isReusesSlot = MemberDefinition.getAttributes(_attributes, MethodAttributes.reusesSlot.value)
    def isReusesSlot_=(value: Boolean) = MemberDefinition.setAttributes(_attributes, MethodAttributes.reusesSlot.value, value)

    def isNewSlot = MemberDefinition.getAttributes(_attributes, MethodAttributes.newSlot.value)
    def isNewSlot_=(value: Boolean) = MemberDefinition.setAttributes(_attributes, MethodAttributes.newSlot.value, value)

    def isCheckAccessOnOverride = MemberDefinition.getAttributes(_attributes, MethodAttributes.checkAccessOnOverride.value)
    def isCheckAccessOnOverride_=(value: Boolean) = MemberDefinition.setAttributes(_attributes, MethodAttributes.checkAccessOnOverride.value, value)

    def isAbstract = MemberDefinition.getAttributes(_attributes, MethodAttributes.`abstract`.value)
    def isAbstract_=(value: Boolean) = MemberDefinition.setAttributes(_attributes, MethodAttributes.`abstract`.value, value)

    def isSpecialName = MemberDefinition.getAttributes(_attributes, MethodAttributes.specialName.value)
    def isSpecialName_=(value: Boolean) = MemberDefinition.setAttributes(_attributes, MethodAttributes.specialName.value, value)

    def isPInvokeImpl = MemberDefinition.getAttributes(_attributes, MethodAttributes.pInvokeImpl.value)
    def isPInvokeImpl_=(value: Boolean) = MemberDefinition.setAttributes(_attributes, MethodAttributes.pInvokeImpl.value, value)

    def isUnmanagedExport = MemberDefinition.getAttributes(_attributes, MethodAttributes.unmanagedExport.value)
    def isUnmanagedExport_=(value: Boolean) = MemberDefinition.setAttributes(_attributes, MethodAttributes.unmanagedExport.value, value)

    def isRuntimeSpecialName = MemberDefinition.getAttributes(_attributes, MethodAttributes.rtSpecialName.value)
    def isRuntimeSpecialName_=(value: Boolean) = MemberDefinition.setAttributes(_attributes, MethodAttributes.rtSpecialName.value, value)

    def hasSecurity = MemberDefinition.getAttributes(_attributes, MethodAttributes.hasSecurity.value)
    def hasSecurity_=(value: Boolean) = MemberDefinition.setAttributes(_attributes, MethodAttributes.hasSecurity.value, value)

    // TODO: IsIL, IsNative, IsRuntime, IsUnmanaged, IsManaged, IsForwardRef, isPreserveSig, IsInternalCall
    // TODO: NoInlining, NoOptimization, AggressiveInlining, AggressiveOptimization

    // TODO
    def isSetter = false
    def isSetter_=(value: Boolean) = { }

    def isGetter = false
    def isGetter_=(value: Boolean) = { }

    def isOther = false
    def isOther_=(value: Boolean) = { }

    def isAddOn = false
    def isAddOn_=(value: Boolean) = { }

    def isRemoveOn = false
    def isRemoveOn_=(value: Boolean) = { }

    def isFire = false
    def isFire_=(value: Boolean) = { }

    def isConstructor =
        isRuntimeSpecialName && isSpecialName &&
        (name == ".cctor" || name == ".ctor")
    
    override def isDefinition = true
}
