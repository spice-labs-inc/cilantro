//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/TypeDefinition.cs

package io.spicelabs.cilantro

import io.spicelabs.cilantro.cil.CustomDebugInformationProvider
import javax.naming.OperationNotSupportedException
import io.spicelabs.cilantro.cil.CustomDebugInformation
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import io.spicelabs.cilantro.metadata.Row2
import io.spicelabs.cilantro.AnyExtension.as
import java.rmi.server.Operation



class TypeDefinition(namespace: String, name: String, private var _attributes: Int = 0) extends TypeReference(namespace, name) with MemberDefinition with SecurityDeclarationProvider with CustomDebugInformationProvider  {
    private var base_type: TypeReference = null
    var fields_range: Range = null
    var methods_range: Range = null

    private var packing_size = MetadataConsts.notResolvedMarker
    private var class_size = MetadataConsts.notResolvedMarker

    private var _interfaces: InterfaceImplementationCollection = null
    private var _nested_types: ArrayBuffer[TypeDefinition] = null
    private var _methods: ArrayBuffer[MethodDefinition] = null
    private var _fields: ArrayBuffer[FieldDefinition] = null
    private var _events: ArrayBuffer[EventDefinition] = null
    private var _properties: ArrayBuffer[PropertyDefinition] = null
    private var _custom_attributes: ArrayBuffer[CustomAttribute] = null
    private var _security_declarations: ArrayBuffer[SecurityDeclaration] = null

    var _custom_infos: ArrayBuffer[CustomDebugInformation] = null

    def attributes: Int = _attributes
    def attributes_=(value: Int) =
        if (isWindowsRuntimeProjection && value != attributes)
            throw new OperationNotSupportedException()
        _attributes = value

    def baseType = base_type
    def baseType_=(value: TypeReference) = base_type = value

    override def name_=(value: String) =
        if (isWindowsRuntimeProjection && value != super.name)
            throw new OperationNotSupportedException()
        
        super.name = value

    private def resolveLayout(): Unit =
        if (!hasImage)
            packing_size = MetadataConsts.noDataMarker
            class_size = MetadataConsts.noDataMarker
            return ()
        
        module.syncRoot.synchronized {
            if (packing_size != MetadataConsts.notResolvedMarker || class_size != MetadataConsts.notResolvedMarker)
                return ()
            var row = module.read(this, (`type`, reader) => reader.readTypeLayout(`type`))

            // packing_size = row.col1
            // packing_size = row.col2
        }

    def hasLayoutInfo =
        if (packing_size >= 0 || class_size >= 0)
            true
        else
            resolveLayout()
            packing_size >= 0 || class_size >= 0

    def packingSize =
        if (packing_size >= 0)
            packing_size
        resolveLayout()
        if packing_size >= 0 then packing_size else -1
    def packingSize_=(value: Short) = packing_size = value

    def classSize =
        if (class_size >= 0)
            class_size
        else
            resolveLayout()
            if class_size >= 0 then class_size else -1
    def classSize_=(value: Short) = class_size = value

    def hasInterfaces =
        if (_interfaces != null)
            _interfaces.length > 0
        hasImage && module.read(this, (`type`, reader) => reader.hasInterfaces(`type`))

    def interfaces =
        if (_interfaces != null)
            _interfaces
        
        if (hasImage)
            _interfaces = module.read(_interfaces, this, (`type`, reader) => reader.readInterfaces(`type`))
        else
            _interfaces = null // InterfaceImplementationCollection()
        
        _interfaces

    def hasNestedTypes =
        if (_nested_types != null)
            _nested_types.length > 0
        hasImage && module.read(this, (`type`, reader) => reader.hasNestedTypes(`type`))
    
    def nestedTypes =
        if (_nested_types != null)
            _nested_types
        
        if (hasImage)
            _nested_types = module.read(_nested_types, this, (`type`, reader) => reader.readNestedTypes(`type`))
            _nested_types

        _nested_types = MemberDefinitionCollection[TypeDefinition](this)
        _nested_types

    def hasMethods =
        if (_methods != null)
            _methods.length > 0
        
        hasImage && methods_range.length > 0
    
    def methods =
        if (_methods != null)
            _methods

        if (hasImage)
            _methods = module.read(_methods, this, (`type`, reader) => reader.readMethods(`type`))
            _methods
        
        _methods = MemberDefinitionCollection[MethodDefinition](this)
        _methods

    def hasFields =
        if (_fields != null)
            _fields.length > 0
        
        hasImage && fields_range.length > 0
    
    def fields =
        if (_fields != null)
            _fields
        
        if (hasImage)
            _fields = null // module.read(_fields, this, (`type`, reader) => reader.readFields(`type`))
            _fields

        _fields = MemberDefinitionCollection[FieldDefinition](this)
        _fields

    def hasEvents =
        if (_events != null)
            _events.length > 0
        false
        // return hasImage && module.read(this, (`type`, reader) => reader.hasEvents(`type`))
    
    def events =
        if (_events != null)
            _events
        
        if (hasImage)
            _events = module.read(_events, this, (`type`, reader) => reader.readEvents(`type`))
        
        _events = MemberDefinitionCollection[EventDefinition](this)
        _events

    def hasProperties =
        if (_properties != null)
            _properties
        false
        // hasImage && module.read(this, (`type`, reader) => reader.hasProperties(`type`))

    def properties =
        if (_properties != null)
            _properties
        if (hasImage)
            _properties = null // module.read(_properties, this, (`type`, reader) => reader.readProperties(`type`))
            _properties
        
        _properties = MemberDefinitionCollection[PropertyDefinition](this)
        _properties

    def hasSecurityDeclarations =
        if (_security_declarations != null)
            _security_declarations.length > 0
        false
        // TODO
        // getHasSecurityDeclarations(module)

    def securityDeclarations =
        if (_security_declarations != null)
            _security_declarations
        else
            _security_declarations = null // getSecurityDeclarations(_security_declarations, module)
            _security_declarations
    
    def hasCustomAttributes =
        if (_custom_attributes != null)
            _custom_attributes.length > 0
        
        getHasCustomAttributes(module)
    
    def customAttributes =
        if (_custom_attributes != null)
            _custom_attributes
        else
            _custom_attributes = getCustomAttributes(_custom_attributes, module)
            _custom_attributes

    override def hasGenericParameters =
        if (generic_parameters != null)
            generic_parameters.length > 0
        getHasGenericParameters(module)

    override def genericParameters = 
        if (generic_parameters != null)
            generic_parameters
        generic_parameters = getGenericParameters(generic_parameters, module)
        generic_parameters

    def hasCustomDebugInformations =
        if (_custom_infos != null)
            _custom_infos.length > 0
        
        false
        // getHasCustomDebugInformations(_custom_info, module)

    def customDebugInformations =
        if (_custom_infos != null)
            _custom_infos
        _custom_infos = null //getCustomDebugInformations(_custom_infos, module)
        _custom_infos

    // TypeAttributes

    def isNotPublic =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.notPublic.value)
    def isNotPublic_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.notPublic.value, value)

    def isPublic =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.public.value)
    def isPublic_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.public.value, value)

    def isNestedPublic =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedPublic.value)
    def isNestedPublic_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedPublic.value, value)

    def isNestedPrivate =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedPrivate.value)
    def isNestedPrivate_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedPrivate.value, value)

    def isNestedFamily =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamily.value)
    def isNestedFamily_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamily.value, value)

    def isNestedAssembly =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedAssembly.value)
    def isNestedAssembly_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedAssembly.value, value)

    def isNestedFamilyAndAssembly =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamANDAssem.value)
    def isNestedFamilyAndAssembly_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamANDAssem.value, value)

    def isNestedFamilyOrAssembly =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamORAssem.value)
    def isNestedFamilyOrAssembly_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamORAssem.value, value)

    def isAutoLayout =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.autoLayout.value)
    def isAutoLayout_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.autoLayout.value, value)

    def isSequentialLayout =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.sequentialLayout.value)
    def isSequentialLayout_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.sequentialLayout.value, value)

    def isExplicitLayout =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.explicitLayout.value)
    def isExplicitLayout_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.explicitLayout.value, value)

    def isClass =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.classSemanticMask.value, TypeAttributes.`class`.value)
    def isClass_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.classSemanticMask.value, TypeAttributes.`class`.value, value)

    def isInterface =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.classSemanticMask.value, TypeAttributes.interface.value)
    def isInterface_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.classSemanticMask.value, TypeAttributes.interface.value, value)

    def isAbstract =
        MemberDefinition.getAttributes(attributes, TypeAttributes.`abstract`.value)
    def isAbstract_=(value: Boolean) =
        _attributes = MemberDefinition.setAttributes(attributes, TypeAttributes.`abstract`.value, value)

    def isSealed =
        MemberDefinition.getAttributes(attributes, TypeAttributes.`sealed`.value)
    def isSealed_=(value: Boolean) =
        _attributes = MemberDefinition.setAttributes(attributes, TypeAttributes.`sealed`.value, value)

    def isSpecialName =
        MemberDefinition.getAttributes(_attributes, TypeAttributes.specialName.value)
    def isSpecialName_=(value: Boolean) =
        _attributes = MemberDefinition.setAttributes(_attributes, TypeAttributes.specialName.value, value)

    def isImport =
        MemberDefinition.getAttributes(attributes, TypeAttributes.`import`.value)
    def isImport_=(value: Boolean) =
        _attributes = MemberDefinition.setAttributes(attributes, TypeAttributes.`import`.value, value)

    def isSerializable =
        MemberDefinition.getAttributes(attributes, TypeAttributes.serializable.value)
    def isSerializable_=(value: Boolean) =
        _attributes = MemberDefinition.setAttributes(attributes, TypeAttributes.serializable.value, value)

    def isWindowsRuntime =
        MemberDefinition.getAttributes(attributes, TypeAttributes.windowsRuntime.value)
    def isWindowsRuntime_=(value: Boolean) =
        _attributes = MemberDefinition.setAttributes(attributes, TypeAttributes.windowsRuntime.value, value)

    def isAnsiClass =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.stringFormatMask.value, TypeAttributes.ansiClass.value)
    def isAnsiClass_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.stringFormatMask.value, TypeAttributes.ansiClass.value, value)

    def isUnicodeClass =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.stringFormatMask.value, TypeAttributes.unicodeClass.value)
    def isUnicodeClass_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.stringFormatMask.value, TypeAttributes.unicodeClass.value, value)

    def isAutoClass =
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.stringFormatMask.value, TypeAttributes.autoClass.value)
    def isAutoClass_=(value: Boolean) =
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.stringFormatMask.value, TypeAttributes.autoClass.value, value)

    def isBeforeFieldInit =
        MemberDefinition.getAttributes(_attributes, TypeAttributes.beforeFieldInit.value)
    def isBeforeFieldInit_=(value: Boolean) =
        _attributes = MemberDefinition.setAttributes(_attributes, TypeAttributes.beforeFieldInit.value, value)

    def isRuntimeSpecialName =
        MemberDefinition.getAttributes(_attributes, TypeAttributes.rtSpecialName.value)
    def isRuntimeSpecialName_=(value: Boolean) =
        _attributes = MemberDefinition.setAttributes(_attributes, TypeAttributes.rtSpecialName.value, value)

    def hasSecurity =
        MemberDefinition.getAttributes(_attributes, TypeAttributes.hasSecurity.value)
    def hasSecurity_=(value: Boolean) =
        _attributes = MemberDefinition.setAttributes(_attributes, TypeAttributes.hasSecurity.value, value)

    def isEnum = base_type != null && base_type.isTypeOf("System", "Enum")

    override def isValueType: Boolean =
        if (base_type == null)
            return false
        base_type.isTypeOf("System", "Enum") || (base_type.isTypeOf("System", "ValueType") && !this.isTypeOf("System", "Enum"))
    override def isValueType_=(value: Boolean): Unit = throw OperationNotSupportedException()

    override def isPrimitive: Boolean =
        MetadataSystem.tryGetPrimitiveElementType(this) match
            case Some(primitive_etype) => primitive_etype.isPrimitive
            case None => false
        
    override def metadataType: MetadataType =
        MetadataSystem.tryGetPrimitiveElementType(this) match
            case Some(primitive_type) => primitive_type.asMetadataType
            case None => super.metadataType
    
    def getEnumUnderlyingType() =
        var fields = _fields
        fields.find((f) => f.isStatic) match
            case Some(field) => field.fieldType
            case None => throw IllegalArgumentException()
        
    def getNestedType(fullname: String) =
        if (!hasNestedTypes)
            null
        else
            nestedTypes.find((nt) => nt.typeFullName() == fullname) match
                case Some(nt) => nt
                case None => null
}


sealed class InterfaceImplementation(theInterfaceType: TypeReference, token: MetadataToken) extends CustomAttributeProvider {
    var _type: TypeDefinition = null
    var _token: MetadataToken = token
    private var _interface_type: TypeReference = theInterfaceType
    private var _custom_attributes: ArrayBuffer[CustomAttribute] = null

    def interfaceType = _interface_type
    def interfaceType_=(value: TypeReference) = _interface_type = value

    def hasCustomAttributes =
        if (_custom_attributes != null)
            _custom_attributes.length > 0
        else if (_type == null)
            false
        else this.getHasCustomAttributes(_type.module)
    
    def customAttributes =
        if (_type == null)
            if (_custom_attributes == null)
                _custom_attributes = ArrayBuffer[CustomAttribute]()
            _custom_attributes
        else
            if (_custom_attributes == null)
                _custom_attributes = this.getCustomAttributes(_custom_attributes, _type.module)
            _custom_attributes
    
    def metadataToken = _token
    def metadataToken_=(value: MetadataToken) = _token = value

    def this(theInterfaceType: TypeReference) =
        this(theInterfaceType, MetadataToken(TokenType.interfaceImpl))
}

class InterfaceImplementationCollection(private val `type`: TypeDefinition, capacity: Int = 0) extends ArrayBuffer[InterfaceImplementation](capacity) {
    override def addOne(elem: InterfaceImplementation): this.type =
        val result = super.addOne(elem)
        elem._type = `type`
        this
    
    override def insert(index: Int, elem: InterfaceImplementation): Unit =
        super.insert(index, elem)
        elem._type = `type`
    
    override def update(index: Int, elem: InterfaceImplementation): Unit =
        super.update(index, elem)
        elem._type = `type`
    
    override def remove(index: Int): InterfaceImplementation =
        val elem = super.remove(index)
        elem._type = null
        elem
}