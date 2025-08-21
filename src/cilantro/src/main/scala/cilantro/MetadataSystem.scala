//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/MetadataSystem.cs

package io.spicelabs.cilantro

import scala.collection.mutable.HashMap
import scala.collection.mutable.ArrayBuffer
import io.spicelabs.cilantro.metadata.Row2
import io.spicelabs.cilantro.metadata.Row3
import io.spicelabs.cilantro.metadata.Row6
import io.spicelabs.cilantro.metadata.ElementType
import java.util.UUID
import scala.annotation.tailrec

class Range(var index: Int, var length: Int) {
    def this() = this(0, 0)
}

sealed class MetadataSystem {
    var _assemblyReferences: Array[AssemblyNameReference] = null
    var _moduleReferences: Array[ModuleReference] = null

    var _types: Array[TypeDefinition] = null
    var _typeReferences: Array[TypeReference] = null

    var _fields: Array[FieldDefinition] = null
    var _methods: Array[MethodDefinition] = null
    var _memberReferences: Array[MemberReference] = null

    var _nestedTypes: HashMap[Int, ArrayBuffer[Int]] = null
    var _reverseNestedTypes: HashMap[Int, Int] = null
    var _interfaces: HashMap[Int, ArrayBuffer[Row2[Int, MetadataToken]]] = null
    var _classLayouts: HashMap[Int, Row2[Char, Int]] = null
    var _fieldLayouts: HashMap[Int, Int] = null
    var _fieldRVAs: HashMap[Int, Int] = null
    var _fieldMarshals: HashMap[MetadataToken, Int] = null
    var _constants: HashMap[MetadataToken, Row2[ElementType, Int]] = null
    var _overrides: HashMap[Int, ArrayBuffer[MetadataToken]] = null
    var _customAttributes: HashMap[MetadataToken, ArrayBuffer[Range]] = null
    var _securityDeclarations: HashMap[MetadataToken, ArrayBuffer[Range]] = null
    var _events: HashMap[Int, Range] = null
    var _properties: HashMap[Int, Range] = null
    // TODO
    // var _semantics: HashMap[Int, Row2[MethodSemanticsAttributes, MetadataToken]] = null
    // var _pInvokes: HashMap[Int, Row3[PInvokeAttributes, Int, Int]] = null
    var _genericParameters: HashMap[MetadataToken, Array[Range]] = null
    var _genericConstraints: HashMap[Int, ArrayBuffer[Row2[Int, MetadataToken]]] = null
    
    // TODO
    // var _documents: Array[Document] = null
    var _localScopes: HashMap[Int, ArrayBuffer[Row6[Int, Range, Range, Int, Int, Int]]] = null
    // TODO
    // var _importScopes: Array[ImportDebugInformation] = null
    var _stateMachineMethods: HashMap[Int, Int] = null
    var _customDebugInformation: HashMap[MetadataToken, Array[Row3[UUID, Int, Int]]] = null


    def clear() =
        if (_nestedTypes != null) _nestedTypes.clear()
        if (_reverseNestedTypes != null) _reverseNestedTypes.clear()
        if (_interfaces != null) _interfaces.clear()
        if (_classLayouts != null) _classLayouts.clear()
        if (_fieldLayouts != null) _fieldLayouts.clear()
        if (_fieldRVAs != null) _fieldLayouts.clear()
        if (_fieldMarshals != null) _fieldMarshals.clear()
        if (_constants != null) _constants.clear()
        if (_overrides != null) _overrides.clear()
        if (_customAttributes != null) _customAttributes.clear()
        if (_securityDeclarations != null) _securityDeclarations.clear()
        if (_events != null) _events.clear()
        if (_properties != null) _properties.clear()
        // TODO
        // if (_semantics != null) _semantics.clear()
        // if (_pInvokes != null) _pInvokes.clear()
        if (_genericParameters != null) _genericParameters.clear()
        if (_genericConstraints != null) _genericConstraints.clear()

        // _documents = Array[Document].empty
        // _importScopes = Array[ImportDebugInformation].empty
        if (_localScopes != null) _localScopes.clear()
        if (_stateMachineMethods != null) _stateMachineMethods.clear()

    def getAssemblyNameReference(rid: Int) =
        if (rid < 1 || rid > _assemblyReferences.length)
            null
        else
            _assemblyReferences(rid - 1)
    
    def getTypeDefinition(rid: Int) =
        if (rid < 1 || rid > _types.length)
            null
        else
            _types(rid - 1)

    def addTypeDefinition(`type`: TypeDefinition) =
        _types(`type`.token.RID - 1) = `type`
    
    def getTypeReference(rid: Int) =
        if (rid < 1 || rid > _typeReferences.length)
            null
        else
            _typeReferences(rid - 1)
    
    def addTypeReference(`type`: TypeReference) =
        _typeReferences(`type`.token.RID - 1) = `type`

    def getFieldDefinition(rid: Int) = 
        if (rid < 1 || rid > _fields.length)
            null
        else
            _fields(rid - 1)

    def addFieldDefinition(field: FieldDefinition) =
        _fields(field.token.RID - 1) = field


    def getMethodDefinition(rid: Int) = 
        if (rid < 1 || rid > _methods.length)
            null
        else
            _methods(rid - 1)

    def addMethodDefinition(method: MethodDefinition) =
        _methods(method.token.RID - 1) = method


    def getMemberReference(rid: Int) =
        if (rid < 1 || rid > _memberReferences.length)
            null
        else
            _memberReferences(rid - 1)
    
    def addMemberReference(member: MemberReference) =
        _memberReferences(member.token.RID - 1) = member

    def tryGetNestedTypeMapping(`type`: TypeDefinition) =
        _nestedTypes.get(`type`.token.RID)
    
    def setNestedTypeMapping(type_rid: Int, mapping: ArrayBuffer[Int]) =
        _nestedTypes.update(type_rid, mapping)
    
    def tryGetReverseNestedTypeMapping(`type`: TypeDefinition) =
        _reverseNestedTypes.get(`type`.token.RID)
    
    def setReverseNestedTypeMapping(nested: Int, declaring: Int) =
        _reverseNestedTypes.update(nested, declaring)

    def tryGetInterfaceMapping(`type`: TypeDefinition) =
        _interfaces.get(`type`.token.RID)
    
    def setInterfaceMapping(type_rid: Int, mapping: ArrayBuffer[Row2[Int, MetadataToken]]) =
        _interfaces.update(type_rid, mapping)
    
    def addPropertiesRange(type_rid: Int, range: Range) =
        _properties.update(type_rid, range)
    
    def tryGetPropertiesRange(`type`: TypeDefinition) =
        _properties.get(`type`.token.RID)

    def addEventsRange(type_rid: Int, range: Range) =
        _events.update(type_rid, range)
    
    def tryGetEventsRange(`type`: TypeDefinition) =
        _events.get(`type`.token.RID)

    def tryGetGenericParameterRanges(owner: GenericParameterProvider) =
        _genericParameters.get(owner.metadataToken)

    def tryGetCustomAttributeRanges(owner: CustomAttributeProvider) =
        _customAttributes.get(owner.metadataToken)

    def tryGetSecurityDeclarationRanges(owner: SecurityDeclarationProvider) =
        _securityDeclarations.get(owner.metadataToken)
    
    def tryGetGenericConstraintMapping(generic_parameter: GenericParameter) =
        _genericConstraints.get(generic_parameter.token.RID)

    def setGenericConstraintMapping(gp_rid: Int, mapping: ArrayBuffer[Row2[Int, MetadataToken]]) =
        _genericConstraints.update(gp_rid, mapping)

    def tryGetOverrideMapping(method: MethodDefinition) =
        _overrides.get(method.token.RID)
    
    def setOverrideMapping(rid: Int, mapping: ArrayBuffer[MetadataToken]) =
        _overrides.update(rid, mapping)

    // TODO
    // def getDocument(rid: Int) =
    //     if (rid < 1 || rid > docuemnts.length)
    //         null
    //     else
    //         _documents(rid - 1)

    def tryGetLocalScopes(method: MethodDefinition) =
        _localScopes.get(method.metadataToken.RID)
    
    def setLocalScopes(method_rid: Int, records: ArrayBuffer[Row6[Int, Range, Range, Int, Int, Int]]) =
        _localScopes.update(method_rid, records)

    // TODO
    // def getImportScope(rid: Int) =
    //     if (rid < 1 || rid > _importScopes.length)
    //         null
    //     else
    //         _importScopes(rid - 1)
    
    def tryGetStateMachineKickOffMethod(method: MethodDefinition) =
        _stateMachineMethods.get(method.metadataToken.RID)
    
    import MetadataSystem.binaryRangeSearch

    def getFieldDeclaringType(field_rid: Int) =
        binaryRangeSearch(_types, field_rid, true)
    
    def getMethodDeclaringType(method_rid: Int) =
        binaryRangeSearch(_types, method_rid, false)


}

object MetadataSystem {
    var _primitive_value_types: HashMap[String, Row2[ElementType, Boolean]] = 
        HashMap[String, Row2[ElementType, Boolean]] (
            "Void" -> Row2(ElementType.void, false),
            "Boolean" -> Row2(ElementType.boolean, true),
            "Char" -> Row2(ElementType.char, true),
            "SByte" -> Row2(ElementType.i1, true),
            "Byte" -> Row2(ElementType.u1, true),
            "Int16" -> Row2(ElementType.i2, true),
            "Uint16" -> Row2(ElementType.u2, true),
            "Int32" -> Row2(ElementType.i4, true),
            "UInt32" -> Row2(ElementType.u4, true),
            "Int64" -> Row2(ElementType.i8, true),
            "UInt64" -> Row2(ElementType.u8, true),
            "Single" -> Row2(ElementType.r4, true),
            "Double" -> Row2(ElementType.r8, true),
            "String" -> Row2(ElementType.string, false),
            "TypedReference" -> Row2(ElementType.typedByRef, false),
            "IntPtr" -> Row2(ElementType.i, true),
            "UIntPtr" -> Row2(ElementType.u, true),
            "Object" -> Row2(ElementType.`object`, true)
        )

    def tryProcessPrimitiveTypeReference(`type`: TypeReference): Unit =
        if (`type`.nameSpace != "System")
            return ()
        
        val scope = `type`.scope
        if (scope == null || scope.metadataScopeType != MetadataScopeType.assemblyNameReference)
            return ()
        
        val primitive_data = tryGetPrimitiveData(`type`) match
            case Some(p) => p
            case None => return ()
        
        `type`.etype = primitive_data.col1
        `type`.isValueType = primitive_data.col2
    
    def tryGetPrimitiveElementType(`type`: TypeDefinition): Option[ElementType] =
        if (`type`.nameSpace != "System")
            return None
        
        tryGetPrimitiveData(`type`) match
            case Some(p) => Some(p.col1)
            case None => None
    
    def tryGetPrimitiveData(`type`: TypeReference): Option[Row2[ElementType, Boolean]] =
        _primitive_value_types.get(`type`.name)

    def binaryRangeSearch(types: Array[TypeDefinition], rid: Int, field: Boolean) =
        binaryRangeSearchRec(types, rid, field, 0, types.length - 1)
    
    @tailrec
    def binaryRangeSearchRec(types: Array[TypeDefinition], rid: Int, field: Boolean, min: Int, max: Int): TypeDefinition =
        if (min > max)
            null
        else
            val mid = min + ((max - min) / 2)
            val `type` = types(mid)
            val range = if field then `type`.fields_range else `type`.methods_range
            if (rid < range.index)
                binaryRangeSearchRec(types, rid, field, min, mid - 1)
            else if (rid >= range.index + range.length)
                binaryRangeSearchRec(types, rid, field, mid + 1, max)
            else
                `type`


        
}