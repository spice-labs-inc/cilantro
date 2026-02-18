//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/MethodImplAttributes.cs

package io.spicelabs.cilantro

enum MethodImplAttributes(val value: Int) {
  case codeTypeMask extends MethodImplAttributes(0x0003)
  case il extends MethodImplAttributes(0x0000)
  case native extends MethodImplAttributes(0x0001)
  case optil extends MethodImplAttributes(0x0002)
  case runtime extends MethodImplAttributes(0x0003)
  case managedMask extends MethodImplAttributes(0x0004)
  case unmanaged extends MethodImplAttributes(0x0004)
  case managed extends MethodImplAttributes(0)
  case forwardRef extends MethodImplAttributes(0x0010)
  case preserveSig extends MethodImplAttributes(0x0080)
  case internalCall extends MethodImplAttributes(0x1000)
  case synchronized extends MethodImplAttributes(0x0020)
  case noOptimization extends MethodImplAttributes(0x0040)
  case noInlining extends MethodImplAttributes(0x0008)
  case aggressiveInlining extends MethodImplAttributes(0x0100)
  case aggressiveOptimization extends MethodImplAttributes(0x0200)
}
