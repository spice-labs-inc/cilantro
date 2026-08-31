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
    def resolveReturnTypeIfNeeded(methodReference: MethodReference): TypeReference = {
        if (methodReference.declaringType.exists(_.isArray) && methodReference.name == "Get") {
            return methodReference.returnType
        
        }
        val genericInstanceMethod = methodReference.as[GenericInstanceMethod]
        val declaringGenericInstanceType = methodReference.declaringType.flatMap(_.as[GenericInstanceType])

        if (genericInstanceMethod.isEmpty && declaringGenericInstanceType.isEmpty) {
            return methodReference.returnType
        
        }
        resolveIfNeeded(genericInstanceMethod, declaringGenericInstanceType, methodReference.returnType)
    
    }
    def resolveFieldTypeIfNeeded(fieldReference: FieldReference) = {
        resolveIfNeeded(None, fieldReference.declaringType.flatMap(_.as[GenericInstanceType]), fieldReference.fieldType)

    }
    def resolveParameterTypeIfNeeded(method: MethodReference, parameter: ParameterReference): TypeReference = {
        val genericInstanceMethod = method.as[GenericInstanceMethod]
        val declaringGenericInstanceType = method.declaringType.flatMap(_.as[GenericInstanceType])

        if (genericInstanceMethod.isEmpty && declaringGenericInstanceType.isEmpty) {
            return parameter.parameterType
        
        }
        resolveIfNeeded(genericInstanceMethod, declaringGenericInstanceType, parameter.parameterType)

    }
    def resolveVariableTypeIfNeeded(method: MethodReference, variable: VariableReference): TypeReference = {
        val genericInstanceMethod = method.as[GenericInstanceMethod]
        val declaringGenericInstanceType = method.declaringType.flatMap(_.as[GenericInstanceType])

        if (genericInstanceMethod.isEmpty && declaringGenericInstanceType.isEmpty) {
            return variable.variableType

        }
        resolveIfNeeded(genericInstanceMethod, declaringGenericInstanceType, variable.variableType)
    
    }
    private def resolveIfNeeded(genericInstanceMethod: Option[GenericInstance], declaringType: Option[GenericInstance], parameterType: TypeReference): TypeReference = {
        parameterType.as[ByReferenceType] match {
            case Some(byRefType) => return resolveIfNeeded(genericInstanceMethod, declaringType, byRefType)
            case None => ()
        }
        parameterType.as[ArrayType] match {
            case Some(arrayType) => return resolveIfNeeded(genericInstanceMethod, declaringType, arrayType)
            case None => ()
        }
        parameterType.as[GenericInstanceType] match {
            case Some(genericInstanceType) => return resolveIfNeeded(genericInstanceMethod, declaringType, genericInstanceType)
            case None => ()
        }
        parameterType.as[GenericParameter] match {
            case Some(genericParameter) => return resolveIfNeeded(genericInstanceMethod, declaringType, genericParameter)
            case None => ()
        }
        parameterType.as[RequiredModifierType] match {
            case Some(requiredModifierType) if containsGenericParameters(requiredModifierType) =>
                return resolveIfNeeded(genericInstanceMethod, declaringType, requiredModifierType)
            case _ => ()
        }
        if (containsGenericParameters(parameterType)) {
            throw IllegalArgumentException("Unexpected generic parameter.")
        
        }
        parameterType

    }
    private def resolveIfNeeded(genericInstanceMethod: Option[GenericInstance], genericInstanceType: Option[GenericInstance], genericParameterElement: GenericParameter): TypeReference = {
        if genericParameterElement.metadataType == MetadataType.mVar then {
            genericInstanceMethod.map(_.genericArguments(genericParameterElement.position)).getOrElse(genericParameterElement)
        }
        else {
            genericInstanceType.map(_.genericArguments(genericParameterElement.position)).getOrElse(genericParameterElement)
    
        }
    }
    private def resolveIfNeeded(genericInstanceMethod: Option[GenericInstance], genericInstanceType: Option[GenericInstance], arrayType: ArrayType): ArrayType = {
        ArrayType(resolveIfNeeded(genericInstanceMethod, genericInstanceType, arrayType.elementType), arrayType.rank)
    
    }
    private def resolveIfNeeded(genericInstanceMethod: Option[GenericInstance], genericInstanceType: Option[GenericInstance], byReferenceType: ByReferenceType): ByReferenceType = {
        ByReferenceType(resolveIfNeeded(genericInstanceMethod, genericInstanceType, byReferenceType.elementType))

    }
    private def resolveIfNeeded(genericInstanceMethod: Option[GenericInstance], genericInstanceType: Option[GenericInstance], genericInstanceType1: GenericInstanceType): GenericInstanceType = {
        if (!containsGenericParameters(genericInstanceType1)) {
            return genericInstanceType1

        }
        val newGenericInstance = GenericInstanceType(genericInstanceType1.elementType)

        for genericArgument <- genericInstanceType1.genericArguments do {
            if (!genericArgument.isGenericParameter) {
                newGenericInstance.genericArguments.addOne(resolveIfNeeded(genericInstanceMethod, genericInstanceType, genericArgument))
            }
            else {
                val genParam = genericArgument.asInstanceOf[GenericParameter]
                genParam._type match {
                    case GenericParameterType.`type` =>
                        genericInstanceType match {
                            case None => throw OperationNotSupportedException()
                            case Some(git) => newGenericInstance.genericArguments.addOne(git.genericArguments(genParam.position))
                        }
                    case GenericParameterType.method =>
                        genericInstanceMethod match {
                            case None => newGenericInstance.genericArguments.addOne(genParam)
                            case Some(gim) => newGenericInstance.genericArguments.addOne(gim.genericArguments(genParam.position))
                        }
                }
            }
        }
        newGenericInstance
    
    }
    private def containsGenericParameters(typeReference: TypeReference): Boolean = {
        if (typeReference.as[GenericParameter].isDefined) {
            return true
        
        }
        typeReference.as[ArrayType] match {
            case Some(arrayType) => return containsGenericParameters(arrayType.elementType)
            case None => ()
        }
        typeReference.as[PointerType] match {
            case Some(pointerType) => return containsGenericParameters(pointerType.elementType)
            case None => ()
        }
        typeReference.as[ByReferenceType] match {
            case Some(byReferenceType) => return containsGenericParameters(byReferenceType.elementType)
            case None => ()
        }
        typeReference.as[SentinelType] match {
            case Some(sentinelType) => return containsGenericParameters(sentinelType.elementType)
            case None => ()
        }
        typeReference.as[PinnedType] match {
            case Some(pinnedType) => return containsGenericParameters(pinnedType.elementType)
            case None => ()
        }
        typeReference.as[RequiredModifierType] match {
            case Some(requiredModifierType) => return containsGenericParameters(requiredModifierType.elementType)
            case None => ()
        }
        typeReference.as[GenericInstanceType] match {
            case Some(genericInstance) => return genericInstance.genericArguments.exists(containsGenericParameters)
            case None => ()
        }
        if (typeReference.isInstanceOf[TypeSpecification]) {
            throw OperationNotSupportedException()
        }
        return false

                        
    }
}
