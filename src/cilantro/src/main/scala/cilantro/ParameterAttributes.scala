//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/ParameterAttributes.cs

package io.spicelabs.cilantro

enum ParameterAttributes(val value: Char) {
  case none extends ParameterAttributes(0x0000)
  case in extends ParameterAttributes(0x0001)
  case out extends ParameterAttributes(0x0002)
  case lcid extends ParameterAttributes(0x0004)
  case retval extends ParameterAttributes(0x0008)
  case optional extends ParameterAttributes(0x0010)
  case hasDefault extends ParameterAttributes(0x1000)
  case hasFieldMarshal extends ParameterAttributes(0x2000)
  case unused extends ParameterAttributes(0xcfe0)
}
