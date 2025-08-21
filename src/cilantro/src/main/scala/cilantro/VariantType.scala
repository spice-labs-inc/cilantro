//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/VariantType.cs

package io.spicelabs.cilantro

enum VariantType(val value: Int) {
  case none extends VariantType(0)
  case i2 extends VariantType(2)
  case i4 extends VariantType(3)
  case r4 extends VariantType(4)
  case r8 extends VariantType(5)
  case cy extends VariantType(6)
  case date extends VariantType(7)
  case bStr extends VariantType(8)
  case dispatch extends VariantType(9)
  case error extends VariantType(10)
  case bool extends VariantType(11)
  case variant extends VariantType(12)
  case unknown extends VariantType(13)
  case decimal extends VariantType(14)
  case i1 extends VariantType(16)
  case ui1 extends VariantType(17)
  case ui2 extends VariantType(18)
  case ui4 extends VariantType(19)
  case i8 extends VariantType(20)
  case ui8 extends VariantType(21)
  case int extends VariantType(22)
  case uint extends VariantType(23)
}

object VariantType {
  def fromOrdinalValue(value: Int): VariantType =
    VariantType.values.find(x => {x.value == value}) match
      case Some(result) => result
      case None => throw IllegalArgumentException(s"value $value not found in VariantType")
  def fromOrdinalValue(value: Byte): VariantType =
    fromOrdinalValue(value.toInt & 0xff)
}