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
import java.lang.reflect.Method
import io.spicelabs.cilantro.DefaultReflectionImporter.isNestedType
import java.lang.reflect.ParameterizedType
import io.spicelabs.cilantro.DefaultReflectionImporter.ImportGenericKind
import io.spicelabs.cilantro.DefaultReflectionImporter.genericParameters
import java.lang.Runtime.Version
import java.lang.module.ModuleDescriptor
import scala.jdk.OptionConverters._
import io.spicelabs.cilantro.DefaultReflectionImporter.moduleVersion
import java.lang.reflect.Field
import io.spicelabs.cilantro.DefaultReflectionImporter.isGenericInstance
import java.lang.reflect.Modifier

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

// this is a weird thing to port since we can't actually reflect on .NET types, but...
class DefaultReflectionImporter(protected val module: ModuleDefinition) extends ReflectionImporter {

    def importType(`type`: Class[?], context: ImportGenericContext, required_modifiers: Array[Class[?]], optional_modifiers: Array[Class[?]]): TypeReference = {
        var `import` = importType(`type`, context);
        
        for modifier <- required_modifiers do {
            `import` = RequiredModifierType(importType(modifier, context), `import`)
        }

        for modifier <- optional_modifiers do {
            `import` = OptionalModifierType(importType(modifier, context), `import`)
        }
        `import`
    }

    def importType(`type`: Class[?], context: ImportGenericContext): TypeReference = {
        importType(`type`, context, ImportGenericKind.open)
    }

    def importType(`type`: Class[?], context: ImportGenericContext, import_kind: ImportGenericKind): TypeReference = {
        import DefaultReflectionImporter.isTypeSpecification
        import DefaultReflectionImporter.importOpenGenericType
        import DefaultReflectionImporter.isValueType
        import DefaultReflectionImporter.importElementType
        import DefaultReflectionImporter.importGenericParameters

        if (isTypeSpecification(`type`) || importOpenGenericType(`type`, import_kind))
            return importTypeSpecification(`type`, context)
        
        val reference = TypeReference("", `type`.getSimpleName(), module, importScope(`type`), isValueType(`type`))
        reference.etype = importElementType(`type`)
        if (isNestedType(`type`)) {
            reference.declaringType = importType(`type`.getDeclaringClass(), context, import_kind)
        } else {
            reference.nameSpace = `type`.getPackage().getName()
        }

        if (`type`.getTypeParameters().length > 0)
            importGenericParameters(reference, `type`.getTypeParameters().map(ty => Class.forName(ty.getTypeName())))
        reference
    }

    protected def importScope(`type`: Class[?]): MetadataScope = {
        importScope(`type`.getModule())
    }


    def importTypeSpecification(`type`: Class[?], context: ImportGenericContext): TypeReference = {
        // this doesn't exist in java
        // if (`type`.isByRef)
        //     return ByReferenceType(ImportType(`type`.getElementType(), context))
        // if (`type`.isPointer)
        //     return PointerType(ImportType(`type`.getElementType(), context))
        
        if (`type`.isArray())
            return ArrayType(importType(`type`.arrayType(), context), 1 /*`type`.getArrayRank()*/)
        
        // if (`type`.isGenericParameter())
        //     return ImportGenericParameter(`type`, context)
        
        throw OperationNotSupportedException(`type`.getName())
    }

    def importGenericInstance(`type`: Class[?], context: ImportGenericContext): TypeReference = {
        val element_type = importType(`type`, context, ImportGenericKind.definition)
        val arguments = genericParameters(`type`)
        val instance = GenericInstanceType(element_type, arguments.length)
        val instance_arguments = instance.genericArguments

        context.push(element_type)
        try {
            for i <- 0 until arguments.length do {
                instance_arguments.addOne((importType(arguments(i), context)))
            }
            return instance
        } finally {
            context.pop()
        }
    }

    protected def importScope(assembly: Module): AssemblyNameReference = {
        importReference(assembly.getName(), assembly.getDescriptor())
    }

    def importReference(name: String, desc: ModuleDescriptor): AssemblyNameReference = {
        tryGetAssemblyNameReference(name, desc) match {
            case Some(reference) => reference
            case None => {
                val reference = AssemblyNameReference(name, moduleVersion(desc))
                module.assemblyReferences.addOne(reference)
                reference
            }
        }
    }

    def tryGetAssemblyNameReference(name: String, desc: ModuleDescriptor): Option[AssemblyNameReference] = {
        module.assemblyReferences.find(ref => name == ref.fullName)
    }

    def importField(field: Field, context: ImportGenericContext): FieldReference = {
        import DefaultReflectionImporter.resolveFieldDefinition
        val declaring_type = importType(field.getDeclaringClass(), context)

        var localField = if isGenericInstance(field.getDeclaringClass()) then resolveFieldDefinition(field) else field

        context.push(declaring_type)
        try {
            return FieldReference(localField.getName(), declaring_type, importType(localField.getType(), context, Array[Class[?]](), Array[Class[?]]()))
        } finally {
            context.pop()
        }
    }

    def importMethod(method: Method, context: ImportGenericContext, import_kind: ImportGenericKind): MethodReference = {
        import DefaultReflectionImporter.importGenericParameters
        import DefaultReflectionImporter.isMethodSpecification
        import DefaultReflectionImporter.importOpenGenericMethod
        import DefaultReflectionImporter.resolveMethodDefinition
        if (isMethodSpecification(method) || importOpenGenericMethod(method, import_kind))
            return importMethodSpecification(method, context)
        
        val declaring_type = importType(method.getDeclaringClass(), context)

        val localMethod = if (isGenericInstance(method.getDeclaringClass())) then resolveMethodDefinition(method) else method

        val reference = MethodReference()
        reference.name = localMethod.getName()
        reference.hasThis = !Modifier.isStatic(localMethod.getModifiers())
        reference.declaringType = importType(localMethod.getDeclaringClass(), context, ImportGenericKind.definition)
        
        if (localMethod.isVarArgs())
            reference.callingConvention = MethodCallingConvention.fromOrdinalValue(reference.callingConvention.value & MethodCallingConvention.varArg.value)
        
        if (localMethod.getTypeParameters().length > 0) {
            importGenericParameters(reference, localMethod.getParameterTypes())
        }

        context.push(reference)
        try {
            reference.returnType = importType(localMethod.getReturnType(), context, Array[Class[?]](), Array[Class[?]]())

            val parameters = method.getParameters()
            val reference_parameters = reference.parameters
            for i <- 0 until parameters.length do {
                val parameter = parameters(i)
                reference_parameters.addOne(ParameterDefinition(importType(parameter.getType(),context, Array[Class[?]](), Array[Class[?]]())))
            }
            reference.declaringType = declaring_type
            reference
        } finally {
            context.pop()
        }
    }

    def importMethodSpecification(method: Method, context: ImportGenericContext): MethodReference = {
        val element_method = importMethod(method, context, ImportGenericKind.definition)
        val instance = GenericInstanceMethod(element_method)
        val arguments = method.getGenericParameterTypes().map(ty => ty.getClass().asInstanceOf[Class[?]])
        val instance_arguments = instance.genericArguments

        context.push(element_method)
        try {
            for i <- 0 until arguments.length do {
                instance_arguments.addOne(importType(arguments(i), context))
            }
            instance
        } finally {
            context.pop()
        }        
    }

    def importReference(`type`: Class[?], context: GenericParameterProvider): TypeReference = {
        importType(`type`, ImportGenericContext.`for`(context))
    }

    def importReference(field: Field, context: GenericParameterProvider): FieldReference = {
        importField(field, ImportGenericContext.`for`(context))
    }

    def importReference(method: Method, context: GenericParameterProvider): MethodReference = {
        importMethod(method, ImportGenericContext.`for`(context), if context != null then ImportGenericKind.open else ImportGenericKind.definition)
    }
}

object DefaultReflectionImporter {
    enum ImportGenericKind {
        case definition
        case open
    }
    // this table is missing:
    // unsigned byte
    // unsigned short
    // unsigned int
    // unsigned long
    // TypedReference
    // IntPtr
    // UIntPtr
    // as none of these exist in Java land
    val type_etype_mapping: Map[Class[?], ElementType] = Map(
        classOf[java.lang.Void] -> ElementType.void,
        classOf[Boolean] -> ElementType.boolean,
        classOf[Char] -> ElementType.char,
        classOf[Byte] -> ElementType.i1,
        classOf[Short] -> ElementType.i2,
        classOf[Int] -> ElementType.i4,
        classOf[Long] -> ElementType.i8,
        classOf[Float] -> ElementType.r4,
        classOf[Double] -> ElementType.r8,
        classOf[String] -> ElementType.string,
        classOf[Object] -> ElementType.`object`
    )

    def importOpenGenericType(`type`: Class[?], import_kind: DefaultReflectionImporter.ImportGenericKind): Boolean = {
        `type`.getTypeParameters().length > 0 && import_kind == ImportGenericKind.open
    }

    def importOpenGenericMethod(method: Method, import_kind: ImportGenericKind): Boolean = {
        `method`.getTypeParameters().length > 0 && import_kind == ImportGenericKind.open
    }

    def isNestedType(`type`: Class[?]) = {
        `type`.getNestMembers().length > 0
    }

    def importGenericParameter(`type`: Class[?], context: ImportGenericContext): TypeReference = {
        if (context.isEmpty)
            throw OperationNotSupportedException();
        
        if (`type`.getEnclosingMethod() != null)
            return context.methodParameter(normalizeMethodName(`type`.getEnclosingMethod()), genericIndex(`type`))

        if (`type`.getEnclosingClass() != null)
            return context.typeParameter(normalizeTypeFullName(`type`.getEnclosingClass()), genericIndex(`type`))

        throw OperationNotSupportedException();
    }

    def normalizeMethodName(method: Method): String = {
        s"${normalizeTypeFullName(method.getDeclaringClass())}.${method.getName()}"
    }

    def normalizeTypeFullName(`type`: Class[?]): String = {
        if (`type`.getDeclaringClass() != null)
            return normalizeTypeFullName(`type`.getDeclaringClass()) + "/" + `type`.getSimpleName()
        `type`.getName()
    }

    def isTypeSpecification(`type`: Class[?]): Boolean = {
        isGenericInstance(`type`) || `type`.getEnclosingClass() != null
    }

    def isGenericInstance(`type`: Class[?]): Boolean = {
        `type`.getTypeParameters().length > 0
    }

    def importElementType(`type`: Class[?]): ElementType = {
        type_etype_mapping.getOrElse(`type`, ElementType.none)
    }

    def genericIndex(`type`: Class[?]): Int = {
        genericParameters(`type`).indexOf(`type`)
    }

    def genericParameters(`type`: Class[?]): Array[Class[?]] = {
        `type`.getGenericSuperclass().as[ParameterizedType].getActualTypeArguments().map(t => t.as[Class[?]])
    }

    def moduleVersion(desc: ModuleDescriptor): CSVersion = {
        val versionOpt = desc.version().toScala
        versionOpt match {
            case Some(vers) => CSVersion.parse(vers.toString()).getOrElse(CSVersion(0, 0, 0, 0))
            case None => CSVersion(0, 0, 0, 0)
        }
    }

    def resolveFieldDefinition(field: Field): Field = {
        // TODO compare with what .NET does in System.Reflection
        field
    }

    def resolveMethodDefinition(method: Method): Method = {
        // TODO compare with what .NET does
        method
    }

    def importGenericParameters(provider: GenericParameterProvider, arguments: Array[Class[?]]): Unit = {
        val provider_parameters = provider.genericParameters
        for i <- 0 until arguments.length do {
            provider_parameters.addOne(GenericParameter(arguments(i).getName(), provider))
        }
    }

    def isMethodSpecification(method: Method): Boolean = {
        method.getGenericParameterTypes().length > 0 && false // java doesn't really have this notion
    }

    private lazy val _valueTypes = Array[Class[?]](classOf[Boolean], classOf[Byte], classOf[Short],
        classOf[Int], classOf[Long], classOf[Char], classOf[Float], classOf[Double])
    def isValueType(ty: Class[?]) = _valueTypes.contains(ty)

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
                    reference._parameters = ParameterDefinitionCollection(reference)
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

