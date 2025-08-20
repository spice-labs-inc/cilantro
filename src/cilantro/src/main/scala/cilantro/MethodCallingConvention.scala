//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/MethodCallingConvention.cs

package io.spicelabs.cilantro

enum MethodCallingConvention(val value: Byte) {
  case default extends MethodCallingConvention(0x0)
  case c extends MethodCallingConvention(0x1)
  case stdCall extends MethodCallingConvention(0x2)
  case thisCall extends MethodCallingConvention(0x3)
  case fastCall extends MethodCallingConvention(0x4)
  case varArg extends MethodCallingConvention(0x5)
  case unmanaged extends MethodCallingConvention(0x9)
  case generic extends MethodCallingConvention(0x10)
}
object MethodCallingConvention {
  def fromOrdinalValue(value: Int) =
    MethodCallingConvention.values.find(x => {x.value == value}) match
      case Some(result) => result
      case None => throw IllegalArgumentException(s"value $value not found in MethodCallingConvention")  

}
