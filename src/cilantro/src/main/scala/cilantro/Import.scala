//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/Import.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer
import io.spicelabs.cilantro.AnyExtension.as
import javax.naming.OperationNotSupportedException
import io.spicelabs.cilantro.ImportGenericContext.genericTypeFor
import scala.compiletime.ops.any
import io.spicelabs.cilantro.metadata.ElementType
import io.spicelabs.cilantro.DefaultMetadataImport.importGenericParameters
import scala.annotation.tailrec

trait MetadataImporterProvider {
    def getMetadataImporter(module: ModuleDefinition): MetadataImporter
}

trait MetadataImporter {
    def importReference(reference: AssemblyNameReference): AssemblyNameReference
    def importReference(`type`: TypeReference, context: GenericParameterProvider): TypeReference
    def importReference(field: FieldReference, context: GenericParameterProvider): FieldReference
    def importReference(method: MethodReference, context: GenericParameterProvider): MethodReference
}

trait ReflectionImporterProvider {
    def getReflectionImporter(module: ModuleDefinition) : ReflectionImporter
}

trait ReflectionImporter { // TODO

}

sealed class ImportGenericContext(_provider: GenericParameterProvider) {
    if (_provider == null)
        throw IllegalArgumentException("_provider")
    private var stack: ArrayBuffer[GenericParameterProvider] = null
    push(_provider)

    def isEmpty = stack == null

    def push(provider: GenericParameterProvider) =
        if (stack == null)
            stack = ArrayBuffer[GenericParameterProvider](provider)
        else
            stack.addOne(provider)
    
    def pop() =
        stack.remove(stack.length - 1)

    @tailrec
    private def methodParameterFind(method: String, position: Int, curr: Int): Option[TypeReference] =
        if (curr < 0)
            None
        else
            val candidate = stack(curr).as[MethodReference]
            if (candidate != null && method == normalizeMethodName(candidate))
                Some(candidate.genericParameters(position))
            else
                methodParameterFind(method, position, curr - 1)
    
    def methodParameter(method: String, position: Int): TypeReference =
        methodParameterFind(method, position, stack.length - 1) match
            case Some(me) => me
            case None => throw OperationNotSupportedException()

    def normalizeMethodName(method: MethodReference) =
        method.declaringType.getElementType().fullName + "." + method.name
    
    @tailrec
    private def typeParameterFind(`type`: String, position: Int, curr: Int): Option[TypeReference] =
        if (curr < 0)
            None
        else
            val candidate = genericTypeFor(stack(curr))
            if (candidate.fullName == `type`)
                Some(candidate.genericParameters(position))
            else
                typeParameterFind(`type`, position, curr - 1)


    def typeParameter(`type`: String, position: Int): TypeReference =
        typeParameterFind(`type`, position, stack.length - 1) match
            case Some(ty) => ty
            case None => throw OperationNotSupportedException()

}

object ImportGenericContext {
    private def genericTypeFor(context: GenericParameterProvider): TypeReference =
        val `type` = context.as[TypeReference]
        if (`type` != null)
            return `type`.getElementType()

        val method = context.as[MethodReference]
        if (method != null)
            return method.declaringType.getElementType()
        
        throw OperationNotSupportedException()

    def `for`(context: GenericParameterProvider) =
        if context != null then ImportGenericContext(context) else null
}

class DefaultMetadataImport(protected val module: ModuleDefinition) extends MetadataImporter {

    def importType(`type`: TypeReference, context: ImportGenericContext): TypeReference =
        if (`type`.isTypeSpecification())
            return importTypeSpecification(`type`, context)

        val reference = TypeReference(`type`.nameSpace, `type`.name, module, importScope(`type`), `type`.isValueType)

        MetadataSystem.tryProcessPrimitiveTypeReference(reference)

        if (`type`.isNested)
            reference.declaringType = importType(`type`.declaringType, context)
        
        if (`type`.hasGenericParameters)
            importGenericParameters(reference, `type`)
        
        reference

    protected def importScope(`type`: TypeReference): MetadataScope =
        importScope(`type`.scope)

    protected def importScope(scope: MetadataScope) =
        scope.metadataScopeType match
            case MetadataScopeType.assemblyNameReference =>
                importReference(scope.asInstanceOf[AssemblyNameReference])
            case MetadataScopeType.moduleDefinition =>
                if scope == module then scope else importReference(scope.asInstanceOf[ModuleDefinition].assembly.name)
            case MetadataScopeType.moduleReference => 
                throw NotImplementedError()
            
    def importReference(name: AssemblyNameReference) =
        module.tryGetAssemblyNameReference(name) match
            case Some(any) => any
            case None =>
                val reference = AssemblyNameReference(name.name, name.version)
                reference.culture = name.culture
                reference.hashAlgorithm = name.hashAlgorithm
                reference.isRetargetable = name.isRetargetable
                reference.isWindowsRuntime = name.isWindowsRuntime

                val pk_token =
                    if name.publicKeyToken != null && name.publicKeyToken.length > 0
                        then Array.ofDim[Byte](name.publicKeyToken.length)
                        else Array.emptyByteArray
                if (pk_token.length > 0)
                    Array.copy(name.publicKeyToken, 0, pk_token, 0, pk_token.length)
                reference.publicKeyToken = pk_token
                module.assemblyReferences.addOne(reference)
                reference
        
    private def importTypeSpecification(`type`: TypeReference, context: ImportGenericContext): TypeReference =
        `type`.etype match
            case ElementType.szArray =>
                val vector = `type`.asInstanceOf[ArrayType]
                ArrayType(importType(vector.elementType, context))
            case ElementType.ptr =>
                val pointer = `type`.asInstanceOf[PointerType]
                PointerType(importType(pointer.elementType, context))
            case ElementType.byRef =>
                val byref = `type`.asInstanceOf[ByReferenceType]
                ByReferenceType(importType(byref.elementType, context))
            case ElementType.pinned =>
                val pinned = `type`.asInstanceOf[PinnedType]
                PinnedType(importType(pinned.elementType, context))
            case ElementType.sentinel =>
                val sentinel = `type`.asInstanceOf[SentinelType]
                SentinelType(importType(sentinel.elementType, context))
            case ElementType.fnPtr =>
                val fnptr = `type`.asInstanceOf[FunctionPointerType]
                val imported_fnptr = FunctionPointerType()
                imported_fnptr.hasThis = fnptr.hasThis
                imported_fnptr.explicitThis = fnptr.explicitThis
                imported_fnptr.callingConvention = fnptr.callingConvention
                imported_fnptr.returnType = importType(fnptr.returnType, context)
                if (!fnptr.hasParameters)
                    imported_fnptr
                else
                    imported_fnptr.parameters.appendAll(fnptr.parameters.map((p) => ParameterDefinition(importType(p.parameterType, context))))
                    imported_fnptr
            case ElementType.cModOpt =>
                val modopt = `type`.asInstanceOf[OptionalModifierType]
                OptionalModifierType(importType(modopt.modifierType, context), importType(modopt.elementType, context))
            case ElementType.cModReqD =>
                val modreq = `type`.asInstanceOf[RequiredModifierType]
                RequiredModifierType(importType(modreq.modifierType, context), importType(modreq.elementType, context))
            case ElementType.array =>
                val array = `type`.asInstanceOf[ArrayType]
                val imported_array = ArrayType(importType(array.elementType, context))
                if (array.isVector)
                    imported_array
                else
                    val dimensions = array.dimensions
                    val imported_dimensions = imported_array.dimensions
                    imported_dimensions.clear()
                    imported_dimensions.appendAll(dimensions.map((d) => ArrayDimension(d.lowerBound, d.upperBound)))
                    imported_array
            case ElementType.genericInst =>
                val instance = `type`.asInstanceOf[GenericInstanceType]
                val element_type = importType(instance.elementType, context)
                val arguments = instance.genericArguments
                val imported_instance = GenericInstanceType(element_type, arguments.length)
                imported_instance.genericArguments.addAll(arguments.map((arg) => importType(arg, context)))
                imported_instance
            case ElementType.`var` =>
                val var_parameter = `type`.asInstanceOf[GenericParameter]
                if (var_parameter.declaringType == null)
                    throw OperationNotSupportedException()
                context.typeParameter(var_parameter.declaringType.fullName, var_parameter.position)
            case ElementType.mVar =>
                val mvar_parameter = `type`.asInstanceOf[GenericParameter]
                if (mvar_parameter.declaringMethod == null)
                    throw OperationNotSupportedException()
                context.methodParameter(context.normalizeMethodName(mvar_parameter.declaringMethod), mvar_parameter.position)
            case _ => throw OperationNotSupportedException(`type`.etype.toString())
    
    private def importField(field: FieldReference, context: ImportGenericContext) =
        val declaring_type = importType(field.declaringType, context)
        context.push(declaring_type)
        try
            FieldReference(field.name, importType(field.fieldType, context), declaring_type)
        finally
            context.pop()
    
    private def importMethod(method: MethodReference, context: ImportGenericContext): MethodReference =
        if (method.isGenericInstance)
            importMethodSpecification(method, context)
        else
            val declaring_type = importType(method.declaringType, context)
            val reference = MethodReference()
            reference.name = method.name
            reference.hasThis = method.hasThis
            reference.explicitThis = method.explicitThis
            reference.declaringType = declaring_type
            reference.callingConvention = method.callingConvention

            if (method.hasGenericParameters)
                importGenericParameters(reference, method)

            context.push(reference)
            try            
                if (!method.hasParameters)
                    reference
                else
                    reference._parameters = ParameterDefinitionCollection()
                    reference._parameters.addAll(method.parameters.map((p) => ParameterDefinition(importType(p.parameterType, context))))
                    reference
            finally
                context.pop()

    private def importMethodSpecification(method: MethodReference, context: ImportGenericContext) =
        if (!method.isGenericInstance)
            throw OperationNotSupportedException()
        
        val instance = method.asInstanceOf[GenericInstanceMethod]
        val element_method = importMethod(instance.elementMethod, context)
        val imported_instance = GenericInstanceMethod(element_method)

        val arguments = instance.genericArguments
        var imported_arguments = imported_instance.genericArguments

        imported_arguments.addAll(arguments.map((arg) => importType(arg, context)))
        imported_instance

    def importReference(`type`: TypeReference, context: GenericParameterProvider): TypeReference =
        checkType(`type`)
        importType(`type`, ImportGenericContext.`for`(context))
    
    def importReference(field: FieldReference, context: GenericParameterProvider): FieldReference =
        checkField(field)
        importField(field, ImportGenericContext.`for`(context))
    
    def importReference(method: MethodReference, context: GenericParameterProvider): MethodReference =
        checkMethod(method)
        importMethod(method, ImportGenericContext.`for`(context))
}   

object DefaultMetadataImport {
    private def importGenericParameters(imported: GenericParameterProvider, original: GenericParameterProvider) =
        val parameters = original.genericParameters
        val imported_parameters = imported.genericParameters
        for parameter <- parameters do
            imported_parameters.addOne(GenericParameter(parameter.name, imported))


}

