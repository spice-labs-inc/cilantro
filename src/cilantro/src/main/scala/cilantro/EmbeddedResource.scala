//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/EmbeddedResource.cs

package io.spicelabs.cilantro

import java.io.InputStream
import java.io.ByteArrayInputStream
import javax.naming.OperationNotSupportedException

class EmbeddedResource(name: String, attributes: Int) extends Resource(name, attributes) {
  private var _stream: InputStream = null
  private var _offset: Option[Int] = None
  private var _reader: MetadataReader = null
  private var _data: Array[Byte] = null

  def this(name: String, attributes: Int, data: Array[Byte]) = {
    this(name, attributes)
    _data = data
  }

  def this(name:String, attributes: Int, stream: InputStream) = {
    this(name, attributes)
    _stream = stream
  }

  def this(name: String, attributes: Int, offset: Int, reader: MetadataReader) = {
    this(name, attributes)
    _offset = Some(offset)
    _reader = reader
  }

  def getResourceStream(): InputStream = {
    if (_stream != null) {
        return _stream
    }

    if (_data != null) {
        return ByteArrayInputStream(_data)
    }

    _offset match {
        case Some(value) => ByteArrayInputStream(_reader.getManagedResource(value))
        case None => throw OperationNotSupportedException()
    }
  }

  def getResourceData(): Array[Byte] = {
    if (_stream != null) {
        return _stream.readAllBytes()
    }

    if (_data != null) {
        return _data
    }

    _offset match
        case Some(value) => _reader.getManagedResource(value)
        case None => throw OperationNotSupportedException()    
  }

    override def resourceType = ResourceType.embedded
}
