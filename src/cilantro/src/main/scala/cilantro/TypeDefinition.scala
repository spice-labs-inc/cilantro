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
import scala.collection.mutable.ArrayBuffer
import scala.util.boundary



class TypeDefinition(namespace: String, name: String, private var _attributes: Int = 0) extends TypeReference(namespace, name) with MemberDefinition with SecurityDeclarationProvider with CustomDebugInformationProvider  {
    private var base_type: Option[TypeReference] = None
    var fields_range: Option[Range] = None
    var methods_range: Option[Range] = None
    var properties_range: Option[Range] = None
    var events_range: Option[Range] = None

    private var packing_size = MetadataConsts.notResolvedMarker
    private var class_size = MetadataConsts.notResolvedMarker

    private var _interfaces: Option[InterfaceImplementationCollection] = None
    private var _nested_types: Option[ArrayBuffer[TypeDefinition]] = None
    private var _methods: Option[ArrayBuffer[MethodDefinition]] = None
    private var _fields: Option[ArrayBuffer[FieldDefinition]] = None
    private var _events: Option[ArrayBuffer[EventDefinition]] = None
    private var _properties: Option[ArrayBuffer[PropertyDefinition]] = None
    private var _custom_attributes: Option[ArrayBuffer[CustomAttribute]] = None
    private var _security_declarations: Option[ArrayBuffer[SecurityDeclaration]] = None

    var _custom_infos: Option[ArrayBuffer[CustomDebugInformation]] = None

    def attributes: Int = _attributes
    def attributes_=(value: Int) = {
        if (isWindowsRuntimeProjection && value != attributes) {
            throw new OperationNotSupportedException()
        }
        _attributes = value

    }
    def baseType: Option[TypeReference] = base_type
    def baseType_=(value: TypeReference) = base_type = Some(value)

    override def name_=(value: String) = {
        if (isWindowsRuntimeProjection && value != super.name) {
            throw new OperationNotSupportedException()
        
        }
        super.name = value

    }
    private def resolveLayout(): Unit = {
        if (!hasImage) {
            packing_size = MetadataConsts.noDataMarker
            class_size = MetadataConsts.noDataMarker
            return ()
        
        }
        boundary {
            module.foreach { m => m.syncRoot.synchronized {
                if (packing_size != MetadataConsts.notResolvedMarker || class_size != MetadataConsts.notResolvedMarker) {
                    boundary.break()
                }
                var row = m.read(this, (`type`, reader) => reader.readTypeLayout(`type`))

                // packing_size = row.col1
                // packing_size = row.col2
            } }
        }

    }
    def hasLayoutInfo = {
        if (packing_size >= 0 || class_size >= 0) {
            true
        }
        else {
            resolveLayout()
            packing_size >= 0 || class_size >= 0

        }
    }
    def packingSize = {
        if (packing_size >= 0) {
            packing_size
        }
        resolveLayout()
        if packing_size >= 0 then packing_size else -1
    }
    def packingSize_=(value: Short) = packing_size = value

    def classSize = {
        if (class_size >= 0) {
            class_size
        }
        else {
            resolveLayout()
            if class_size >= 0 then class_size else -1
        }
    }
    def classSize_=(value: Short) = class_size = value

    def hasInterfaces = {
        _interfaces match {
            case Some(i) => i.length > 0
            case None => hasImage && module.exists(m => m.read(this, (`type`, reader) => reader.hasInterfaces(`type`)))
        }

    }
    def interfaces = {
        _interfaces match {
            case Some(i) => i
            case None =>
                val loaded = if (hasImage) module.flatMap(m => Option(m.read(InterfaceImplementationCollection(this), this, (`type`, reader) => reader.readInterfaces(`type`).getOrElse(InterfaceImplementationCollection(this))))).orElse(Some(InterfaceImplementationCollection(this)))
                             else Some(InterfaceImplementationCollection(this))
                _interfaces = loaded
                loaded.getOrElse(InterfaceImplementationCollection(this))
        }

    }
    def hasNestedTypes = {
        _nested_types match {
            case Some(t) => t.length > 0
            case None => hasImage && module.exists(m => m.read(this, (`type`, reader) => reader.hasNestedTypes(`type`)))
        }
    
    }
    def nestedTypes = {
        _nested_types match {
            case Some(t) => t
            case None =>
                val loaded = if (hasImage) module.map(m => m.read(MemberDefinitionCollection[TypeDefinition](this), this, (t, reader) => reader.readNestedTypes(t).getOrElse(MemberDefinitionCollection[TypeDefinition](this)))).getOrElse(MemberDefinitionCollection[TypeDefinition](this))
                             else MemberDefinitionCollection[TypeDefinition](this)
                _nested_types = Some(loaded)
                loaded

        }
    }
    def hasMethods = {
        _methods match {
            case Some(m) => m.length > 0
        
            case None => hasImage && methods_range.exists(_.length > 0)
        }
    
    }
    def methods = {
        _methods match {
            case Some(m) => m

            case None =>
                val loaded = if (hasImage) module.map(m => m.read(MemberDefinitionCollection[MethodDefinition](this), this, (t, reader) => reader.readMethods(t).getOrElse(MemberDefinitionCollection[MethodDefinition](this)))).getOrElse(MemberDefinitionCollection[MethodDefinition](this))
                             else MemberDefinitionCollection[MethodDefinition](this)
                _methods = Some(loaded)
                loaded

        }
    }
    def hasFields = {
        _fields match {
            case Some(f) => f.length > 0
        
            case None => hasImage && fields_range.exists(_.length > 0)
        }
    
    }
    def fields = {
        _fields match {
            case Some(f) => f

            case None =>
                val loaded = if (hasImage) module.map(m => m.read(MemberDefinitionCollection[FieldDefinition](this), this, (t, reader) => reader.readFields(t).getOrElse(MemberDefinitionCollection[FieldDefinition](this)))).getOrElse(MemberDefinitionCollection[FieldDefinition](this))
                             else MemberDefinitionCollection[FieldDefinition](this)
                _fields = Some(loaded)
                loaded

        }
    }
    def hasEvents = {
        _events match {
            case Some(e) => e.length > 0
            case None => false
        }
    
    }
    def events = {
        _events match {
            case Some(e) => e

            case None =>
                val loaded = if (hasImage) module.map(m => m.read(MemberDefinitionCollection[EventDefinition](this), this, (t, reader) => reader.readEvents(t).getOrElse(MemberDefinitionCollection[EventDefinition](this)))).getOrElse(MemberDefinitionCollection[EventDefinition](this))
                             else MemberDefinitionCollection[EventDefinition](this)
                _events = Some(loaded)
                loaded

        }
    }
    def hasProperties = {
        _properties match {
            case Some(p) => p.length > 0
            case None => false
        }

    }
    def properties = {
        _properties match {
            case Some(p) => p

            case None =>
                val loaded = if (hasImage) module.map(m => m.read(MemberDefinitionCollection[PropertyDefinition](this), this, (t, reader) => reader.readProperties(t).getOrElse(MemberDefinitionCollection[PropertyDefinition](this)))).getOrElse(MemberDefinitionCollection[PropertyDefinition](this))
                             else MemberDefinitionCollection[PropertyDefinition](this)
                _properties = Some(loaded)
                loaded

        }
    }
    def hasSecurityDeclarations = {
        _security_declarations match {
            case Some(d) => d.length > 0
            case None => false
        }

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
        _custom_attributes match {
            case Some(a) => a.length > 0
        
            case None => module.exists(m => getHasCustomAttributes(Some(m)))
        }
    
    }
    def customAttributes = {
        _custom_attributes match {
            case Some(a) => a

            case None =>
                val loaded = module.map(m => getCustomAttributes(ArrayBuffer.empty[CustomAttribute], Some(m))).getOrElse(ArrayBuffer.empty[CustomAttribute])
                _custom_attributes = Some(loaded)
                loaded

        }
    }
    override def hasGenericParameters = {
        generic_parameters match {
            case Some(gp) => gp.length > 0
            case None => getHasGenericParameters(module)
        }

    }
    override def genericParameters =  {
        generic_parameters match {
            case Some(gp) => gp
            case None =>
                val loaded = getGenericParameters(ArrayBuffer.empty[GenericParameter], module)
                generic_parameters = Some(loaded)
                loaded
        }

    }
    def hasCustomDebugInformations = {
        _custom_infos match {
            case Some(i) => i.length > 0
        
            case None => false
        }

    }
    def customDebugInformations = {
        _custom_infos match {
            case Some(i) => i

            case None =>
                val loaded = ArrayBuffer.empty[CustomDebugInformation]
                _custom_infos = Some(loaded)
                loaded

    // TypeAttributes

        }
    }
    def isNotPublic = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.notPublic.value)
    }
    def isNotPublic_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.notPublic.value, value)

    }
    def isPublic = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.public.value)
    }
    def isPublic_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.public.value, value)

    }
    def isNestedPublic = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedPublic.value)
    }
    def isNestedPublic_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedPublic.value, value)

    }
    def isNestedPrivate = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedPrivate.value)
    }
    def isNestedPrivate_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedPrivate.value, value)

    }
    def isNestedFamily = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamily.value)
    }
    def isNestedFamily_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamily.value, value)

    }
    def isNestedAssembly = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedAssembly.value)
    }
    def isNestedAssembly_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedAssembly.value, value)

    }
    def isNestedFamilyAndAssembly = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamANDAssem.value)
    }
    def isNestedFamilyAndAssembly_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamANDAssem.value, value)

    }
    def isNestedFamilyOrAssembly = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamORAssem.value)
    }
    def isNestedFamilyOrAssembly_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamORAssem.value, value)

    }
    def isAutoLayout = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.autoLayout.value)
    }
    def isAutoLayout_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.autoLayout.value, value)

    }
    def isSequentialLayout = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.sequentialLayout.value)
    }
    def isSequentialLayout_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.sequentialLayout.value, value)

    }
    def isExplicitLayout = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.explicitLayout.value)
    }
    def isExplicitLayout_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.explicitLayout.value, value)

    }
    def isClass = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.classSemanticMask.value, TypeAttributes.`class`.value)
    }
    def isClass_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.classSemanticMask.value, TypeAttributes.`class`.value, value)

    }
    def isInterface = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.classSemanticMask.value, TypeAttributes.interface.value)
    }
    def isInterface_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.classSemanticMask.value, TypeAttributes.interface.value, value)

    }
    def isAbstract = {
        MemberDefinition.getAttributes(attributes, TypeAttributes.`abstract`.value)
    }
    def isAbstract_=(value: Boolean) = {
        _attributes = MemberDefinition.setAttributes(attributes, TypeAttributes.`abstract`.value, value)

    }
    def isSealed = {
        MemberDefinition.getAttributes(attributes, TypeAttributes.`sealed`.value)
    }
    def isSealed_=(value: Boolean) = {
        _attributes = MemberDefinition.setAttributes(attributes, TypeAttributes.`sealed`.value, value)

    }
    def isSpecialName = {
        MemberDefinition.getAttributes(_attributes, TypeAttributes.specialName.value)
    }
    def isSpecialName_=(value: Boolean) = {
        _attributes = MemberDefinition.setAttributes(_attributes, TypeAttributes.specialName.value, value)

    }
    def isImport = {
        MemberDefinition.getAttributes(attributes, TypeAttributes.`import`.value)
    }
    def isImport_=(value: Boolean) = {
        _attributes = MemberDefinition.setAttributes(attributes, TypeAttributes.`import`.value, value)

    }
    def isSerializable = {
        MemberDefinition.getAttributes(attributes, TypeAttributes.serializable.value)
    }
    def isSerializable_=(value: Boolean) = {
        _attributes = MemberDefinition.setAttributes(attributes, TypeAttributes.serializable.value, value)

    }
    def isWindowsRuntime = {
        MemberDefinition.getAttributes(attributes, TypeAttributes.windowsRuntime.value)
    }
    def isWindowsRuntime_=(value: Boolean) = {
        _attributes = MemberDefinition.setAttributes(attributes, TypeAttributes.windowsRuntime.value, value)

    }
    def isAnsiClass = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.stringFormatMask.value, TypeAttributes.ansiClass.value)
    }
    def isAnsiClass_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.stringFormatMask.value, TypeAttributes.ansiClass.value, value)

    }
    def isUnicodeClass = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.stringFormatMask.value, TypeAttributes.unicodeClass.value)
    }
    def isUnicodeClass_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.stringFormatMask.value, TypeAttributes.unicodeClass.value, value)

    }
    def isAutoClass = {
        MemberDefinition.getMaskedAttributes(_attributes, TypeAttributes.stringFormatMask.value, TypeAttributes.autoClass.value)
    }
    def isAutoClass_=(value: Boolean) = {
        _attributes = MemberDefinition.setMaskedAttributes(_attributes, TypeAttributes.stringFormatMask.value, TypeAttributes.autoClass.value, value)

    }
    def isBeforeFieldInit = {
        MemberDefinition.getAttributes(_attributes, TypeAttributes.beforeFieldInit.value)
    }
    def isBeforeFieldInit_=(value: Boolean) = {
        _attributes = MemberDefinition.setAttributes(_attributes, TypeAttributes.beforeFieldInit.value, value)

    }
    def isRuntimeSpecialName = {
        MemberDefinition.getAttributes(_attributes, TypeAttributes.rtSpecialName.value)
    }
    def isRuntimeSpecialName_=(value: Boolean) = {
        _attributes = MemberDefinition.setAttributes(_attributes, TypeAttributes.rtSpecialName.value, value)

    }
    def hasSecurity = {
        MemberDefinition.getAttributes(_attributes, TypeAttributes.hasSecurity.value)
    }
    def hasSecurity_=(value: Boolean) = {
        _attributes = MemberDefinition.setAttributes(_attributes, TypeAttributes.hasSecurity.value, value)

    }
    def isEnum = base_type.exists(_.isTypeOf("System", "Enum"))

    override def isValueType: Boolean = {
        base_type match {
            case None => false
            case Some(bt) => bt.isTypeOf("System", "Enum") || (bt.isTypeOf("System", "ValueType") && !this.isTypeOf("System", "Enum"))
        }
    }
    override def isValueType_=(value: Boolean): Unit = throw OperationNotSupportedException()

    override def isPrimitive: Boolean = {
        MetadataSystem.tryGetPrimitiveElementType(this) match {
            case Some(primitive_etype) => primitive_etype.isPrimitive
            case None => false
        
        }
    }
    override def metadataType: MetadataType = {
        MetadataSystem.tryGetPrimitiveElementType(this) match {
            case Some(primitive_type) => primitive_type.asMetadataType
            case None => super.metadataType

        }
    }
    override def isDefinition: Boolean = true

    def declaringTypeTD:TypeDefinition = super.declaringType.asInstanceOf[TypeDefinition]
    def declaringTypeTD_=(value: TypeDefinition): Unit = super.declaringType = value

    def windowsRuntimeProjectionTD: TypeDefinitionProjection = projection.map(_.asInstanceOf[TypeDefinitionProjection]).getOrElse(throw OperationNotSupportedException())
    def windowsRuntimeProjectionTD_=(value: TypeDefinitionProjection): Unit = projection = Some(value)
    
    def getEnumUnderlyingType() = {
        val fields = this.fields
        fields.find((f) => !f.isStatic) match {
            case Some(field) => Some(field.fieldType)
            case None => throw IllegalArgumentException()

        
        }
    }
    def getNestedType(fullname: String): Option[TypeDefinition] = {
        if (!hasNestedTypes) {
            None
        }
        else {
            nestedTypes.find((nt) => nt.typeFullName() == fullname) match {
                case Some(nt) => Some(nt)
                case None => None

            }
        }
    }
    override def resolve(): TypeDefinition = this
}


sealed class InterfaceImplementation(theInterfaceType: TypeReference, token: MetadataToken) extends CustomAttributeProvider {
    var _type: Option[TypeDefinition] = None
    var _token: MetadataToken = token
    private var _interface_type: TypeReference = theInterfaceType
    private var _custom_attributes: Option[ArrayBuffer[CustomAttribute]] = None

    def interfaceType = _interface_type
    def interfaceType_=(value: TypeReference) = _interface_type = value

    def hasCustomAttributes = {
        _custom_attributes match {
            case Some(a) => a.length > 0
            case None =>
                _type match {
                    case Some(t) => this.getHasCustomAttributes(t.module)
                    case None => false
                }
        }
    
    }
    def customAttributes = {
        _custom_attributes match {
            case Some(a) => a
            case None =>
                val loaded = _type match {
                    case Some(t) => getCustomAttributes(ArrayBuffer.empty[CustomAttribute], t.module)
                    case None => ArrayBuffer.empty[CustomAttribute]
                }
                _custom_attributes = Some(loaded)
                loaded
        }
    }
    def metadataToken: Option[MetadataToken] = Some(_token)
    def metadataToken_=(value: MetadataToken) = _token = value

    def this(theInterfaceType: TypeReference) = {
        this(theInterfaceType, MetadataToken(TokenType.interfaceImpl))
    }
}

class InterfaceImplementationCollection(private val `type`: TypeDefinition, capacity: Int = 0) extends ArrayBuffer[InterfaceImplementation](capacity) {
    override def addOne(elem: InterfaceImplementation): this.type = {
        val result = super.addOne(elem)
        elem._type = Some(`type`)
        this
    
    }
    override def insert(index: Int, elem: InterfaceImplementation): Unit = {
        super.insert(index, elem)
        elem._type = Some(`type`)
    
    }
    override def update(index: Int, elem: InterfaceImplementation): Unit = {
        super.update(index, elem)
        elem._type = Some(`type`)
    
    }
    override def remove(index: Int): InterfaceImplementation = {
        val elem = super.remove(index)
        elem._type = None
        elem
    }
}