package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer
import scala.util.boundary
import javax.naming.OperationNotSupportedException
import io.spicelabs.cilantro.AnyExtension.as
import scala.util.Try
import scala.util.Success

class MethodReference(initialName: String, _returnType: TypeReference, _declaring_type: Option[TypeReference] = None) extends MemberReference(initialName) with MethodSignature with GenericParameterProvider with GenericContext { // TODO
    var _parameters: Option[ParameterDefinitionCollection] = None

    private var _return_type:MethodReturnType = MethodReturnType(this)
    _return_type.returnType = _returnType
    this.token = Some(MetadataToken(TokenType.memberRef))
    _declaring_type.foreach(this.declaringType = _)

    def this() = {
        this("", TypeReference("", ""), None)

    }
    private var _has_this = false
    private var _explicit_this = false
    private var _calling_convention: MethodCallingConvention = MethodCallingConvention.default

    var _generic_parameters: Option[ArrayBuffer[GenericParameter]] = None

    def hasThis = _has_this
    def hasThis_=(value: Boolean) = _has_this = value

    def explicitThis = _explicit_this
    def explicitThis_=(value: Boolean) = _explicit_this = value

    def callingConvention = _calling_convention
    def callingConvention_=(value: MethodCallingConvention) = _calling_convention = value

    def hasParameters = {
        _parameters.exists(_.length > 0)

    }
    def parameters = {
        _parameters match {
            case Some(p) => p
            case None =>
                val p = ParameterDefinitionCollection(this)
                _parameters = Some(p)
                p
        }
    

    }
    override def `type` = {
        this.declaringType.flatMap(_.as[GenericInstanceType]) match {
            case Some(instance) => Some(instance.elementType)
            case None => declaringType
        }
    }
    override def method = Some(this)

    override def genericParameterType = GenericParameterType.method

    def hasGenericParameters = {
        _generic_parameters.exists(_.length > 0)

    }
    override def genericParameters = {
        _generic_parameters match {
            case Some(gp) => gp
            case None =>
                val gp = GenericParameterCollection(this)
                _generic_parameters = Some(gp)
                gp
        }

    }
    def returnType:TypeReference = {
        methodReturnType.returnType
    }
    def returnType_=(value: TypeReference) = {
        methodReturnType.returnType = value
    }
    def methodReturnType = _return_type
    def methodReturnType_=(value: MethodReturnType) = _return_type = value


    override def fullName = {
        val builder = StringBuilder()
        builder.append(returnType.fullName).append(" ")
        builder.append(declaringType.map(_.fullName).getOrElse("")).append("::").append(name)
        methodSignatureFullName(builder)
        builder.toString()
    }

    override def methodSignatureFullName(builder: StringBuilder): StringBuilder = {
        builder.append("(")
        val params = parameters
        for i <- params.indices do {
            if (i > 0) {
                builder.append(",")
            }
            val parameter = params(i)
            if (parameter.parameterType.isSentinel) {
                builder.append("...,")
            }
            builder.append(parameter.parameterType.fullName)
        }
        builder.append(")")

    }
    

    def isGenericInstance = false

    override def containsGenericParameter = {
        if (_generic_parameters.exists(_.length > 0)) {
            true
        }
        else if (declaringType.exists(_.containsGenericParameter)) {
            true
        }
        else if (_return_type.returnType.containsGenericParameter) {
            true
        }
        else {
            _parameters.exists(_.exists(_.parameterType.containsGenericParameter))
        }
    }

    def getElementMethod() : MethodReference = this

    override def resolveDefinition() = {
        this.resolve()

    }
    override def resolve(): MethodDefinition = {
        this.module match {
            case Some(module) => module.resolve(this)
            case None => throw OperationNotSupportedException()
        }
    
    }
    override def hashCode(): Int = {
        val hashCodeMultiplier = 486187739

        this.as[GenericInstanceMethod] match {
            case Some(genericInstanceMethod) =>
                val hashCode = genericInstanceMethod.genericArguments.foldLeft(genericInstanceMethod.elementMethod.hashCode())((hc, arg) => {
                    hc * hashCodeMultiplier + arg.hashCode()
                })
                return hashCode
            case None => ()
        }
        return declaringType.hashCode() * hashCodeMultiplier + name.hashCode()
    }

    override def equals(that: Any): Boolean = {
        that match {
            case other: MemberReference => equals(this, other)
            case _ => false
        }
    }
}

object MethodReference {
    var xComparisonStack: Option[ArrayBuffer[MethodReference]] = None
    var yComparisonStack: Option[ArrayBuffer[MethodReference]] = None

    def areEqual(x: MethodReference, y: MethodReference): Boolean = {
        if (x eq y) {
            return true

        }
        if (x.hasThis != y.hasThis) {
            return false
        
        }
        if (x.hasParameters != y.hasParameters) {
            return false
        
        }
        if (x.hasGenericParameters != y.hasGenericParameters) {
            return false
        
        }
        if (x.parameters.length != y.parameters.length) {
            return false

        }
        if (x.name != y.name) {
            return false
        
        }
        if (!x.declaringType.equals(y.declaringType)) {
            return false
        
        }
        val xGeneric = x.as[GenericInstanceMethod]
        val yGeneric = y.as[GenericInstanceMethod]

        (xGeneric, yGeneric) match {
            case (Some(xg), Some(yg)) =>
                if (xg.genericArguments.length != yg.genericArguments.length) {
                    return false

                }
                val both = xg.genericArguments.zip(yg.genericArguments)
                return both.exists((x, y) => !x.equals(y))
            case _ => ()
        }

        val xResolved = Try { x.resolve() }
        val yResolved = Try { y.resolve() }

        (xResolved, yResolved) match {
            case (Success(xr), Success(yr)) =>
                if (xr ne yr) {
                    return false
                }
            case _ =>
                if (xComparisonStack.isEmpty) {
                    xComparisonStack = Some(ArrayBuffer[MethodReference]())
                }
                if (yComparisonStack.isEmpty) {
                    yComparisonStack = Some(ArrayBuffer[MethodReference]())
                
                }
                val both = xComparisonStack.get.zip(yComparisonStack.get)
                if (both.exists((x1, y1) => (x1 eq x) && (y1 eq y ))) {
                    return true
                
                }
                xComparisonStack.foreach(_.addOne(x))

                val parametersEqual = try {
                    yComparisonStack.foreach(_.addOne(y))
                    try {
                        boundary {
                            for i <- 0 until x.parameters.length do {
                                if (!x.parameters(i).parameterType.equals(y.parameters(i).parameterType)) {
                                    boundary.break(false)
                                }
                            }
                            true
                        }
                    } finally {
                        yComparisonStack.foreach(_.remove(yComparisonStack.get.length - 1))
                    }
                } finally {
                    xComparisonStack.foreach(_.remove(xComparisonStack.get.length - 1))
                }
                if (!parametersEqual) {
                    return false
                }
        }

        return true
    }
}
