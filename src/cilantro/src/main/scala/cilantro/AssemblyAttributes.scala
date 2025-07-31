//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/AssemblyFlags.cs

package io.spicelabs.cilantro

enum AssemblyAttributes(val value: Int) {
  case publicKey extends AssemblyAttributes(0x0001)
  case sideBySideCompatible extends AssemblyAttributes(0x0000)
  case retargetable extends AssemblyAttributes(0x0100)
  case windowsRuntime extends AssemblyAttributes(0x0200)
  case disableJITCompileOptimizer extends AssemblyAttributes(0x4000)
  case enableJITCompileTracking extends AssemblyAttributes(0x8000)
}
