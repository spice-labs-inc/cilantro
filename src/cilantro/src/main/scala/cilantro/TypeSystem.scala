//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/TypeSystem.cs

package io.spicelabs.cilantro

import io.spicelabs.cilantro.metadata.ElementType
import io.spicelabs.cilantro.AnyExtension.as
import javax.naming.OperationNotSupportedException
import io.spicelabs.cilantro.metadata.Row2
import scala.util.boundary,boundary.break

abstract class TypeSystem(val _module: ModuleDefinition) {

    var type_object: TypeReference = null
    var type_void: TypeReference = null
    var type_bool: TypeReference = null
    var type_char: TypeReference = null
    var type_sbyte: TypeReference = null
    var type_byte: TypeReference = null
    var type_int16: TypeReference = null
    var type_uint16: TypeReference = null
    var type_int32: TypeReference = null
    var type_uint32: TypeReference = null
    var type_int64: TypeReference = null
    var type_uint64: TypeReference = null
    var type_single: TypeReference = null
    var type_double: TypeReference = null
    var type_intptr: TypeReference = null
    var type_uintptr: TypeReference = null
    var type_string: TypeReference = null
    var type_typedref: TypeReference = null

    def lookupType(namespace: String, name: String): TypeReference

    private def lookupSystemType(reference: TypeReference, name: String, element_type: ElementType, assigner: (TypeReference)=>Unit): TypeReference =
        _module.syncRoot.synchronized {
            if (reference != null)
                reference
            else
                val `type` = lookupType("System", name)
                `type`.etype = element_type
                assigner(`type`)
                `type`
        }
    
    private def lookupSystemValueType(typeRef: TypeReference, name: String, element_type: ElementType, assigner: (TypeReference)=>Unit) =
        _module.syncRoot.synchronized {
            if (typeRef != null)
                typeRef
            else
                val `type` = lookupType("System", name)
                `type`.etype = element_type
                `type`.knownValueType()
                assigner(`type`)
                `type`
        }
    
    def coreLibrary =
        val common = this.as[CommonTypeSystem]
        if (common == null)
            _module
        else
            common.getCoreLibraryReference()
    
    def `object` =
        lookupSystemType(type_object, "Object", ElementType.`object`, (t)=>type_object = t)

    def void =
        lookupSystemType(type_void, "Void", ElementType.`object`, (t)=>type_void = t)
    
    def boolean =
        lookupSystemValueType(type_bool, "Boolean", ElementType.boolean, (t)=>type_bool = t)
    
    def char =
        lookupSystemValueType(type_char, "Char", ElementType.char, (t)=>type_char = t)
    
    def sByte =
        lookupSystemValueType(type_sbyte, "SByte", ElementType.i1, (t)=>type_sbyte = t)
    
    def byte =
        lookupSystemValueType(type_byte, "Byte", ElementType.u1, (t)=>type_byte = t)
    
    def int16 =
        lookupSystemValueType(type_int16, "Int16", ElementType.i2, (t)=>type_int16 = t)
    
    def uInt16 =
        lookupSystemValueType(type_uint16, "UInt16", ElementType.u2, (t)=>type_uint16 = t)
    
    def int32 =
        lookupSystemValueType(type_int32, "Int32", ElementType.i4, (t)=>type_int32 = t)
    
    def uInt32 =
        lookupSystemValueType(type_uint32, "UInt32", ElementType.u4, (t)=>type_uint32 = t)
    
    def int64 =
        lookupSystemValueType(type_int64, "Int64", ElementType.i8, (t)=>type_int64 = t)
    
    def uInt64 =
        lookupSystemValueType(type_uint64, "UInt64", ElementType.u8, (t)=>type_uint64 = t)

    def single =
        lookupSystemValueType(type_single, "Single", ElementType.r4, (t)=>type_single = t)
    
    def double =
        lookupSystemValueType(type_double, "Double", ElementType.r8, (t)=>type_double = t)
    
    def intPtr =
        lookupSystemValueType(type_intptr, "IntPtr", ElementType.i, (t)=>type_intptr = t)
    
    def uintPtr =
        lookupSystemValueType(type_uintptr, "UIntPtr", ElementType.u, (t)=>type_uintptr = t)
    
    def string =
        lookupSystemType(type_string, "String", ElementType.string, (t)=>type_string = t)
    
    def typedReference =
        lookupSystemValueType(type_typedref, "TypedReference", ElementType.typedByRef, (t)=>type_typedref = t)
}

private sealed class CoreTypeSystem(__module: ModuleDefinition) extends TypeSystem(__module) {

    override def lookupType(namespace: String, name: String): TypeReference =
        val defn = lookupTypeDefinition(namespace, name)
        var `type` = if defn != null then defn else lookupTypeForwarded(namespace, name)
        if (`type` != null)
            `type`
        else
            throw OperationNotSupportedException()
        
    def lookupTypeDefinition(namespace: String, name: String): TypeReference =
        val metadata = _module.metadataSystem
        if (metadata._types == null)
            initialize(_module.types)
        _module.read(Row2[String, String](namespace, name), (row, reader) => {
                val types = reader.metadata._types

                var result: TypeReference = null
                boundary:
                    for i <- 0 until types.length do
                        if (types(i) == null)
                            types(i) = reader.getTypeDefinition(i + 1)
                        val `type` = types(i)

                        if (`type`.name == row.col2 && `type`.nameSpace == row.col1)
                            result = `type`
                            break()
                `result`
                })
    
    def lookupTypeForwarded(namespace: String, name: String): TypeReference =
        if (!_module.hasExportedTypes)
            null
        else
            val exported_types = _module.exportedTypes
            exported_types.find((ty) => ty.name == name && ty.nameSpace == namespace) match
                case Some(exportedType) => exportedType.createReference()
                case None => null
    
    def initialize(value: Any) = { }
}

private sealed class CommonTypeSystem(__module: ModuleDefinition) extends TypeSystem(__module) {
    private var core_library: AssemblyNameReference = null

    override def lookupType(namespace: String, name: String): TypeReference =
        createTypeReference(namespace, name)
    
    def getCoreLibraryReference() =
        if (core_library != null)
            core_library
        else _module.tryGetCoreLibraryReference() match
            case Some(lib) =>
                core_library = lib
                core_library
            case None =>
                core_library = AssemblyNameReference()
                core_library.name = ModuleDefinition.mscorlib
                core_library.version = getCorelibVersion()
                core_library.publicKeyToken = Array[Byte](0xb7.toByte, 0x7a, 0x5c, 0x56, 0x19, 0x34, 0xe0.toByte, 0x89.toByte)
                _module.assemblyReferences.addOne(core_library)
                core_library
    def getCorelibVersion() =
        _module.runtime match
            case TargetRuntime.net_1_0 | TargetRuntime.net_1_1 => CSVersion(1, 0, 0, 0)
            case TargetRuntime.net_2_0 => CSVersion(2, 0, 0, 0)
            case TargetRuntime.net_4_0 => CSVersion(4, 0, 0, 0)
            case null => throw OperationNotSupportedException()
    
    def createTypeReference(namespace: String, name: String) =
        TypeReference(namespace, name, _module, getCoreLibraryReference())
                    


}

object TypeSystem {
    def createTypeSystem(module: ModuleDefinition) =
        if (module.isCoreLibrary())
            CoreTypeSystem(module)
        else
            CommonTypeSystem(module)
}
