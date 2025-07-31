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

import scala.collection.mutable.ListBuffer
import javax.naming.OperationNotSupportedException

class CustomAttributeArgument(private val _type: TypeReference, private val _value: Object)
{
    def `type` = _type
    def value = _value
}

object CustomAttributeArgument {
    def apply(`type`: TypeReference, value: Object) = 
        new CustomAttributeArgument(checkType(`type`), value)
}

class CustomAttributeNamedArgument(private val _name: String, private val _argument: CustomAttributeArgument)
{
    def name = _name
    def argument = _argument
}

object CustomAttributeNamedArgument {
    def apply(name: String, argument: CustomAttributeArgument) =
        new CustomAttributeNamedArgument(checkName(name), argument)
}

trait CustomAttributeTrait {
    def attributeType: TypeReference
    def hasFields: Boolean
    def hasProperties: Boolean
    def hasConstructorArguments: Boolean
    def fields: ListBuffer[CustomAttributeNamedArgument]
    def properties: ListBuffer[CustomAttributeNamedArgument]
    def constructorArguments: ListBuffer[CustomAttributeArgument]
}

sealed class CustomAttribute(private var _signature: Int, private var _constructor: MethodReference, var _blob:Array[Byte], var _resolved: Boolean) extends CustomAttributeTrait {
    
    private var arguments: ListBuffer[CustomAttributeArgument] = null
    private var _fields: ListBuffer[CustomAttributeNamedArgument] = null
    private var _properties: ListBuffer[CustomAttributeNamedArgument] = null

    def constructor = _constructor
    def constructor_(value: MethodReference) = _constructor = value

    def attributeType = null // TODO constructor.declaringType

    def isResolved = _resolved

    def hasConstructorArguments =
        resolve()
        arguments != null && !arguments.isEmpty

    def constructorArguments : ListBuffer[CustomAttributeArgument] =
        resolve()

        if (arguments == null)
            arguments = ListBuffer.empty[CustomAttributeArgument]

        arguments

    def hasFields =
        resolve()
        _fields != null && !fields.isEmpty

    def fields = 
        resolve()

        if (_fields == null)
            _fields = ListBuffer.empty[CustomAttributeNamedArgument]

        _fields

    def hasProperties =
        resolve()
        _properties != null && !_properties.isEmpty

    def properties =
        resolve()

        if (_properties == null)
            _properties = ListBuffer.empty[CustomAttributeNamedArgument]
        
        _properties
    
    def hasImage =
        constructor != null && constructor.hasImage
    
    def module = constructor.module

    def this(signature: Int, constructor: MethodReference) =
        this(signature, constructor, null, false)

    def this(constructor: MethodReference) =
        this(0, constructor, null, true)

    def this(constructor: MethodReference, blob: Array[Byte]) =
        this(0, constructor, blob, false)

    def getBlob(): Array[Byte] =
        if (_blob != null)
            _blob
        if (!hasImage)
            throw OperationNotSupportedException();
        _blob = module.read(_blob, this, (attribute, reader) => reader.readCustomAttributeBlob(attribute._signature))
        _blob

    private def resolve() : Unit =
        if (_resolved || !hasImage)
            ()
        
        module.syncRoot.synchronized {
            if (_resolved)
                ()
            module.read(this, (attribute, reader) => {
                try
                    reader.readCustomAttributesSignature(attribute)
                    _resolved = true
                    ()
                catch
                    case r: ResolutionException =>
                        if (arguments != null)
                            arguments.clear()
                        if (_fields != null)
                            _fields.clear()
                        if (_properties != null)
                            _properties.clear()
                        _resolved = false
                        ()
                    case _ => ()

            })
        }

        ()
}