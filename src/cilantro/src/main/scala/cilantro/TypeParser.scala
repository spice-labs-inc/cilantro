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
import javax.naming.OperationNotSupportedException
import io.spicelabs.cilantro.TypeParser.addInt
import io.spicelabs.cilantro.TypeParser.addType
import io.spicelabs.cilantro.TypeParser.addStr
import io.spicelabs.cilantro.metadata.ElementType


class TypeParser(private val _fullname: String) {
    val _length = _fullname.length()
    var _position = 0
    private var _depth = 0
    private val maxTypeParserDepth = 128

    private class Type {
        var type_fullname: String = ""
        var nested_names: Option[Array[String]] = None
        var arity = 0
        var specs: Option[Array[Int]] = None
        var generic_arguments: Option[Array[TypeParser#Type]] = None
        var assembly: Option[String] = None
    }

    private def parseType(fq_name: Boolean) = {
        // Recursion bound from plan 04: nested generic arguments
        // (`A`1[[A`1[[...]]]]`) recurse through parseType; a hostile
        // custom-attribute type string must fail, not overflow the stack.
        _depth += 1
        if (_depth > maxTypeParserDepth) {
            throw IllegalArgumentException()
        }
        try {
            var `type` = Type()
            `type`.type_fullname = parsePart()
            `type`.nested_names = parseNestedNames()
            if (tryGetArity(`type`)) {
                `type`.generic_arguments = parseGenericArguments(`type`.arity)
            
            }
            `type`.specs = Option(parseSpecs())

            if (fq_name) {
                `type`.assembly = parseAssemblyName()
            
            }
            `type`
        } finally {
            _depth -= 1
        }
    
    }
    private def parsePart() = {
        var part = StringBuilder()
        while _position < _length && !TypeParser.isDelimeter(_fullname.charAt(_position)) do {
            if (_fullname.charAt(_position) == '\\') {
                _position += 1
            }
            part.append(_fullname.charAt(_position))
            _position += 1
        }
        part.toString()
    
    }
    private def tryParseWhiteSpace() = {
        while _position < _length && Character.isWhitespace(_fullname(_position)) do {
            _position += 1
    
        }
    }
    private def parseNestedNames() = {
        var nested_names: Option[Array[String]] = None
        while tryParse('+') do {
            nested_names = Some(addStr(nested_names.getOrElse(Array.empty[String]), parsePart()))
        }
        nested_names

    }
    private def tryParse(chr: Char) = {
        if (_position < _length && _fullname.charAt(_position) == chr) {
            _position += 1
            true
        }
        else {
            false

        }
    }
    private def parseSpecs(): Array[Int] = {
        var specs: Array[Int] = Array.emptyIntArray
        boundary {
            while _position < _length do {
                specs = _fullname.charAt(_position) match {
                    case '*' =>
                        _position += 1
                        addInt(specs, TypeParser.ptr)
                    case '&' =>
                        _position += 1
                        addInt(specs, TypeParser.byRef)
                    case '[' =>
                        _position += 1
                        _fullname.charAt(_position) match {
                            case ']' =>
                                _position += 1
                                addInt(specs, TypeParser.szArray)
                            case '*' =>
                                _position += 1
                                addInt(specs, 1)
                            case _ => 
                                var rank = 1
                                while tryParse(',') do {
                                    rank += 1
                                }
                                specs = addInt(specs, rank)
                                tryParse(']')
                                specs
                        }
                    case _ => break()
                }
            }
        }
        specs

    }
    private def parseGenericArguments(arity: Int): Option[Array[TypeParser#Type]] = {
        var generic_arguments: Array[TypeParser#Type] = Array[TypeParser#Type]()

        if (_position == _length || _fullname.charAt(_position) != '[') {
            None
        }
        else {
            tryParse('[')

            for i <- 0 until arity do {
                val fq_argument = tryParse('[')
                generic_arguments = addType(generic_arguments, parseType(fq_argument))
                if (fq_argument) {
                    tryParse(']')
                }
                tryParse(',')
                tryParseWhiteSpace()
            
            }
            tryParse(']')

            Some(generic_arguments)

        }
    }
    private def parseAssemblyName(): Option[String] = {
        if (!tryParse(',')) {
            Some("")
        }
        else {
            tryParseWhiteSpace()
            val start = _position
            boundary {
            while _position < _length do {
                    val chr = _fullname.charAt(_position)
                    if (chr == '[' || chr == ']') {
                        break()
                    }
                    _position += 1
                }
            }
            Some(_fullname.substring(start, _position))


            
        }
    }
}

object TypeParser {
    private def tryGetArity(`type`: TypeParser#Type): Boolean = {
        var arity = 0

        arity = tryAddArity(`type`.type_fullname, arity)

        `type`.nested_names.foreach { nested_names =>
            if (nested_names.length > 0) {
                for i <- 0 until nested_names.length do {
                    arity = tryAddArity(nested_names(i), arity)
                }
            }
        }
        `type`.arity = arity
        arity > 0
    
    }
    private def tryGetArity(name: String): Option[Int] = {
        val index = name.lastIndexOf('`')
        if (index == -1) {
            None
        }
        else {
            parseInt32(name.substring(index + 1))
    
        }
    }
    private def parseInt32(value: String): Option[Int] = value.toIntOption

    private def tryAddArity(name: String, arity: Int) = {
        tryGetArity(name) match {
            case Some(type_arity) => arity + type_arity
            case None => arity
    
        }
    }
    def isDelimeter(chr: Char) = {
        "+,[]*&".indexOf(chr) >= 0
    
    }
    private def addInt(array: Array[Int], item: Int) = {
        array :+ item
    
    }
    private def addType(array: Array[TypeParser#Type], item: TypeParser#Type) = {
        array :+ item
    
    }
    private def addStr(array: Array[String], item: String) = {
        array :+ item
    
    }
    def parseType(module: Option[ModuleDefinition], fullname: String, typeDefinitionOnly: Boolean = false) = {
        if (fullname.length() == 0) {
            None
        }
        else {
            val parser = TypeParser(fullname)
            getTypeReference(module, parser.parseType(true), typeDefinitionOnly)

        }
    }
    private def getTypeReference(module: Option[ModuleDefinition], type_info: TypeParser#Type, type_def_only: Boolean): Option[TypeReference] = {
        val `type` = tryGetDefinition(module, type_info) match {
            case Some(atype) =>
                Some(createSpecs(atype, type_info))
            case None =>
                if (type_def_only) {
                    None
                }
                else {
                    module.map(m => createReference(type_info, m, getMetadataScope(m, type_info)))
                }
        }
        `type`.map(createSpecs(_, type_info))

    }
    private def getTypeReferenceUnusedMarker = ()
    private def createSpecs(`type`: TypeReference, type_info: TypeParser#Type): TypeReference = {
        var thetype = tryCreateGenericInstanceType(`type`, type_info)
        type_info.specs match {
            case None => thetype
            case Some(specs) if specs.length == 0 => thetype
            case Some(specs) =>
                for i <- 0 until specs.length do {
                    thetype = specs(i) match {
                        case TypeParser.ptr => PointerType(thetype)
                        case TypeParser.byRef => ByReferenceType(thetype)
                        case TypeParser.szArray => ArrayType(thetype)
                        case _ =>
                            val array = ArrayType(thetype)
                            array.dimensions.clear()
                            for j <- 0 until specs(i) do {
                                array.dimensions.addOne((ArrayDimension(None, None)))
                            }
                            array
                    }
                }
                thetype
        }
    }
    private def tryCreateGenericInstanceType(`type`: TypeReference, type_info: TypeParser#Type) = {
        type_info.generic_arguments match {
            case None => `type`
            case Some(generic_arguments) if generic_arguments.length == 0 => `type`
            case Some(generic_arguments) =>
                val instance = GenericInstanceType(`type`, generic_arguments.length)
                val instance_arguments = instance.genericArguments

                for i <- 0 until generic_arguments.length do {
                    instance_arguments.addOne(getTypeReference(`type`.module, generic_arguments(i), false).getOrElse(throw OperationNotSupportedException()))
                }
                instance
        }
    }
    def splitFullName(fullname: String): (String, String) = {
        var last_dot = fullname.lastIndexOf('.')
        if (last_dot == -1) {
            ("", fullname)
        }
        else {
            (fullname.substring(0, last_dot), fullname.substring(last_dot + 1))

        }
    }
    private def createReference(type_info: TypeParser#Type, module: ModuleDefinition, scope: Option[MetadataScope]): TypeReference = {
        val (namespace, name) = splitFullName(type_info.type_fullname)
        var `type` = TypeReference(namespace, name, module, scope.getOrElse(throw OperationNotSupportedException()))
        MetadataSystem.tryProcessPrimitiveTypeReference(`type`)

        adjustGenericParameters(`type`)

        type_info.nested_names match {
            case None => `type`
            case Some(nested_names) if nested_names.length == 0 => `type`
            case Some(nested_names) =>
            for i <- 0 until nested_names.length do {
                val nested = TypeReference("", nested_names(i), module)
                nested.declaringType = `type`
                `type` = nested
                adjustGenericParameters(`type`)
            }
            `type`

        }
    }
    private def adjustGenericParameters(`type`: TypeReference) = {
        tryGetArity(`type`.name) match {
            case Some(arity) =>
                for i <- 0 until arity do {
                    `type`.genericParameters.addOne(GenericParameter(`type`))
                }
            case None => ()
    
        }
    }
    private def getMetadataScope(module: ModuleDefinition, type_info: TypeParser#Type): Option[MetadataScope] = {
        type_info.assembly match {
            case Some(assembly) if assembly.length() > 0 =>
                val reference = AssemblyNameReference.parse(assembly)
                module.tryGetAssemblyNameReference(reference) match {
                    case Some(m) => Some(m)
                    case None => Some(reference)
                }
            case _ => Some(module.typeSystem.coreLibrary)
        }
    }
    private def tryGetDefinition(module: Option[ModuleDefinition], type_info: TypeParser#Type): Option[TypeReference] = {
        if (!tryCurrentModule(module, type_info)) {
            return None
        
        }
        var typedef = module.flatMap(_.getType(type_info.type_fullname))
        if (typedef.isEmpty) {
            return None
        
        }
        val nested_names = type_info.nested_names
        if (nested_names.isDefined && nested_names.get.length > 0) {
            var failed = false
            boundary {
                for i <- 0 until nested_names.get.length do {
                    typedef.flatMap(_.getNestedType(nested_names.get(i))) match {
                        case Some(nt) => typedef = Some(nt)
                        case None =>
                            failed = true
                            break()
                    }
                }
            }
            if (failed) {
                return None
            }
        }
        typedef

    }
    private def tryCurrentModule(module: Option[ModuleDefinition], type_info: TypeParser#Type): Boolean = {
        type_info.assembly match {
            case None => true
            case Some(assembly) if assembly.length() == 0 => true
            case Some(assembly) =>
                module.flatMap(_.assembly).flatMap(_.name).exists(_.fullName == assembly)
        }
    
    }
    def toParseable(`type`: TypeReference, top_level: Boolean = true): String = {
            val name = StringBuilder()
            appendType(`type`, name, true, top_level)
            name.toString()
    
        }
    private def appendNamePart(part: String, name: StringBuilder) = {
        for c <- part do {
            if (isDelimeter(c)) {
                name.append('\\')
            }
            name.append(c)
    
        }
    }
    private def appendType(`type`: TypeReference, name: StringBuilder, fq_name: Boolean, top_level: Boolean): Unit = {
        val element_type = `type`.getElementType()

        element_type.declaringType.foreach { declaring_type =>
            appendType(declaring_type, name, false, top_level)
            name.append('+')
        
        }
        val namespace = `type`.nameSpace
        if (namespace.length() > 0) {
            appendNamePart(namespace, name)
            name.append('.')
        
        }
        appendNamePart(element_type.name, name)

        if (fq_name) {
            if (`type`.isTypeSpecification()) {
                appendTypeSpecification(`type`.asInstanceOf[TypeSpecification], name)
            }
            if (requiresFullyQualifiedName(`type`, top_level)) {
                name.append(", ")
                name.append(getScopeFullName(`type`))
    
            }
        }
    }
    private def getScopeFullName(`type`: TypeReference) = {
        `type`.scope match {
            case Some(scope) =>
                scope.metadataScopeType match {
                    case MetadataScopeType.assemblyNameReference => scope.asInstanceOf[AssemblyNameReference].fullName
                    case MetadataScopeType.moduleDefinition => scope.asInstanceOf[ModuleDefinition].assembly.flatMap(_.name).map(_.fullName).getOrElse("")
                    case _ => throw IllegalArgumentException()
                }
            case None => ""
    
        
        }
    }
    private def appendTypeSpecification(`type`: TypeSpecification, name: StringBuilder): Unit = {
        if (`type`.elementType.isTypeSpecification()) {
            appendTypeSpecification(`type`.elementType.asInstanceOf[TypeSpecification], name)
        
        }
        `type`.etype match {
            case ElementType.ptr => name.append('*')
            case ElementType.byRef => name.append('&')
            case ElementType.szArray | ElementType.array =>
                val array = `type`.asInstanceOf[ArrayType]
                if (array.isVector) {
                    name.append("[]")
                }
                else {
                    name.append("[")
                    for i <- 1 until array.rank do name.append(',')
                    name.append(']')
                }
            case ElementType.genericInst =>
                val instance = `type`.asInstanceOf[GenericInstanceType]
                val arguments = instance.genericArguments
                name.append('[')

                for i <- 0 until arguments.length do {
                    if (i > 0) {
                        name.append(',')
                    }
                    val argument = arguments(i)
                    val requires_fqname = argument.scope != argument.module

                    if (requires_fqname) {
                        name.append('[')
                    
                    }
                    appendType(argument, name, true, false)

                    if (requires_fqname) {
                        name.append(']')
                    }
                }
                name.append(']')
            case _ => ()

        }
    }
    private def requiresFullyQualifiedName(`type`: TypeReference, top_level: Boolean) = {
        if (`type`.scope == `type`.module ||
            (`type`.scope.exists(_.name == "mscorlib") && top_level))
            false
        else {
            true        


        }
    }
    val ptr = -1
    val byRef = -2
    val szArray = -3
}
