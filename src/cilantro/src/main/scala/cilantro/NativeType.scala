//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/NativeType.cs

package io.spicelabs.cilantro

enum NativeType(val value: Int) {
  case none extends NativeType(0x66)
  case boolean extends NativeType(0x02)
  case i1 extends NativeType(0x03)
  case u1 extends NativeType(0x04)
  case i2 extends NativeType(0x05)
  case u2 extends NativeType(0x06)
  case i4 extends NativeType(0x07)
  case u4 extends NativeType(0x08)
  case i8 extends NativeType(0x09)
  case u8 extends NativeType(0x0a)
  case r4 extends NativeType(0x0b)
  case r8 extends NativeType(0x0c)
  case lpStr extends NativeType(0x14)
  case int extends NativeType(0x1f)
  case uint extends NativeType(0x20)
  case func extends NativeType(0x26)
  case array extends NativeType(0x2a)
  case currency extends NativeType(0x0f)
  case bStr extends NativeType(0x13)
  case lpWStr extends NativeType(0x15)
  case lpTStr extends NativeType(0x16)
  case fixedSysString extends NativeType(0x17)
  case iUnknown extends NativeType(0x19)
  case iDispatch extends NativeType(0x1a)
  case struct extends NativeType(0x1b)
  case intF extends NativeType(0x1c)
  case safeArray extends NativeType(0x1d)
  case fixedArray extends NativeType(0x1e)
  case byValStr extends NativeType(0x22)
  case ansiBStr extends NativeType(0x23)
  case tBStr extends NativeType(0x24)
  case variantBool extends NativeType(0x25)
  case asAny extends NativeType(0x28)
  case lpStruct extends NativeType(0x2b)
  case customMarshaler extends NativeType(0x2c)
  case error extends NativeType(0x2d)
  case max extends NativeType(0x50)
}

object NativeType {
  def fromOrdinalValue(value: Int): NativeType =
    NativeType.values.find(x => {x.value == value}) match
      case Some(result) => result
      case None => throw IllegalArgumentException(s"value $value not found in NativeType")
  def fromOrdinalValue(value: Byte): NativeType =
    fromOrdinalValue(value.toInt & 0xff)
}