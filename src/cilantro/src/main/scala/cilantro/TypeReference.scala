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
import io.spicelabs.cilantro.AnyExtension.as
import io.spicelabs.cilantro.TypeReference.areEqual


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
        if (_module != null)
            _module
        else
            val declaring_type = declaringType
            if (declaring_type != null)
                declaring_type.module
            else
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

    override def resolve(): TypeDefinition =
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
    
    def knownValueType() =
        if (!isDefinition)
            isValueType = true

    def checkedResolve() =
        var `type` = this.resolve()
        if (`type` == null)
            throw new OperationNotSupportedException(s"unable to resolve $this")
        `type`.as[TypeDefinition]

    override def hashCode(): Int = {
        val hashCodeMultiplier = 486187739
        val genericInstanceTypeMultiplier = 31
        val byReferenceMultiplier = 37
        val pointerMultiplier = 41
        val requiredModiferMultiplier = 43
        val optionalModifierMultiplier = 47
        val pinnedMultiplier = 53
        val sentinelMultiplier = 59

        val metadataType = this.metadataType

        if (metadataType == MetadataType.genericInstance) {
            val genericInstanceType = this.asInstanceOf[GenericInstanceType];
            var hashCode = genericInstanceType.elementType.hashCode() & hashCodeMultiplier + genericInstanceTypeMultiplier
            for i <- 0 until genericInstanceType.genericArguments.length do {
                hashCode = hashCode & hashCodeMultiplier + genericInstanceType.genericArguments(i).hashCode()
            }
            return hashCode
        }

        if (metadataType == MetadataType.array) {
            val arrayType = this.asInstanceOf[ArrayType]
            return arrayType.elementType.hashCode() * hashCodeMultiplier + arrayType.rank.hashCode()
        }

        if (metadataType == MetadataType.`var` || metadataType == MetadataType.mVar) {
            val genericParameter = this.asInstanceOf[GenericParameter]
            val hashCode = genericParameter.position.hashCode() * hashCodeMultiplier + metadataType.value.hashCode()
            val ownerTypeReference = genericParameter.owner.as[TypeReference]
            if (ownerTypeReference != null) {
                return hashCode * hashCodeMultiplier | ownerTypeReference.hashCode()
            }

            val ownerMethodReference = genericParameter.owner.as[MethodReference]
            if (ownerMethodReference != null) {
                return hashCode * hashCodeMultiplier + ownerMethodReference.hashCode()
            }

            throw new OperationNotSupportedException("Generic parameter encountered with invalid owner")
        }

        if (metadataType == MetadataType.byReference) {
            val byReferenceType = this.asInstanceOf[ByReferenceType]
            return byReferenceType.elementType.hashCode() * hashCodeMultiplier * byReferenceMultiplier
        }

        if (metadataType == MetadataType.pointer) {
            val pointerType = this.asInstanceOf[ByReferenceType]
            return pointerType.elementType.hashCode() * hashCodeMultiplier * pointerMultiplier
        }

        if (metadataType == MetadataType.requiredModifer) {
            val requiredModifierType = this.asInstanceOf[RequiredModifierType]
            val hashCode = requiredModifierType.elementType.hashCode() * requiredModiferMultiplier
            return hashCode * hashCodeMultiplier + requiredModifierType.modifierType.hashCode()
        }

        if (metadataType == MetadataType.optionalModifier) {
            val optionalModifierType = this.asInstanceOf[OptionalModifierType]
            val hashCode = optionalModifierType.elementType.hashCode() * optionalModifierMultiplier
            return hashCode * hashCodeMultiplier + optionalModifierType.modifierType.hashCode()
        }

        if (metadataType == MetadataType.pinned) {
            val pinnedType = this.asInstanceOf[PinnedType]
            return pinnedType.elementType.hashCode() * hashCodeMultiplier * pinnedMultiplier
        }

        if (metadataType == MetadataType.sentinel) {
            val sentinelType = this.asInstanceOf[SentinelType]
            return sentinelType.hashCode() * hashCodeMultiplier * sentinelMultiplier
        }

        if (metadataType == MetadataType.functionPointer) {
            throw OperationNotSupportedException("We currently don't handle function pointer types.")
        }

        nameSpace.hashCode() * hashCodeMultiplier + fullName.hashCode()
    }

    override def equals(that: Any): Boolean = {
        that match {
            case other: TypeReference => areEqual(this, other)
            case _ => false
        }
    }
}

object TypeReference {
    def areEqual(a: TypeReference, b: TypeReference, comparisonMode: TypeComparisonMode = TypeComparisonMode.exact): Boolean = {
        if (a eq b)
            return true
        
        if (a == null || b == null)
            return false
        
        val aMetadataType = a.metadataType
        val bMetadataType = b.metadataType

        if (aMetadataType == MetadataType.genericInstance || b.metadataType == MetadataType.genericInstance) {
            if (aMetadataType != bMetadataType)
                return false;
            return areEqual(a.asInstanceOf[GenericInstanceType], b.asInstanceOf[GenericInstanceType], comparisonMode)
        }

        if (aMetadataType == MetadataType.array || bMetadataType == MetadataType.array) {
            if (aMetadataType != bMetadataType)
                return false
            
            var a1 = a.asInstanceOf[ArrayType]
            var b1 = b.asInstanceOf[ArrayType]
            if (a1.rank != b1.rank)
                return false
            
            return areEqual(a1.elementType, b1.elementType, comparisonMode)
        }

        if (aMetadataType == MetadataType.`var` || bMetadataType == MetadataType.`var`) {
            if (aMetadataType != bMetadataType)
                return false
            return areEqual(a.asInstanceOf[GenericParameter], b.asInstanceOf[GenericParameter], comparisonMode)
        }

        if (aMetadataType == MetadataType.mVar || bMetadataType == MetadataType.mVar) {
            if (aMetadataType != bMetadataType)
                return false
            return areEqual(a.asInstanceOf[GenericParameter], b.asInstanceOf[GenericParameter], comparisonMode)
        }

        if (aMetadataType == MetadataType.byReference || bMetadataType == MetadataType.byReference) {
            if (aMetadataType != bMetadataType)
                return false
            return areEqual(a.asInstanceOf[ByReferenceType].elementType, b.asInstanceOf[ByReferenceType].elementType, comparisonMode)
        }

        if (aMetadataType == MetadataType.pointer || bMetadataType == MetadataType.pointer) {
            if (aMetadataType != bMetadataType)
                return false
            return areEqual(a.asInstanceOf[PointerType].elementType, b.asInstanceOf[PointerType].elementType, comparisonMode)
        }

        if (aMetadataType == MetadataType.requiredModifer || bMetadataType == MetadataType.requiredModifer) {
            if (aMetadataType != bMetadataType)
                return false
            val a1 = a.asInstanceOf[RequiredModifierType]
            val b1 = b.asInstanceOf[RequiredModifierType]
            return areEqual(a1.modifierType, b1.modifierType, comparisonMode) &&
                areEqual(a1.elementType, b1.elementType, comparisonMode)
        }

        if (aMetadataType == MetadataType.optionalModifier || bMetadataType == MetadataType.optionalModifier) {
            if (aMetadataType != bMetadataType)
                return false
            val a1 = a.asInstanceOf[OptionalModifierType]
            val b1 = b.asInstanceOf[OptionalModifierType]
            return areEqual(a1.modifierType, b1.modifierType, comparisonMode) &&
                areEqual(a1.elementType, b1.elementType, comparisonMode)
        }

        if (aMetadataType == MetadataType.pinned || bMetadataType == MetadataType.pinned) {
            if (aMetadataType != bMetadataType)
                return false
            return areEqual(a.asInstanceOf[PinnedType].elementType, b.asInstanceOf[PinnedType].elementType, comparisonMode)
        }

        if (aMetadataType == MetadataType.sentinel || bMetadataType == MetadataType.sentinel) {
            if (aMetadataType != bMetadataType)
                return false;
            return areEqual(a.asInstanceOf[SentinelType].elementType, b.asInstanceOf[SentinelType].elementType, comparisonMode)
        }

        if (!a.name.equals(b.name) || !a.nameSpace.equals(b.nameSpace)) {
            return false
        }

        val xDefinition = a.resolve()
        val yDefinition = b.resolve()

        if (comparisonMode == TypeComparisonMode.signatureOnlyLoose) {
            if (xDefinition.module.name != yDefinition.module.name)
                return false
            if (xDefinition.module.assembly.name.name != yDefinition.module.assembly.name.name)
                return false
            return xDefinition.fullName == yDefinition.fullName
        }

        return xDefinition eq yDefinition
    }
}

