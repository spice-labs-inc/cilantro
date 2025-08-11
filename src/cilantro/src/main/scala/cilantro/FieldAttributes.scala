//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/FieldAttributes.cs

package io.spicelabs.cilantro

enum FieldAttributes(val value: Char) {
  case fieldAccessMask extends FieldAttributes(0x0007)
  case compilerControlled extends FieldAttributes(0x0000)
  case `private` extends FieldAttributes(0x0001)
  case famANDAssem extends FieldAttributes(0x0002)
  case assembly extends FieldAttributes(0x0003)
  case family extends FieldAttributes(0x0004)
  case famORAssem extends FieldAttributes(0x0005)
  case `public` extends FieldAttributes(0x0006)

  case static extends FieldAttributes(0x0010)
  case initOnly extends FieldAttributes(0x0020)
  case literal extends FieldAttributes(0x0040)
  case notSerialized extends FieldAttributes(0x0080)
  case specialName extends FieldAttributes(0x0200)

  case pInvokeImpl extends FieldAttributes(0x2000)

  case rtSpecialName extends FieldAttributes(0x0400)
  case hasFieldMarshal extends FieldAttributes(0x1000)
  case hasDefault extends FieldAttributes(0x8000)
  case hasFieldRVA extends FieldAttributes(0x0100)
}
