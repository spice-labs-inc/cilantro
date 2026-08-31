//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/CustomAttribute.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer
import javax.naming.OperationNotSupportedException

class CustomAttributeArgument(private val _type: TypeReference, private val _value: Any)
{
    def `type` = _type
    def value = _value
}

object CustomAttributeArgument {
    def apply(`type`: TypeReference, value: Any) =  {
        new CustomAttributeArgument(checkType(`type`), value)
    }
}

class CustomAttributeNamedArgument(private val _name: String, private val _argument: CustomAttributeArgument)
{
    def name = _name
    def argument = _argument
}

object CustomAttributeNamedArgument {
    def apply(name: String, argument: CustomAttributeArgument) = {
        new CustomAttributeNamedArgument(checkName(name), argument)
    }
}

trait CustomAttributeTrait {
    def attributeType: Option[TypeReference]
    def hasFields: Boolean
    def hasProperties: Boolean
    def hasConstructorArguments: Boolean
    def fields: ArrayBuffer[CustomAttributeNamedArgument]
    def properties: ArrayBuffer[CustomAttributeNamedArgument]
    def constructorArguments: ArrayBuffer[CustomAttributeArgument]
}

sealed class CustomAttribute(var _signature: Int, private var _constructor: MethodReference, var _blob:Array[Byte], private var _resolved: Boolean) extends CustomAttributeTrait {
    
    var _projection: Option[CustomAttributeValueProjection] = None
    var _arguments: Option[ArrayBuffer[CustomAttributeArgument]] = None
    var _fields: Option[ArrayBuffer[CustomAttributeNamedArgument]] = None
    var _properties: Option[ArrayBuffer[CustomAttributeNamedArgument]] = None

    def constructor = _constructor
    def constructor_(value: MethodReference) = _constructor = value

    def attributeType = constructor.declaringType

    def isResolved = _resolved

    def hasConstructorArguments = {
        resolve()
        _arguments.exists(!_.isEmpty)

    }
    def constructorArguments : ArrayBuffer[CustomAttributeArgument] = {
        resolve()

        _arguments match {
            case Some(args) => args
            case None =>
                val args = ArrayBuffer.empty[CustomAttributeArgument]
                _arguments = Some(args)
                args
        }

    }
    def hasFields = {
        resolve()
        _fields.exists(!_.isEmpty)

    }
    def fields =  {
        resolve()

        _fields match {
            case Some(f) => f
            case None =>
                val f = ArrayBuffer.empty[CustomAttributeNamedArgument]
                _fields = Some(f)
                f
        }

    }
    def hasProperties = {
        resolve()
        _properties.exists(!_.isEmpty)

    }
    def properties = {
        resolve()

        _properties match {
            case Some(p) => p
            case None =>
                val p = ArrayBuffer.empty[CustomAttributeNamedArgument]
                _properties = Some(p)
                p
        }
    
    }
    def hasImage = {
        constructor.hasImage
    
    }
    def module = constructor.module

    def this(signature: Int, constructor: MethodReference) = {
        this(signature, constructor, Array.emptyByteArray, false)

    }
    def this(constructor: MethodReference) = {
        this(0, constructor, Array.emptyByteArray, true)

    }
    def this(constructor: MethodReference, blob: Array[Byte]) = {
        this(0, constructor, blob, false)

    }
    def getBlob(): Array[Byte] = {
        if (_blob.length > 0) {
            _blob
        }
        if (!hasImage) {
            throw OperationNotSupportedException();
        }
        module.foreach { m =>
            _blob = m.read(_blob, this, (attribute, reader) => reader.readCustomAttributeBlob(attribute._signature))
        }
        _blob

    }
    private def resolve() : Unit = {
        if (_resolved || !hasImage) {
            return ()
        
        }
        module.foreach { m => m.syncRoot.synchronized {
            if (_resolved) {
                ()
            }
            else  {
                m.read(this, (attribute, reader) => {
                    try {
                        reader.readCustomAttributesSignature(attribute)
                        _resolved = true
                        ()
                    }
                    catch {
                        case r: ResolutionException =>
                            _arguments.foreach(_.clear())
                            _fields.foreach(_.clear())
                            _properties.foreach(_.clear())
                            _resolved = false
                            ()
                        case _ => ()

                    }
                })
            }
        } }

        ()
    }
}