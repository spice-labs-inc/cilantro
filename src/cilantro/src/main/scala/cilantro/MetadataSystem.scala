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
    var _assemblyReferences: Array[AssemblyNameReference] = Array.empty
    var _moduleReferences: Array[ModuleReference] = Array.empty

    var _types: Array[TypeDefinition] = Array.empty
    var _typeReferences: Array[TypeReference] = Array.empty

    var _fields: Array[FieldDefinition] = Array.empty
    var _methods: Array[MethodDefinition] = Array.empty
    var _memberReferences: Array[MemberReference] = Array.empty

    var _nestedTypes: HashMap[Int, ArrayBuffer[Int]] = HashMap()
    var _reverseNestedTypes: HashMap[Int, Int] = HashMap()
    var _interfaces: HashMap[Int, ArrayBuffer[Row2[Int, MetadataToken]]] = HashMap()
    var _classLayouts: HashMap[Int, Row2[Char, Int]] = HashMap()
    var _fieldLayouts: HashMap[Int, Int] = HashMap()
    var _fieldRVAs: HashMap[Int, Int] = HashMap()
    var _fieldMarshals: HashMap[MetadataToken, Int] = HashMap()
    var _constants: HashMap[MetadataToken, Row2[ElementType, Int]] = HashMap()
    var _overrides: HashMap[Int, ArrayBuffer[MetadataToken]] = HashMap()
    var _customAttributes: HashMap[MetadataToken, ArrayBuffer[Range]] = HashMap()
    var _securityDeclarations: HashMap[MetadataToken, ArrayBuffer[Range]] = HashMap()
    var _events: HashMap[Int, Range] = HashMap()
    var _properties: HashMap[Int, Range] = HashMap()
    // TODO
    // var _semantics: HashMap[Int, Row2[MethodSemanticsAttributes, MetadataToken]] = HashMap()
    // var _pInvokes: HashMap[Int, Row3[PInvokeAttributes, Int, Int]] = HashMap()
    var _genericParameters: HashMap[MetadataToken, Array[Range]] = HashMap()
    var _genericConstraints: HashMap[Int, ArrayBuffer[Row2[Int, MetadataToken]]] = HashMap()
    
    // TODO
    // var _documents: Array[Document] = Array.empty
    var _localScopes: HashMap[Int, ArrayBuffer[Row6[Int, Range, Range, Int, Int, Int]]] = HashMap()
    // TODO
    // var _importScopes: Array[ImportDebugInformation] = Array.empty
    var _stateMachineMethods: HashMap[Int, Int] = HashMap()
    var _customDebugInformation: HashMap[MetadataToken, Array[Row3[UUID, Int, Int]]] = HashMap()


    def clear() = {
        _nestedTypes.clear()
        _reverseNestedTypes.clear()
        _interfaces.clear()
        _classLayouts.clear()
        _fieldLayouts.clear()
        _fieldLayouts.clear()
        _fieldMarshals.clear()
        _constants.clear()
        _overrides.clear()
        _customAttributes.clear()
        _securityDeclarations.clear()
        _events.clear()
        _properties.clear()
        // TODO
        // if (_semantics != null) _semantics.clear()
        // if (_pInvokes != null) _pInvokes.clear()
        _genericParameters.clear()
        _genericConstraints.clear()

        // _documents = Array[Document].empty
        // _importScopes = Array[ImportDebugInformation].empty
        _localScopes.clear()
        _stateMachineMethods.clear()

    }
    def getAssemblyNameReference(rid: Int) = {
        if (rid < 1 || rid > _assemblyReferences.length) {
            None
        }
        else {
            Option(_assemblyReferences(rid - 1))
    
        }
    }
    def getTypeDefinition(rid: Int) = {
        if (rid < 1 || rid > _types.length) {
            None
        }
        else {
            Option(_types(rid - 1))

        }
    }
    def addTypeDefinition(`type`: TypeDefinition) = {
        `type`.token.foreach(tok => _types(tok.RID - 1) = `type`)
    
    }
    def getTypeReference(rid: Int) = {
        if (rid < 1 || rid > _typeReferences.length) {
            None
        }
        else {
            Option(_typeReferences(rid - 1))
    
        }
    }
    def addTypeReference(`type`: TypeReference) = {
        `type`.token.foreach(tok => _typeReferences(tok.RID - 1) = `type`)

    }
    def getFieldDefinition(rid: Int) = {
        if (rid < 1 || rid > _fields.length) {
            None
        }
        else {
            Option(_fields(rid - 1))
            }
        }
    def addFieldDefinition(field: FieldDefinition) = {
        field.token.foreach(tok => _fields(tok.RID - 1) = field)


    }
    def getMethodDefinition(rid: Int) = {
        if (rid < 1 || rid > _methods.length) {
            None
        }
        else {
            Option(_methods(rid - 1))
            }
        }
    def addMethodDefinition(method: MethodDefinition) = {
        method.token.foreach(tok => _methods(tok.RID - 1) = method)


    }
    def getMemberReference(rid: Int) = {
        if (rid < 1 || rid > _memberReferences.length) {
            None
        }
        else {
            Option(_memberReferences(rid - 1))
    
        }
    }
    def addMemberReference(member: MemberReference) = {
        member.token.foreach(tok => _memberReferences(tok.RID - 1) = member)

    }
    def tryGetNestedTypeMapping(`type`: TypeDefinition) = {
        `type`.token.flatMap(tok => Option(_nestedTypes.get(tok.RID)))
    
    }
    def setNestedTypeMapping(type_rid: Int, mapping: ArrayBuffer[Int]) = {
        _nestedTypes.update(type_rid, mapping)
    
    }
    def tryGetReverseNestedTypeMapping(`type`: TypeDefinition) = {
        `type`.token.flatMap(tok => Option(_reverseNestedTypes.get(tok.RID)))
    
    }
    def setReverseNestedTypeMapping(nested: Int, declaring: Int) = {
        _reverseNestedTypes.update(nested, declaring)

    }
    def tryGetInterfaceMapping(`type`: TypeDefinition) = {
        `type`.token.flatMap(tok => Option(_interfaces.get(tok.RID)))
    
    }
    def setInterfaceMapping(type_rid: Int, mapping: ArrayBuffer[Row2[Int, MetadataToken]]) = {
        _interfaces.update(type_rid, mapping)
    
    }
    def addPropertiesRange(type_rid: Int, range: Range) = {
        _properties.update(type_rid, range)
    
    }
    def tryGetPropertiesRange(`type`: TypeDefinition) = {
        `type`.token.flatMap(tok => Option(_properties.get(tok.RID)))

    }
    def addEventsRange(type_rid: Int, range: Range) = {
        _events.update(type_rid, range)
    
    }
    def tryGetEventsRange(`type`: TypeDefinition) = {
        `type`.token.flatMap(tok => Option(_events.get(tok.RID)))

    }
    def tryGetGenericParameterRanges(owner: GenericParameterProvider) = {
        owner.metadataToken.flatMap(tok => Option(_genericParameters.get(tok)))

    }
    def tryGetCustomAttributeRanges(owner: CustomAttributeProvider) = {
        owner.metadataToken.flatMap(tok => Option(_customAttributes.get(tok)))

    }
    def tryGetSecurityDeclarationRanges(owner: SecurityDeclarationProvider) = {
        owner.metadataToken.flatMap(tok => Option(_securityDeclarations.get(tok)))
    
    }
    def tryGetGenericConstraintMapping(generic_parameter: GenericParameter) = {
        generic_parameter.token.flatMap(tok => Option(_genericConstraints.get(tok.RID)))

    }
    def setGenericConstraintMapping(gp_rid: Int, mapping: ArrayBuffer[Row2[Int, MetadataToken]]) = {
        _genericConstraints.update(gp_rid, mapping)

    }
    def tryGetOverrideMapping(method: MethodDefinition) = {
        method.token.flatMap(tok => Option(_overrides.get(tok.RID)))
    
    }
    def setOverrideMapping(rid: Int, mapping: ArrayBuffer[MetadataToken]) = {
        _overrides.update(rid, mapping)

    // TODO
    // def getDocument(rid: Int) =
    //     if (rid < 1 || rid > docuemnts.length)
    //         null
    //     else
    //         _documents(rid - 1)

    }
    def tryGetLocalScopes(method: MethodDefinition) = {
        method.token.flatMap(tok => Option(_localScopes.get(tok.RID)))
    
    }
    def setLocalScopes(method_rid: Int, records: ArrayBuffer[Row6[Int, Range, Range, Int, Int, Int]]) = {
        _localScopes.update(method_rid, records)

    // TODO
    // def getImportScope(rid: Int) =
    //     if (rid < 1 || rid > _importScopes.length)
    //         null
    //     else
    //         _importScopes(rid - 1)
    
    }
    def tryGetStateMachineKickOffMethod(method: MethodDefinition) = {
        method.token.flatMap(tok => Option(_stateMachineMethods.get(tok.RID)))
    
    }
    import MetadataSystem.binaryRangeSearch

    def getFieldDeclaringType(field_rid: Int) = {
        binaryRangeSearch(_types, field_rid, true)
    
    }
    def getMethodDeclaringType(method_rid: Int) = {
        binaryRangeSearch(_types, method_rid, false)


    }
}

object MetadataSystem {
    var _primitive_value_types: HashMap[String, Row2[ElementType, Boolean]] =  {
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

    }
    def tryProcessPrimitiveTypeReference(`type`: TypeReference): Unit = {
        if (`type`.nameSpace != "System") {
            return ()
        
        }
        val scope = `type`.scope
        if (scope.isEmpty || !scope.exists(_.metadataScopeType == MetadataScopeType.assemblyNameReference)) {
            return ()
        
        }
        val primitive_data = tryGetPrimitiveData(`type`) match {
            case Some(p) => p
            case None => return ()
        
        }
        `type`.etype = primitive_data.col1
        `type`.isValueType = primitive_data.col2
    
    }
    def tryGetPrimitiveElementType(`type`: TypeDefinition): Option[ElementType] = {
        if (`type`.nameSpace != "System") {
            return None
        
        }
        tryGetPrimitiveData(`type`) match {
            case Some(p) => Some(p.col1)
            case None => None
    
        }
    }
    def tryGetPrimitiveData(`type`: TypeReference): Option[Row2[ElementType, Boolean]] = {
        _primitive_value_types.get(`type`.name)

    }
    def binaryRangeSearch(types: Array[TypeDefinition], rid: Int, field: Boolean) = {
        binaryRangeSearchRec(types, rid, field, 0, types.length - 1)
    
    }
    @tailrec
    def binaryRangeSearchRec(types: Array[TypeDefinition], rid: Int, field: Boolean, min: Int, max: Int): Option[TypeDefinition] = {
        if (min > max) {
            None
        }
        else {
            val mid = min + ((max - min) / 2)
            val `type` = types(mid)
            val range = if field then `type`.fields_range else `type`.methods_range
            range match {
                case Some(r) =>
                    if (rid < r.index) {
                        binaryRangeSearchRec(types, rid, field, min, mid - 1)
                    }
                    else if (rid >= r.index + r.length) {
                        binaryRangeSearchRec(types, rid, field, mid + 1, max)
                    }
                    else {
                        Some(`type`)
                    }
                case None => None
            }
        }
    }
}