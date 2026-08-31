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

class TypeResolver(typeDefinitionContext: Option[GenericInstanceType], methodDefinitionContext: Option[GenericInstanceMethod]) {
    private val _typeDefinitionContext: Option[GenericInstance] = typeDefinitionContext
    private val _methodDefinitionContext: Option[GenericInstance] = methodDefinitionContext

    def this() = this(None, None)
    def this(typeDefinitionContext: GenericInstanceType) = this(Some(typeDefinitionContext), None)
    def this(methodDefinitionContext: GenericInstanceMethod) = this(None, Some(methodDefinitionContext))

    def resolve(method: MethodReference): MethodReference = {
        var methodReference = method
        if (isDummy()) {
            return methodReference
        }

        val declaringType = method.declaringType.map(resolve)

        method.as[GenericInstanceMethod] match {
            case Some(genericInstanceMethod) =>
                methodReference = MethodReference(method.name, method.returnType, declaringType)
                methodReference.parameters.addAll(method.parameters.map(p => ParameterDefinition(p.name, p.attributes, p.parameterType)))
                methodReference.genericParameters.addAll(method.genericParameters.map(gp => GenericParameter(gp.name, Some(methodReference))))
                methodReference.hasThis = method.hasThis
                val m = GenericInstanceMethod(methodReference)
                m.genericArguments.addAll(genericInstanceMethod.genericArguments.map(ga => resolve(ga)))
                methodReference = m
            case None =>
                methodReference = MethodReference(method.name, method.returnType, declaringType)
                methodReference.genericParameters.addAll(method.genericParameters.map(gp => GenericParameter(gp.name, Some(methodReference))))
                methodReference.parameters.addAll(method.parameters.map(p => ParameterDefinition(p.name, p.attributes, p.parameterType)))
                methodReference.hasThis = method.hasThis
        }
        methodReference
    }

    def resolve(field: FieldReference): FieldReference = {
        val declaringType = field.declaringType.map(resolve)
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

        if (_typeDefinitionContext.exists(_.genericArguments.contains(typeReference))) {
            return typeReference
        }

        if (_methodDefinitionContext.exists(_.genericArguments.contains(typeReference))) {
            return typeReference
        }


        typeReference.as[GenericParameter] match {
            case Some(genericParameter) =>
                if (_typeDefinitionContext.exists(_.genericArguments.contains(genericParameter))) {
                    return genericParameter
                }
                if (_methodDefinitionContext.exists(_.genericArguments.contains(genericParameter))) {
                    return genericParameter
                }
                return resolveGenericParameter(genericParameter)
            case None => ()
        }

        typeReference.as[ArrayType] match {
            case Some(arrayType) => return ArrayType(resolve(arrayType.elementType), arrayType.rank)
            case None => ()
        }

        typeReference.as[PointerType] match {
            case Some(pointerType) => return PointerType(resolve(pointerType.elementType))
            case None => ()
        }

        typeReference.as[ByReferenceType] match {
            case Some(byReferenceType) => return ByReferenceType(resolve(byReferenceType.elementType))
            case None => ()
        }

        typeReference.as[PinnedType] match {
            case Some(pinnedType) => return PinnedType(resolve(pinnedType.elementType))
            case None => ()
        }

        typeReference.as[GenericInstanceType] match {
            case Some(genericInstanceType) =>
                val newGenericInstanceType = GenericInstanceType(genericInstanceType.elementType)
                newGenericInstanceType.genericArguments.addAll(genericInstanceType.genericArguments.map(ga => resolve(ga)))
                return newGenericInstanceType
            case None => ()
        }

        typeReference.as[RequiredModifierType] match {
            case Some(requiredModType) => return resolve(requiredModType.elementType, includeTypeDefinitions)
            case None => ()
        }

        if (includeTypeDefinitions) {
            typeReference.as[TypeDefinition] match {
                case Some(typeDefinition) if typeDefinition.hasGenericParameters =>
                    val newGenericInstanceType = GenericInstanceType(typeDefinition)
                    newGenericInstanceType.genericArguments.addAll(typeDefinition.genericParameters.map(gp => resolve(gp)))
                    return newGenericInstanceType
                case _ => ()
            }
        }

        if (typeReference.isInstanceOf[TypeSpecification]) {
            throw OperationNotSupportedException(s"The type ${typeReference.fullName} cannot be resolved correctly.")
        }
        return typeReference
    }

    def nested(genericInstanceMethod: GenericInstanceMethod): TypeResolver = {
        TypeResolver(_typeDefinitionContext.flatMap(_.as[GenericInstanceType]), Some(genericInstanceMethod))
    }

    private def resolveGenericParameter(genericParameter: GenericParameter): TypeReference = {
        if (genericParameter.owner.isEmpty) {
            return handleOwnerlessInvalidILCode(genericParameter)
        }

        val memberReference = genericParameter.owner.flatMap(_.as[MemberReference])
        if (memberReference.isEmpty) {
            throw OperationNotSupportedException()
        
        }
        if (genericParameter.`type` == GenericParameterType.`type`) {
            return _typeDefinitionContext.map(_.genericArguments(genericParameter.position)).getOrElse(genericParameter)
        } else {
            _methodDefinitionContext match {
                case Some(mdc) => mdc.genericArguments(genericParameter.position)
                case None => genericParameter
            }
        }
    }

    private def handleOwnerlessInvalidILCode(genericParameter: GenericParameter): TypeReference = {
        if (genericParameter.`type` == GenericParameterType.method && _typeDefinitionContext.exists(tdc => genericParameter.position < tdc.genericArguments.length)) {
            return _typeDefinitionContext.get.genericArguments(genericParameter.position)
        }
        genericParameter.module.map(_.typeSystem.`object`).getOrElse(throw OperationNotSupportedException())
    }

    private def isDummy() = {
        _typeDefinitionContext.isEmpty && _methodDefinitionContext.isEmpty
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