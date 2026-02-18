//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/LinkedResource.cs

package io.spicelabs.cilantro

class LinkedResource(name: String, flags: Int, private var _file: String = null) extends Resource(name, flags) {
  var _hash: Array[Byte] = Array.emptyByteArray

  def hash = _hash

  def file = _file
  def file_=(value: String) = _file = value

  override def resourceType: ResourceType = ResourceType.linked
}
