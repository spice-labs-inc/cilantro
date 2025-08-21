package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer
import javax.naming.OperationNotSupportedException
import io.spicelabs.cilantro.AnyExtension.as

class MethodReference(name: String, _returnType: TypeReference, _declaring_type: TypeReference = null) extends MemberReference(name) with MethodSignature with GenericParameterProvider with GenericContext { // TODO
    var _parameters: ParameterDefinitionCollection = null

    private var _return_type:MethodReturnType = MethodReturnType(this)
    _return_type.returnType = _returnType
    this.token = MetadataToken(TokenType.memberRef)
    this.declaringType = _declaring_type

    def this() =
        this(null, null)

    private var _has_this = false
    private var _explicit_this = false
    private var _calling_convention: MethodCallingConvention = MethodCallingConvention.default

    var _generic_parameters: ArrayBuffer[GenericParameter] = null

    def hasThis = _has_this
    def hasThis_=(value: Boolean) = _has_this = value

    def explicitThis = _explicit_this
    def explicitThis_=(value: Boolean) = _explicit_this = value

    def callingConvention = _calling_convention
    def callingConvention_=(value: MethodCallingConvention) = _calling_convention = value

    def hasParameters =
        _parameters != null && _parameters.length > 0

    def parameters =
        if (_parameters == null)
            _parameters = ParameterDefinitionCollection(this)
        _parameters
    

    override def `type` =
        val declaring_type = this.declaringType
        val instance = declaring_type.as[GenericInstanceType]
        if (instance != null)
            instance.elementType
        else
            declaringType

    override def method = this

    override def genericParameterType = GenericParameterType.method

    def hasGenericParameters =
        _generic_parameters != null && _generic_parameters.length > 0

    override def genericParameters =
        if (_generic_parameters == null)
            _generic_parameters = ArrayBuffer[GenericParameter]() // FIXME wrong type
        _generic_parameters

    def returnType:TypeReference =
        val return_type = methodReturnType
        if return_type != null then return_type.returnType else null
    def returnType_=(value: TypeReference) =
        val return_type = methodReturnType
        if (return_type != null)
            return_type.returnType = value
    
    def methodReturnType = _return_type
    def methodReturnType_=(value: MethodReturnType) = _return_type = value


    override def fullName = // FIXME
        "return type" + " " + memberFullName() + "method signature full name"
    

    def isGenericInstance = false

    override def containsGenericParameter = false // TODO

    def getElementMethod() : MethodReference = this

    override def resolveDefinition() =
        this.resolve()

    override def resolve() =
        var module = this.module
        if (module == null)
            throw OperationNotSupportedException()
        module.resolve(this)
}
