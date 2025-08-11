package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer
import javax.naming.OperationNotSupportedException

class MethodReference extends MemberReference with GenericParameterProvider with GenericContext { // TODO
    private var _module: ModuleDefinition = null;

    var _parameters: ParameterDefinitionCollection = null

    override def hasImage = false

    override def module = _module

    private var _has_this = false
    private var _explicit_this = false

    var _generic_parameters: ArrayBuffer[GenericParameter] = null

    def hasThis = _has_this
    def hasThis_=(value: Boolean) = _has_this = value

    def explicitThis = _explicit_this
    def explicitThis_=(value: Boolean) = _explicit_this = value

    // TODO
    // def callingConvention = ...
    // def callingContention_=(value: MethodCallingConvention) = ...

    def hasParameters =
        _parameters != null && _parameters.length > 0

    def parameters =
        if (_parameters == null)
            _parameters = ParameterDefinitionCollection(/* this */) // TODO
        _parameters
    

    override def `type` =
        val declaring_type = this.declaringType
        declaring_type // TODO
        // val instance = declaring_type.asInstanceOf[GenericInstanceType]
        // if (instance != null)
        //     instance.elementType
        // else
            // declaringType

    override def method = this

    override def genericParameterType = GenericParameterType.method

    def hasGenericParameters =
        _generic_parameters != null && _generic_parameters.length > 0

    override def genericParameters =
        if (_generic_parameters == null)
            _generic_parameters = ArrayBuffer[GenericParameter]() // FIXME wrong type
        _generic_parameters


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
