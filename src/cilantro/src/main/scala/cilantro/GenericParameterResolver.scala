//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/GenericParameterResolver.cs

package io.spicelabs.cilantro

import io.spicelabs.cilantro.AnyExtension.as
import io.spicelabs.cilantro.cil.VariableReference
import javax.naming.OperationNotSupportedException

sealed class GenericParameterResolver {
}

object GenericParameterResolver {
    def resolveReturnTypeIfNeeded(methodReference: MethodReference): TypeReference =
        if (methodReference.declaringType.isArray && methodReference.name == "Get")
            return methodReference.returnType
        
        val genericInstanceMethod = methodReference.as[GenericInstanceMethod]
        val declaringGenericInstanceType = methodReference.declaringType.as[GenericInstanceType]

        if (genericInstanceMethod == null && declaringGenericInstanceType == null)
            return methodReference.returnType
        
        resolveIfNeeded(genericInstanceMethod, declaringGenericInstanceType, methodReference.returnType)
    
    def resolveFieldTypeIfNeeded(fieldReference: FieldReference) =
        resolveIfNeeded(null, fieldReference.declaringType.as[GenericInstanceType], fieldReference.fieldType)

    def resolveParameterTypeIfNeeded(method: MethodReference, parameter: ParameterReference): TypeReference =
        val genericInstanceMethod = method.as[GenericInstanceMethod]
        val declaringGenericInstanceType = method.declaringType.as[GenericInstanceType]

        if (genericInstanceMethod == null && declaringGenericInstanceType == null)
            return parameter.parameterType
        
        resolveIfNeeded(genericInstanceMethod, declaringGenericInstanceType, parameter.parameterType)

    def resolveVariableTypeIfNeeded(method: MethodReference, variable: VariableReference): TypeReference =
        val genericInstanceMethod = method.as[GenericInstanceMethod]
        val declaringGenericInstanceType = method.declaringType.as[GenericInstanceType]

        if (genericInstanceMethod == null && declaringGenericInstanceType == null)
            return variable.variableType

        resolveIfNeeded(genericInstanceMethod, declaringGenericInstanceType, variable.variableType)
    
    private def resolveIfNeeded(genericInstanceMethod: GenericInstance, declaringType: GenericInstance, parameterType: TypeReference): TypeReference =
        val byRefType = parameterType.as[ByReferenceType]
        if (byRefType != null)
            return resolveIfNeeded(genericInstanceMethod, declaringType, byRefType)
        
        val arrayType = parameterType.as[ArrayType]
        if (arrayType != null)
            return resolveIfNeeded(genericInstanceMethod, declaringType, arrayType)
        
        val genericInstanceType = parameterType.as[GenericInstanceType]
        if (genericInstanceType != null)
            return resolveIfNeeded(genericInstanceMethod, declaringType, genericInstanceType)

        val genericParameter = parameterType.as[GenericParameter]
        if (genericParameter != null)
            return resolveIfNeeded(genericInstanceMethod, declaringType, genericParameter)
        
        val requiredModifierType = parameterType.as[RequiredModifierType]
        if (requiredModifierType != null && containsGenericParameters(requiredModifierType))
            return resolveIfNeeded(genericInstanceMethod, declaringType, requiredModifierType)
        
        if (containsGenericParameters(parameterType))
            throw IllegalArgumentException("Unexpected generic parameter.")
        
        parameterType

    private def resolveIfNeeded(genericInstanceMethod: GenericInstance, genericInstanceType: GenericInstance, genericParameterElement: GenericParameter): TypeReference =
        if genericParameterElement.metadataType == MetadataType.mVar then
            if genericInstanceMethod != null then genericInstanceMethod.genericArguments(genericParameterElement.position) else genericParameterElement
        else
            genericInstanceType.genericArguments(genericParameterElement.position)
    
    private def resolveIfNeeded(genericInstanceMethod: GenericInstance, genericInstanceType: GenericInstance, arrayType: ArrayType): ArrayType =
        ArrayType(resolveIfNeeded(genericInstanceMethod, genericInstanceType, arrayType.elementType), arrayType.rank)
    
    private def resolveIfNeeded(genericInstanceMethod: GenericInstance, genericInstanceType: GenericInstance, byReferenceType: ByReferenceType): ByReferenceType =
        ByReferenceType(resolveIfNeeded(genericInstanceMethod, genericInstanceType, byReferenceType.elementType))

    private def resolveIfNeeded(genericInstanceMethod: GenericInstance, genericInstanceType: GenericInstance, genericInstanceType1: GenericInstanceType): GenericInstanceType =
        if (!containsGenericParameters(genericInstanceType1))
            return genericInstanceType1

        val newGenericInstance = GenericInstanceType(genericInstanceType1.elementType)

        for genericArgument <- genericInstanceType1.genericArguments do
            if (!genericArgument.isGenericParameter)
                newGenericInstance.genericArguments.addOne(resolveIfNeeded(genericInstanceMethod, genericInstanceType, genericArgument))
            else
                val genParam = genericArgument.asInstanceOf[GenericParameter]
                genParam.`type` match
                    case GenericParameterType.`type` =>
                        if (genericInstanceType == null)
                            throw OperationNotSupportedException()
                        newGenericInstance.genericArguments.addOne(genericInstanceType.genericArguments(genParam.position))
                    case GenericParameterType.method =>
                        if (genericInstanceMethod == null)
                            newGenericInstance.genericArguments.addOne(genParam)
                        else
                            newGenericInstance.genericArguments.addOne(genericInstanceMethod.genericArguments(genParam.position))
        newGenericInstance
    
    private def containsGenericParameters(typeReference: TypeReference): Boolean =
        val genericParameter = typeReference.as[GenericParameter]
        if (genericParameter != null)
            return true
        
        val arrayType = typeReference.as[ArrayType]
        if (arrayType != null)
            return containsGenericParameters(arrayType.elementType)
        
        val pointerType = typeReference.as[PointerType]
        if (pointerType != null)
            return containsGenericParameters(pointerType.elementType)

        val byReferenceType = typeReference.as[ByReferenceType]
        if (byReferenceType != null)
            return containsGenericParameters(byReferenceType.elementType)
        
        val sentinelType = typeReference.as[SentinelType]
        if (sentinelType != null)
            return containsGenericParameters(sentinelType.elementType)
        
        val pinnedType = typeReference.as[PinnedType]
        if (pinnedType != null)
            return containsGenericParameters(pinnedType.elementType)
        
        val requiredModifierType = typeReference.as[RequiredModifierType]
        if (requiredModifierType != null)
            return containsGenericParameters(requiredModifierType.elementType)
        
        val genericInstance = typeReference.as[GenericInstanceType]
        if (genericInstance != null)
            return genericInstance.genericArguments.exists(containsGenericParameters)
        
        if (typeReference.isInstanceOf[TypeSpecification])
            throw OperationNotSupportedException()
        return false

                        
}
