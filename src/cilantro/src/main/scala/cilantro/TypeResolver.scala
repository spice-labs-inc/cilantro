//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/TypeResolver.cs

package io.spicelabs.cilantro

import io.spicelabs.cilantro.AnyExtension.as
import javax.naming.OperationNotSupportedException
import io.spicelabs.cilantro.cil.VariableReference

class TypeResolver(typeDefinitionContext: GenericInstanceType, methodDefinitionContext: GenericInstanceMethod) {
    private val _typeDefinitionContext: GenericInstance = typeDefinitionContext
    private val _methodDefinitionContext: GenericInstance = methodDefinitionContext

    def this() = this(null, null)
    def this(typeDefinitionContext: GenericInstanceType) = this(typeDefinitionContext, null)
    def this(methodDefinitionContext: GenericInstanceMethod) = this(null, methodDefinitionContext)

    def resolve(method: MethodReference): MethodReference = {
        var methodReference = method
        if (isDummy()) {
            return methodReference
        }

        val declaringType = resolve(method.declaringType)

        val genericInstanceMethod = method.as[GenericInstanceMethod]
        if (genericInstanceMethod != null) {
            methodReference = MethodReference(method.name, method.returnType, declaringType)
            methodReference.parameters.addAll(method.parameters.map(p => ParameterDefinition(p.name, p.attributes, p.parameterType)))
            methodReference.genericParameters.addAll(method.genericParameters.map(gp => GenericParameter(gp.name, methodReference)))
            methodReference.hasThis = method.hasThis
            val m = GenericInstanceMethod(methodReference)
            m.genericArguments.addAll(genericInstanceMethod.genericArguments.map(ga => resolve(ga)))
            methodReference = m
        } else {
            methodReference = MethodReference(method.name, method.returnType, declaringType)
            methodReference.genericParameters.addAll(method.genericParameters.map(gp => GenericParameter(gp.name, methodReference)))
            methodReference.parameters.addAll(method.parameters.map(p => ParameterDefinition(p.name, p.attributes, p.parameterType)))
            methodReference.hasThis = method.hasThis
        }
        methodReference
    }

    def resolve(field: FieldReference): FieldReference = {
        val declaringType = resolve(field.declaringType)
        if (declaringType == field.declaringType) {
            field
        } else {
            FieldReference(field.name, field.fieldType, declaringType)
        }
    }

    def resolveReturnType(method: MethodReference): TypeReference = {
        resolve(GenericParameterResolver.resolveReturnTypeIfNeeded(method))
    }

    def resolveParameterType(method: MethodReference, parameter: ParameterReference): TypeReference = {
        resolve(GenericParameterResolver.resolveParameterTypeIfNeeded(method, parameter))
    }

    def resolveVariableType(method: MethodReference, variable: VariableReference): TypeReference = {
        resolve(GenericParameterResolver.resolveVariableTypeIfNeeded(method, variable))
    }

    def resolveFieldType(field: FieldReference): TypeReference = {
        resolve(GenericParameterResolver.resolveFieldTypeIfNeeded(field))
    }

    def resolve(typeReference: TypeReference): TypeReference = {
        resolve(typeReference, true)
    }

    def resolve(typeReference: TypeReference, includeTypeDefinitions: Boolean): TypeReference = {
        if (isDummy()) {
            return typeReference
        }

        if (_typeDefinitionContext != null && _typeDefinitionContext.genericArguments.contains(typeReference)) {
            return typeReference
        }

        if (_methodDefinitionContext != null && _methodDefinitionContext.genericArguments.contains(typeReference)) {
            return typeReference
        }


        val genericParameter = typeReference.as[GenericParameter]
        if (genericParameter != null) {
            if (_typeDefinitionContext != null && _typeDefinitionContext.genericArguments.contains(genericParameter)) {
                return genericParameter
            }
            if (_methodDefinitionContext != null && _methodDefinitionContext.genericArguments.contains(genericParameter)) {
                return genericParameter
            }
            return resolveGenericParameter(genericParameter)
        }

        val arrayType = typeReference.as[ArrayType]
        if (arrayType != null) {
            return ArrayType(resolve(arrayType.elementType), arrayType.rank)
        }

        val pointerType = typeReference.as[PointerType]
        if (pointerType != null) {
            return PointerType(resolve(pointerType.elementType))
        }

        val byReferenceType = typeReference.as[ByReferenceType]
        if (byReferenceType != null) {
            return ByReferenceType(resolve(byReferenceType.elementType))
        }

        val pinnedType = typeReference.as[PinnedType]
        if (pinnedType != null) {
            return PinnedType(resolve(pinnedType.elementType))
        }

        val genericInstanceType = typeReference.as[GenericInstanceType]
        if (genericInstanceType != null) {
            val newGenericInstanceType = GenericInstanceType(genericInstanceType.elementType)
            newGenericInstanceType.genericArguments.addAll(genericInstanceType.genericArguments.map(ga => resolve(ga)))
            return newGenericInstanceType
        }

        val requiredModType = typeReference.as[RequiredModifierType]
        if (requiredModType != null) {
            return resolve(requiredModType.elementType, includeTypeDefinitions)
        }

        if (includeTypeDefinitions) {
            val typeDefinition = typeReference.as[TypeDefinition]
            if (typeDefinition != null && typeDefinition.hasGenericParameters) {
                val newGenericInstanceType = GenericInstanceType(typeDefinition)
                newGenericInstanceType.genericArguments.addAll(typeDefinition.genericParameters.map(gp => resolve(gp)))
                return newGenericInstanceType
            }
        }

        if (typeReference.isInstanceOf[TypeSpecification]) {
            throw OperationNotSupportedException(s"The type ${typeReference.fullName} cannot be resolved correctly.")
        }
        return typeReference
    }

    def nested(genericInstanceMethod: GenericInstanceMethod): TypeResolver = {
        TypeResolver(_typeDefinitionContext.as[GenericInstanceType], genericInstanceMethod)
    }

    private def resolveGenericParameter(genericParameter: GenericParameter): TypeReference = {
        if (genericParameter.owner == null) {
            return handleOwnerlessInvalidILCode(genericParameter)
        }

        val memberReference = genericParameter.owner.as[MemberReference]
        if (memberReference == null)
            throw OperationNotSupportedException()
        
        if (genericParameter.`type` == GenericParameterType.`type`) {
            return _typeDefinitionContext.genericArguments(genericParameter.position)
        } else {
            if (_methodDefinitionContext != null) {
                _methodDefinitionContext.genericArguments(genericParameter.position)
            } else {
                genericParameter
            }
        }
    }

    private def handleOwnerlessInvalidILCode(genericParameter: GenericParameter): TypeReference = {
        if (genericParameter.`type` == GenericParameterType.method && (_typeDefinitionContext != null && genericParameter.position < _typeDefinitionContext.genericArguments.length)) {
            return _typeDefinitionContext.genericArguments(genericParameter.position)
        }
        genericParameter.module.typeSystem.`object`
    }

    private def isDummy() = {
        _typeDefinitionContext == null && _methodDefinitionContext == null
    }
}

object TypeResolver {
    def `for`(typeReference: TypeReference): TypeResolver = {
        if typeReference.isGenericInstance then TypeResolver(typeReference.asInstanceOf[GenericInstanceType]) else TypeResolver()
    }

    def `for`(typeReference: TypeReference, methodReference: MethodReference): TypeResolver = {
        TypeResolver(typeReference.as[GenericInstanceType], methodReference.as[GenericInstanceMethod])
    }
}