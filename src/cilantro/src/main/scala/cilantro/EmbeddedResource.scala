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
  private var _stream: Option[InputStream] = None
  private var _offset: Option[Int] = None
  private var _reader: Option[MetadataReader] = None
  private var _data: Option[Array[Byte]] = None

  def this(name: String, attributes: Int, data: Array[Byte]) = {
    this(name, attributes)
    _data = Some(data)
  }

  def this(name:String, attributes: Int, stream: InputStream) = {
    this(name, attributes)
    _stream = Some(stream)
  }

  def this(name: String, attributes: Int, offset: Int, reader: MetadataReader) = {
    this(name, attributes)
    _offset = Some(offset)
    _reader = Some(reader)
  }

  def getResourceStream(): InputStream = {
    _stream match {
        case Some(s) => s
        case None => _data match {
            case Some(d) => ByteArrayInputStream(d)
            case None => (_offset, _reader) match {
                case (Some(value), Some(r)) => ByteArrayInputStream(r.getManagedResource(value))
                case _ => throw OperationNotSupportedException()
            }
        }
    }
  }

  // Plan 13 (C5-02): the Try-shaped accessor Goat Rodeo will consume.
  def resourceData(): scala.util.Try[Array[Byte]] = scala.util.Try {
    getResourceData()
  }

  def getResourceData(): Array[Byte] = {
    _stream match {
        case Some(s) => s.readAllBytes()
        case None => _data match {
            case Some(d) => d
            case None => (_offset, _reader) match {
                case (Some(value), Some(r)) => r.getManagedResource(value)
                case _ => throw OperationNotSupportedException()
            }
        }
    }
  }

    override def resourceType = ResourceType.embedded
}
