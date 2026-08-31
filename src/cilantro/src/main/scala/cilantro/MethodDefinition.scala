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
import scala.util.Try
import io.spicelabs.cilantro.cil.CustomDebugInformation
import io.spicelabs.cilantro.cil.MethodBody
import io.spicelabs.cilantro.cil.MethodBodyReader
import javax.naming.OperationNotSupportedException


// TODO ctor parameters
class MethodDefinition(_name: String, private var _attributes: Char, returnType: TypeReference) extends MethodReference(_name, returnType) with MemberDefinition {
    this.token = Some(MetadataToken(TokenType.method))
    private var _impl_attributes: Char = 0

    var _sem_attrs_ready: Boolean = false

    var _sem_attrs: Char = 0

    private var _custom_attributes: Option[ArrayBuffer[CustomAttribute]] = None
    private var _security_declarations: Option[ArrayBuffer[SecurityDeclaration]] = None

    var _rva: Int = 0
    var parameter_range: Option[io.spicelabs.cilantro.Range] = None
    // TODO
    // var pinvoke: PInvokeInfo = null

    private var _overrides: Option[ArrayBuffer[MethodReference]] = None

    // TODO
    // var _body: MethodBody
    // var _debug_info: MethodDebugInformation = null
    var _custom_infos: Option[ArrayBuffer[CustomDebugInformation]] = None

    def this() = {
        this("", 0, TypeReference("", ""))

    }
    override def name_=(value: String) = {
        if (isWindowsRuntimeProjection && value != name) {
            throw OperationNotSupportedException()
        }
        super.name = value
    
    }
    def attributes:Char = _attributes
    def attributes_=(value: Char) = {
        if (isWindowsRuntimeProjection && value != _attributes) {
            throw OperationNotSupportedException()
        }
        _attributes = value
    }
    def attributes_=(value: MethodAttributes) = {
        if (isWindowsRuntimeProjection && value.value != _attributes) {
            throw OperationNotSupportedException()
        }
        _attributes = value.value


    }
    def implAttributes = _impl_attributes
    def implAttributes_=(value: Char) = {
        if (isWindowsRuntimeProjection && value != _impl_attributes) {
            throw OperationNotSupportedException()
        }
        _impl_attributes = value


    }
    def semanticAttributes = {
        if (_sem_attrs_ready) {
            _sem_attrs
        }
        else if (hasImage) {
                readSemantics()
                _sem_attrs
        }
        else {
            _sem_attrs = 0
            _sem_attrs_ready = true
            _sem_attrs
    

        }
    }
    def windowsRuntimeProjection: MethodDefinitionProjection = projection.map(_.asInstanceOf[MethodDefinitionProjection]).getOrElse(throw OperationNotSupportedException())
    def windowsRuntimeProjection_=(value: MethodDefinitionProjection) = projection = Some(value)

    def readSemantics(): Unit = {
        module.foreach(m => m.read(this, (method, reader) => reader.readSemantics(method)))
    }

    def hasSecurityDeclarations = {
        _security_declarations.exists(_.length > 0)

    }
    def securityDeclarations = {
        _security_declarations match {
            case Some(d) => d
            case None =>
                val loaded = ArrayBuffer.empty[SecurityDeclaration]
                _security_declarations = Some(loaded)
                loaded

        }
    }
    def hasCustomAttributes = {
        _custom_attributes.exists(_.length > 0)

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
    def RVA = _rva

    def hasBody: Boolean = {
        (attributes & (MethodAttributes.`abstract`.value | MethodAttributes.pInvokeImpl.value)) == 0 &&
        (implAttributes & (MethodImplAttributes.internalCall.value | MethodImplAttributes.native.value |
            MethodImplAttributes.unmanaged.value | MethodImplAttributes.runtime.value)) == 0
    }

    // Reads and decodes the method body (Phase 2 decoder + Phase 3 EH
    // reader). Success(None) for RVA == 0 (abstract / P/Invoke) or
    // non-body methods; Failure for malformed bodies. See
    // MethodBodyReader.
    def readBody(): Try[Option[MethodBody]] = {
        MethodBodyReader.readBody(this)
    }

    // TODO
    def hasPInvokeInfo = false

    def hasOverrides = {
        _overrides match {
            case Some(o) => o.length > 0
            case None => hasImage && module.exists(m => m.read(this, (method, reader) => reader.hasOverrides(method)))
        }
    }
    def overrides = {
        _overrides match {
            case Some(o) => o
            case None =>
                val loaded = if (hasImage) module.map(m => m.read(ArrayBuffer.empty[MethodReference], this, (method, reader) => reader.readOverrides(method))).getOrElse(ArrayBuffer.empty[MethodReference])
                             else ArrayBuffer[MethodReference]()
                _overrides = Some(loaded)
                loaded
    
        }
    }
    override def hasGenericParameters = {
        _generic_parameters match {
            case Some(gp) => gp.length > 0
            case None => getHasGenericParameters(module)
    
        }
    }
    override def genericParameters = {
        _generic_parameters match {
            case Some(gp) => gp
            case None =>
                val loaded = getGenericParameters(ArrayBuffer.empty[GenericParameter], module)
                _generic_parameters = Some(loaded)
                loaded

        }
    }
    def hasCustomDebugInformations = {
        // TODO
        // read(body)
        _custom_infos.exists(_.length > 0)
    
    }
    def customDebugInformations = {
        // TODO
        // read(body)

        _custom_infos match {
            case Some(i) => i
            case None =>
                val loaded = ArrayBuffer[CustomDebugInformation]()
                _custom_infos = Some(loaded)
                loaded
        }

    }
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

    def declaringTypeTD: TypeDefinition = super.declaringType.asInstanceOf[TypeDefinition]
    def declaringTypeTD_=(value: TypeDefinition): Unit = super.declaringType = value

    def isConstructor = {
        isRuntimeSpecialName && isSpecialName &&
        (name == ".cctor" || name == ".ctor")
    
    }
    override def isDefinition = true
}
