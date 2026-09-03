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

  // Plan 2026_09_01 (H2): cheap declared-length accessor — the 4-byte
  // prefix only, no blob materialization. Data-backed resources report
  // their length directly; stream-backed resources cannot know a
  // length without reading (documented Failure).
  def resourceLength(): scala.util.Try[Int] = scala.util.Try {
    _data match {
      case Some(d) => d.length
      case None => _stream match {
        case Some(_) => throw OperationNotSupportedException("resourceLength is unavailable for stream-backed resources")
        case None => (_offset, _reader) match {
          case (Some(value), Some(r)) =>
            r.managedResourceLength(value) match {
              case scala.util.Success(len) => len
              case scala.util.Failure(e) => throw e
            }
          case _ => throw OperationNotSupportedException()
        }
      }
    }
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

  // Internal seam (plan 2026_09_02, phase B): the manifest offset of
  // an offset-backed resource, for the zero-copy managed-resource
  // payload accessor. Not part of the frozen public surface.
  private[cilantro] def resourceOffset: Option[Int] = _offset

    override def resourceType = ResourceType.embedded
}
