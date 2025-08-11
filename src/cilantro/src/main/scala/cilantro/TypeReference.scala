//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/TypeReference.cs

package io.spicelabs.cilantro

import io.spicelabs.cilantro.metadata.ElementType
import scala.collection.mutable.ArrayBuffer
import javax.naming.OperationNotSupportedException


enum MetadataType(val value: Byte) {
    case void extends MetadataType(ElementType.void.value)
    case boolean extends MetadataType(ElementType.boolean.value)
    case char extends MetadataType(ElementType.char.value)
    case sByte extends MetadataType(ElementType.i1.value)
    case byte extends MetadataType(ElementType.u1.value)
    case int16 extends MetadataType(ElementType.i2.value)
    case uint16 extends MetadataType(ElementType.u2.value)
    case int32 extends MetadataType(ElementType.i4.value)
    case uint32 extends MetadataType(ElementType.u4.value)
    case int64 extends MetadataType(ElementType.i8.value)
    case uint64 extends MetadataType(ElementType.u8.value)
    case single extends MetadataType(ElementType.r4.value)
    case `double` extends MetadataType(ElementType.r8.value)
    case string extends MetadataType(ElementType.string.value)
    case pointer extends MetadataType(ElementType.ptr.value)
    case byReference extends MetadataType(ElementType.byRef.value)
    case valueType extends MetadataType(ElementType.valueType.value)
    case `class` extends MetadataType(ElementType.`class`.value)
    case `var` extends MetadataType(ElementType.`var`.value)
    case array extends MetadataType(ElementType.array.value)
    case genericInstance extends MetadataType(ElementType.genericInst.value)
    case typedByReference extends MetadataType(ElementType.typedByRef.value)
    case intPtr extends MetadataType(ElementType.i.value)
    case uintPtr extends MetadataType(ElementType.u.value)
    case functionPointer extends MetadataType(ElementType.fnPtr.value)
    case `object` extends MetadataType(ElementType.`object`.value)
    case mVar extends MetadataType(ElementType.mVar.value)
    case requiredModifer extends MetadataType(ElementType.cModReqD.value)
    case optionalModifier extends MetadataType(ElementType.cModOpt.value)
    case sentinel extends MetadataType(ElementType.sentinel.value)
    case pinned extends MetadataType(ElementType.pinned.value)
}

object MetadataType {
  def fromOrdinalValue(value: Int) =
    MetadataType.values.find(x => {x.value == value}) match
      case Some(result) => result
      case None => throw IllegalArgumentException(s"value $value not found in TokenType")

}

class TypeReference(private var _namespace: String, _name: String) extends MemberReference(_name) with GenericParameterProvider with GenericContext { // TODO
    private var value_type: Boolean = false
    var _scope: MetadataScope = null
    var _module: ModuleDefinition = null

    var etype: ElementType = ElementType.none

    private var fullname: String = null

    protected var generic_parameters: ArrayBuffer[GenericParameter] = null

    override def name = super.name
    override def name_=(value: String) =
        if (isWindowsRuntimeProjection && value != super.name)
            throw OperationNotSupportedException("Projected type reference name can't be changed.")
        
        super.name = value
        clearFullName()

    def nameSpace = _namespace
    def nameSpace_=(value: String) =
        if (isWindowsRuntimeProjection && value != _namespace)
            throw OperationNotSupportedException("Projected type reference name can't be changed.")
        
        _namespace = value
        clearFullName()

    def isValueType = value_type
    def isValueType_=(value: Boolean) = value_type = value

    override def module =
        if (module != null) module

        val declaring_type = declaringType
        if (declaring_type != null)
            declaring_type.module
        null

    def windowsRuntimeProjection = projection.asInstanceOf[TypeReferenceProjection]
    def windowsRuntimeProjection_=(value: TypeReferenceProjection) = projection = value

    override def `type`: GenericParameterProvider = this

    override def method: GenericParameterProvider = null

    override def genericParameterType: GenericParameterType = GenericParameterType.`type`

    def hasGenericParameters:Boolean =
        generic_parameters == null || generic_parameters.length == 0
    
    def genericParameters: ArrayBuffer[GenericParameter] =
        if (generic_parameters == null)
            generic_parameters = GenericParameterCollection(this)
        generic_parameters.asInstanceOf[ArrayBuffer[GenericParameter]]

    def scope:MetadataScope =
        val declaring_type = declaringType
        if (declaring_type != null)
            declaring_type.scope
        
        _scope
    
    def scope_=(value: MetadataScope):Unit =
        val declaring_type = declaringType
        if (declaring_type != null)
            if (isWindowsRuntimeProjection && value != declaring_type.scope)
                throw OperationNotSupportedException("Projected type reference scope can't be changed.")
            declaring_type.scope = value

        if (isWindowsRuntimeProjection && value != _scope)
            throw OperationNotSupportedException("Projected type reference scope can't be changed.")
        
        _scope = value

    def isNested = declaringType != null

    override def declaringType: TypeReference = super.declaringType
    override def declaringType_=(value: TypeReference) =
        if (isWindowsRuntimeProjection && value != super.declaringType)
            throw OperationNotSupportedException("Projected type declaring type can't be changed.")
        super.declaringType = value
        clearFullName()

    override def fullName =
        if (fullname != null)
            fullname
        
        var new_fullname = this.typeFullName()
        if (isNested)
            new_fullname = declaringType.fullName + "/" + new_fullname
        fullname = new_fullname
        fullname

    def isByReference = false
    def isPointer = false
    def isSentinel = false
    def isArray = false
    def isGenericParameter = false
    def isGenericInstance = false
    def isRequiredModifier = false
    def isOptionalModifier = false
    def isPinned = false
    def isFunctionPointer = false
    def isPrimitive = false

    def metadataType =
        etype match
            case ElementType.none => if isValueType then MetadataType.valueType else MetadataType.`class`
            case el: ElementType  => MetadataType.fromOrdinalValue(el.value)


    def this(namespace: String, name: String, module: ModuleDefinition, scope: MetadataScope) =
        this(namespace, name)
        _module = module
        _scope = scope

    def this(namespace: String, name: String, module: ModuleDefinition, scope: MetadataScope, valueType: Boolean) =
        this(namespace, name, module, scope)
        value_type = valueType

    protected def clearFullName() =
        this.fullname = null
    
    def getElementType(): TypeReference = this

    override def resolveDefinition() = resolve()

    override def resolve(): MemberDefinition =
        var module = this.module
        if (module == null)
            throw new OperationNotSupportedException()
        module.resolve(this)

    def typeFullName() =
        if (this.nameSpace == null || this.nameSpace.isEmpty)
            this.name
        else
            this.nameSpace + "." + this.name

    def isTypeOf(namespace: String, name: String) =
        this._name == name && this._namespace == namespace
    
    def isTypeSpecification() =
        etype match
            case ElementType.array |
                ElementType.byRef |
                ElementType.cModOpt |
                ElementType.cModReqD |
                ElementType.fnPtr |
                ElementType.genericInst |
                ElementType.mVar |
                ElementType.pinned |
                ElementType.ptr |
                ElementType.szArray |
                ElementType.sentinel |
                ElementType.`var` => true
            case _ => false
        

}

