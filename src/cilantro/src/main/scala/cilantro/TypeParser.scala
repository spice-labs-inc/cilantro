//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/TypeParser.cs

package io.spicelabs.cilantro

import io.spicelabs.cilantro.TypeParser.tryGetArity
import scala.util.boundary, boundary.break
import io.spicelabs.cilantro.TypeParser.addInt
import io.spicelabs.cilantro.TypeParser.addType
import io.spicelabs.cilantro.TypeParser.addStr
import io.spicelabs.cilantro.metadata.ElementType


class TypeParser(private val _fullname: String) {
    val _length = _fullname.length()
    var _position = 0

    private class Type {
        var type_fullname: String = null
        var nested_names: Array[String] = null
        var arity = 0
        var specs: Array[Int] = null
        var generic_arguments: Array[TypeParser#Type] = null
        var assembly: String = null
    }

    private def parseType(fq_name: Boolean) =
        var `type` = Type()
        `type`.type_fullname = parsePart()
        `type`.nested_names = parseNestedNames()
        if (tryGetArity(`type`))
            `type`.generic_arguments = parseGenericArguments(`type`.arity)
        
        `type`.specs = parseSpecs()

        if (fq_name)
            `type`.assembly = parseAssemblyName()
        
        `type`
    
    private def parsePart() =
        var part = StringBuilder()
        while _position < _length do
            if (_fullname.charAt(_position) == '\\')
                _position += 1
            part.append(_fullname.charAt(_position))
            _position += 1
        part.toString()
    
    private def tryParseWhiteSpace() =
        while _position < _length && Character.isWhitespace(_fullname(_position)) do
            _position += 1
    
    private def parseNestedNames() =
        var nested_names: Array[String] = null
        while tryParse('+') do
            nested_names = addStr(nested_names, parsePart())
        nested_names

    private def tryParse(chr: Char) =
        if (_position < _length && _fullname.charAt(_position) == chr)
            _position += 1
            true
        else
            false

    private def parseSpecs(): Array[Int] =
        var specs: Array[Int] = Array.emptyIntArray
        boundary:
            while _position < _length do
                specs = _fullname.charAt(_position) match
                    case '*' =>
                        _position += 1
                        addInt(specs, TypeParser.ptr)
                    case '&' =>
                        _position += 1
                        addInt(specs, TypeParser.byRef)
                    case '[' =>
                        _position += 1
                        _fullname.charAt(_position) match
                            case ']' =>
                                _position += 1
                                addInt(specs, TypeParser.szArray)
                            case '*' =>
                                _position += 1
                                addInt(specs, 1)
                            case _ => 
                                var rank = 1
                                while tryParse(',') do
                                    rank += 1
                                specs = addInt(specs, rank)
                                tryParse(']')
                                specs
                    case _ => break()
        specs

    private def parseGenericArguments(arity: Int): Array[TypeParser#Type] =
        var generic_arguments: Array[TypeParser#Type] = Array[TypeParser#Type]()

        if (_position == _length || _fullname.charAt(_position) != '[')
            null
        else
            tryParse('[')

            for i <- 0 until arity do
                val fq_argument = tryParse('[')
                generic_arguments = addType(generic_arguments, parseType(fq_argument))
                if (fq_argument)
                    tryParse(']')
                tryParse(',')
                tryParseWhiteSpace()
            
            tryParse(']')

            generic_arguments

    private def parseAssemblyName() =
        if (!tryParse(','))
            ""
        else
            tryParseWhiteSpace()
            val start = _position
            boundary:
                while _position < _length do
                    val chr = _fullname.charAt(_position)
                    if (chr == '[' || chr == ']')
                        break()
                    _position += 1
            _fullname.substring(start, _position - start)


            
}

object TypeParser {
    private def tryGetArity(`type`: TypeParser#Type): Boolean =
        var arity = 0

        arity = tryAddArity(`type`.type_fullname, arity)

        val nested_names = `type`.nested_names
        if (nested_names != null && nested_names.length > 0)
            for i <- 0 until nested_names.length do
                arity = tryAddArity(nested_names(i), arity)
        `type`.arity = arity
        arity > 0
    
    private def tryGetArity(name: String): Option[Int] =
        val index = name.lastIndexOf('`')
        if (index == -1)
            None
        else
            parseInt32(name.substring(index + 1))
    
    private def parseInt32(value: String): Option[Int] = value.toIntOption

    private def tryAddArity(name: String, arity: Int) =
        tryGetArity(name) match
            case Some(type_arity) => arity + type_arity
            case None => arity
    
    private def isDelimeter(chr: Char) =
        "+,[]*&".indexOf(chr) >= 0
    
    private def addInt(array: Array[Int], item: Int) =
        if array == null then Array(item) else array :+ item
    
    private def addType(array: Array[TypeParser#Type], item: TypeParser#Type) =
        if array == null then Array(item) else array :+ item
    
    private def addStr(array: Array[String], item: String) =
        if array == null then Array(item) else array :+ item
    
    def parseType(module: ModuleDefinition, fullname: String, typeDefinitionOnly: Boolean = false) =
        if (fullname == null || fullname.length() == 0)
            null
        else
            val parser = TypeParser(fullname)
            getTypeReference(module, parser.parseType(true), typeDefinitionOnly)

    private def getTypeReference(module: ModuleDefinition, type_info: TypeParser#Type, type_def_only: Boolean): TypeReference =
        val `type` = tryGetDefinition(module, type_info) match
            case Some(atype) =>
                createSpecs(atype, type_info)
            case None =>
                if (type_def_only)
                    null
                else
                    createReference(type_info, module, getMetadataScope(module, type_info))
        if `type` == null then null else createSpecs(`type`, type_info)

    private def createSpecs(`type`: TypeReference, type_info: TypeParser#Type): TypeReference =
        var thetype = tryCreateGenericInstanceType(`type`, type_info)
        var specs = type_info.specs
        if (specs == null || specs.length == 0)
            thetype
        else
            for i <- 0 until specs.length do
                thetype = specs(i) match
                    case TypeParser.ptr => PointerType(thetype)
                    case TypeParser.byRef => ByReferenceType(thetype)
                    case TypeParser.szArray => ArrayType(thetype)
                    case _ =>
                        val array = ArrayType(thetype)
                        array.dimensions.clear()
                        for j <- 0 until specs(i) do
                            array.dimensions.addOne((ArrayDimension(null, null)))
                        array
            thetype
    
    private def tryCreateGenericInstanceType(`type`: TypeReference, type_info: TypeParser#Type) =
        val generic_arguments = type_info.generic_arguments
        if (generic_arguments == null || generic_arguments.length == 0)
            `type`
        else
            val instance = GenericInstanceType(`type`, generic_arguments.length)
            val instance_arguments = instance.genericArguments

            for i <- 0 until generic_arguments.length do
                instance_arguments.addOne(getTypeReference(`type`.module, generic_arguments(i), false))
            instance

    def splitFullName(fullname: String): (String, String) =
        var last_dot = fullname.lastIndexOf('.')
        if (last_dot == -1)
            ("", fullname)
        else
            (fullname.substring(0, last_dot), fullname.substring(last_dot + 1))

    private def createReference(type_info: TypeParser#Type, module: ModuleDefinition, scope: MetadataScope): TypeReference =
        val (namespace, name) = splitFullName(type_info.type_fullname)
        var `type` = TypeReference(namespace, name, module, scope)
        MetadataSystem.tryProcessPrimitiveTypeReference(`type`)

        adjustGenericParameters(`type`)

        val nested_names = type_info.nested_names
        if (nested_names == null || nested_names.length == 0)
            `type`
        else
            for i <- 0 until nested_names.length do
                val nested = TypeReference("", nested_names(i), module, null)
                nested.declaringType = `type`
                `type` = nested
                adjustGenericParameters(`type`)
            `type`

    private def adjustGenericParameters(`type`: TypeReference) =
        tryGetArity(`type`.name) match
            case Some(arity) =>
                for i <- 0 until arity do
                    `type`.genericParameters.addOne(GenericParameter(`type`))
            case None => ()
    
    private def getMetadataScope(module: ModuleDefinition, type_info: TypeParser#Type): MetadataScope =
        if (type_info.assembly == null || type_info.assembly.length() == 0)
            module.typeSystem.coreLibrary
        else
            val reference = AssemblyNameReference.parse(type_info.assembly)
            module.tryGetAssemblyNameReference(reference) match
                case Some(m) => m
                case None => reference
    
    private def tryGetDefinition(module: ModuleDefinition, type_info: TypeParser#Type): Option[TypeReference] =
        var `type`: TypeReference = null
        if (!tryCurrentModule(module, type_info))
            return None
        
        var typedef = module.getType(type_info.type_fullname)
        if (typedef == null)
            return None
        
        val nested_names = type_info.nested_names
        if (nested_names != null && nested_names.length > 0)
            var failed = false
            boundary:
                for i <- 0 until nested_names.length do
                    val nested_type = typedef.getNestedType(nested_names(i))
                    if (nested_type == null)
                        failed = true
                        break()
                    typedef = nested_type
            if (failed)
                return None
        return Some(typedef)

    private def tryCurrentModule(module: ModuleDefinition, type_info: TypeParser#Type): Boolean =
        if (type_info.assembly == null || type_info.assembly.length() == 0)
            return true
        
        if (module.assembly != null && module.assembly.name.fullName == type_info.assembly)
            return true
        
        return false
    
    def toParseable(`type`: TypeReference, top_level: Boolean = true): String =
        if (`type` == null)
            null
        else
            val name = StringBuilder()
            appendType(`type`, name, true, top_level)
            name.toString()
    
    private def appendNamePart(part: String, name: StringBuilder) =
        for c <- part do
            if (isDelimeter(c))
                name.append('\\')
            name.append(c)
    
    private def appendType(`type`: TypeReference, name: StringBuilder, fq_name: Boolean, top_level: Boolean): Unit =
        val element_type = `type`.getElementType()

        val declaring_type = element_type.declaringType
        if (declaring_type != null)
            appendType(declaring_type, name, false, top_level)
            name.append('+')
        
        val namespace = `type`.nameSpace
        if (namespace != null && namespace.length() > 0)
            appendNamePart(namespace, name)
            name.append('.')
        
        appendNamePart(element_type.name, name)

        if (fq_name)
            if (`type`.isTypeSpecification())
                appendTypeSpecification(`type`.asInstanceOf[TypeSpecification], name)
            if (requiresFullyQualifiedName(`type`, top_level))
                name.append(", ")
                name.append(getScopeFullName(`type`))
    
    private def getScopeFullName(`type`: TypeReference) =
        val scope = `type`.scope
        scope.metadataScopeType match
            case MetadataScopeType.assemblyNameReference => scope.asInstanceOf[AssemblyNameReference].fullName
            case MetadataScopeType.moduleDefinition => scope.asInstanceOf[ModuleDefinition].assembly.name.fullName
            case _ => throw IllegalArgumentException()
    
        
    private def appendTypeSpecification(`type`: TypeSpecification, name: StringBuilder): Unit =
        if (`type`.elementType.isTypeSpecification())
            appendTypeSpecification(`type`.elementType.asInstanceOf[TypeSpecification], name)
        
        `type`.etype match
            case ElementType.ptr => name.append('*')
            case ElementType.byRef => name.append('&')
            case ElementType.szArray | ElementType.array =>
                val array = `type`.asInstanceOf[ArrayType]
                if (array.isVector)
                    name.append("[]")
                else
                    name.append("[")
                    for i <- 1 until array.rank do name.append(',')
                    name.append(']')
            case ElementType.genericInst =>
                val instance = `type`.asInstanceOf[GenericInstanceType]
                val arguments = instance.genericArguments
                name.append('[')

                for i <- 0 until arguments.length do
                    if (i > 0)
                        name.append(',')
                    val argument = arguments(i)
                    val requires_fqname = argument.scope != argument.module

                    if (requires_fqname)
                        name.append('[')
                    
                    appendType(argument, name, true, false)

                    if (requires_fqname)
                        name.append(']')
                name.append(']')
            case _ => ()

    private def requiresFullyQualifiedName(`type`: TypeReference, top_level: Boolean) =
        if (`type`.scope == `type`.module ||
            (`type`.scope.name == "mscorlib" && top_level))
            false
        else
            true        


    val ptr = -1
    val byRef = -2
    val szArray = -3

}
