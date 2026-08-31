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

    var type_object: Option[TypeReference] = None
    var type_void: Option[TypeReference] = None
    var type_bool: Option[TypeReference] = None
    var type_char: Option[TypeReference] = None
    var type_sbyte: Option[TypeReference] = None
    var type_byte: Option[TypeReference] = None
    var type_int16: Option[TypeReference] = None
    var type_uint16: Option[TypeReference] = None
    var type_int32: Option[TypeReference] = None
    var type_uint32: Option[TypeReference] = None
    var type_int64: Option[TypeReference] = None
    var type_uint64: Option[TypeReference] = None
    var type_single: Option[TypeReference] = None
    var type_double: Option[TypeReference] = None
    var type_intptr: Option[TypeReference] = None
    var type_uintptr: Option[TypeReference] = None
    var type_string: Option[TypeReference] = None
    var type_typedref: Option[TypeReference] = None

    def lookupType(namespace: String, name: String): TypeReference

    private def lookupSystemType(reference: Option[TypeReference], name: String, element_type: ElementType, assigner: (TypeReference)=>Unit): TypeReference = {
        _module.syncRoot.synchronized {
            reference match {
                case Some(r) => r
                case None =>
                    val `type` = lookupType("System", name)
                    `type`.etype = element_type
                    assigner(`type`)
                    `type`
            }
        }
    
    }
    private def lookupSystemValueType(typeRef: Option[TypeReference], name: String, element_type: ElementType, assigner: (TypeReference)=>Unit) = {
        _module.syncRoot.synchronized {
            typeRef match {
                case Some(r) => r
                case None =>
                    val `type` = lookupType("System", name)
                    `type`.etype = element_type
                    `type`.knownValueType()
                    assigner(`type`)
                    `type`
            }
        }
    
    }
    def coreLibrary = {
        this.as[CommonTypeSystem] match {
            case Some(common) => common.getCoreLibraryReference()
            case None => _module
        }
    }
    def `object` = {
        lookupSystemType(type_object, "Object", ElementType.`object`, (t)=>type_object = Some(t))

    }
    def void = {
        lookupSystemType(type_void, "Void", ElementType.`object`, (t)=>type_void = Some(t))
    
    }
    def boolean = {
        lookupSystemValueType(type_bool, "Boolean", ElementType.boolean, (t)=>type_bool = Some(t))
    
    }
    def char = {
        lookupSystemValueType(type_char, "Char", ElementType.char, (t)=>type_char = Some(t))
    
    }
    def sByte = {
        lookupSystemValueType(type_sbyte, "SByte", ElementType.i1, (t)=>type_sbyte = Some(t))
    
    }
    def byte = {
        lookupSystemValueType(type_byte, "Byte", ElementType.u1, (t)=>type_byte = Some(t))
    
    }
    def int16 = {
        lookupSystemValueType(type_int16, "Int16", ElementType.i2, (t)=>type_int16 = Some(t))
    
    }
    def uInt16 = {
        lookupSystemValueType(type_uint16, "UInt16", ElementType.u2, (t)=>type_uint16 = Some(t))
    
    }
    def int32 = {
        lookupSystemValueType(type_int32, "Int32", ElementType.i4, (t)=>type_int32 = Some(t))
    
    }
    def uInt32 = {
        lookupSystemValueType(type_uint32, "UInt32", ElementType.u4, (t)=>type_uint32 = Some(t))
    
    }
    def int64 = {
        lookupSystemValueType(type_int64, "Int64", ElementType.i8, (t)=>type_int64 = Some(t))
    
    }
    def uInt64 = {
        lookupSystemValueType(type_uint64, "UInt64", ElementType.u8, (t)=>type_uint64 = Some(t))

    }
    def single = {
        lookupSystemValueType(type_single, "Single", ElementType.r4, (t)=>type_single = Some(t))
    
    }
    def double = {
        lookupSystemValueType(type_double, "Double", ElementType.r8, (t)=>type_double = Some(t))
    
    }
    def intPtr = {
        lookupSystemValueType(type_intptr, "IntPtr", ElementType.i, (t)=>type_intptr = Some(t))
    
    }
    def uintPtr = {
        lookupSystemValueType(type_uintptr, "UIntPtr", ElementType.u, (t)=>type_uintptr = Some(t))
    
    }
    def string = {
        lookupSystemType(type_string, "String", ElementType.string, (t)=>type_string = Some(t))
    
    }
    def typedReference = {
        lookupSystemValueType(type_typedref, "TypedReference", ElementType.typedByRef, (t)=>type_typedref = Some(t))
    }
}

private sealed class CoreTypeSystem(__module: ModuleDefinition) extends TypeSystem(__module) {

    override def lookupType(namespace: String, name: String): TypeReference = {
        val defn = lookupTypeDefinition(namespace, name)
        var `type` = if defn.isDefined then defn else lookupTypeForwarded(namespace, name)
        `type` match {
            case Some(t) => t
            case None => throw OperationNotSupportedException()
        }
    }
    def lookupTypeDefinition(namespace: String, name: String): Option[TypeReference] = {
        val metadata = _module.metadataSystem
        if (metadata._types.length == 0) {
            initialize(_module.types)
        }
        _module.read(Row2[String, String](namespace, name), (row, reader) => {
                val types = reader.metadata._types

                var result: Option[TypeReference] = None
                boundary {
                    for i <- 0 until types.length do {
                        reader.getTypeDefinition(i + 1).foreach(td => types(i) = td)
                        val `type` = types(i)

                        if (`type`.name == row.col2 && `type`.nameSpace == row.col1) {
                            result = Some(`type`)
                            break()
                        }
                    }
                }
                result
                })
    
    }
    def lookupTypeForwarded(namespace: String, name: String): Option[TypeReference] = {
        if (!_module.hasExportedTypes) {
            None
        }
        else {
            val exported_types = _module.exportedTypes
            exported_types.find((ty) => ty.name == name && ty.nameSpace == namespace) match {
                case Some(exportedType) => Some(exportedType.createReference())
                case None => None
    
            }
        }
    }
    def initialize(value: Any) = { }
}

private sealed class CommonTypeSystem(__module: ModuleDefinition) extends TypeSystem(__module) {
    private var core_library: Option[AssemblyNameReference] = None

    override def lookupType(namespace: String, name: String): TypeReference = {
        createTypeReference(namespace, name)
    
    }
    def getCoreLibraryReference() = {
        core_library match {
            case Some(lib) => lib
            case None =>
                _module.tryGetCoreLibraryReference() match {
                    case Some(lib) =>
                        core_library = Some(lib)
                        lib
                    case None =>
                        val lib = AssemblyNameReference()
                        lib.name = ModuleDefinition.mscorlib
                        lib.version = getCorelibVersion()
                        lib.publicKeyToken = Array[Byte](0xb7.toByte, 0x7a, 0x5c, 0x56, 0x19, 0x34, 0xe0.toByte, 0x89.toByte)
                        _module.assemblyReferences.addOne(lib)
                        core_library = Some(lib)
                        lib
                }
        }
    }
    def getCorelibVersion() = {
        _module.runtime match {
            case TargetRuntime.net_1_0 | TargetRuntime.net_1_1 => CSVersion(1, 0, 0, 0)
            case TargetRuntime.net_2_0 => CSVersion(2, 0, 0, 0)
            case TargetRuntime.net_4_0 => CSVersion(4, 0, 0, 0)
    
        }
    }
    def createTypeReference(namespace: String, name: String) = {
        TypeReference(namespace, name, _module, getCoreLibraryReference())
                    


    }
}

object TypeSystem {
    def createTypeSystem(module: ModuleDefinition) = {
        if (module.isCoreLibrary()) {
            CoreTypeSystem(module)
        }
        else {
            CommonTypeSystem(module)
        }
    }
}
