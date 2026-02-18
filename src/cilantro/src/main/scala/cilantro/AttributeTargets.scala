//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.


package io.spicelabs.cilantro

enum AttributeTargets(val value: Char) {
  case assembly extends AttributeTargets(1)
  case module extends AttributeTargets(2)
  case `class` extends AttributeTargets(4)
  case struct extends AttributeTargets(8)
  case `enum` extends AttributeTargets(16)
  case constructor extends AttributeTargets(32)
  case method extends AttributeTargets(64)
  case property extends AttributeTargets(128)
  case field extends AttributeTargets(256)
  case event extends AttributeTargets(512)
  case interface extends AttributeTargets(1024)
  case parameter extends AttributeTargets(2048)
  case delegate extends AttributeTargets(4096)
  case returnValue extends AttributeTargets(8192)
  case genericParameter extends AttributeTargets(16384)
  case all extends AttributeTargets(32767)
}
