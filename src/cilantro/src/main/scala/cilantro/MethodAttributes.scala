//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/MethodAttributes.cs

package io.spicelabs.cilantro

enum MethodAttributes(val value: Char) {
  case memberAccessMask extends MethodAttributes(0x0007)
  case compilerControlled extends MethodAttributes(0x0000)
  case `private` extends MethodAttributes(0x0001)
  case famANDAssem extends MethodAttributes(0x0002)
  case assembly extends MethodAttributes(0x0003)
  case family extends MethodAttributes(0x0004)
  case famORAssem extends MethodAttributes(0x0005)
  case `public` extends MethodAttributes(0x0006)

  case static extends MethodAttributes(0x0010)
  case `final` extends MethodAttributes(0x0020)
  case `virtual` extends MethodAttributes(0x0040)
  case hideBySig extends MethodAttributes(0x0080)

  case vtableLayoutMask extends MethodAttributes(0x0100)
  case reusesSlot extends MethodAttributes(0x0000)
  case newSlot extends MethodAttributes(0x0100)

  case checkAccessOnOverride extends MethodAttributes(0x0200)
  case `abstract` extends MethodAttributes(0x0400)
  case specialName extends MethodAttributes(0x0800)

  case pInvokeImpl extends MethodAttributes(0x2000)
  case unmanagedExport extends MethodAttributes(0x0008)

  case rtSpecialName extends MethodAttributes(0x1000)
  case hasSecurity extends MethodAttributes(0x4000)
  case requireSecObject extends MethodAttributes(0x8000)
}
