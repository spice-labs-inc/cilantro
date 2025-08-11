//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/ModuleKind.cs

package io.spicelabs.cilantro

enum ModuleKind {
  case dll, console, windows, netModule
}

enum MetadataKind {
  case ecma335, windowsMetadata, managedWindowsMetadata
}


enum TargetArchitecture(val value: Int) {
  case i386 extends TargetArchitecture(0x014c)
  case amd64 extends TargetArchitecture(0x8664)
  case ia64 extends TargetArchitecture(0x0200)
  case arm extends TargetArchitecture(0x01c0)
  case armv7 extends TargetArchitecture(0x01c4)
  case arm64 extends TargetArchitecture(0xaa64)
}

object TargetArchitecture {
  def fromOrdinalValue(value: Int) =
    TargetArchitecture.values.find(x => {x.value == value}) match
      case Some(result) => result
      case None => throw IllegalArgumentException(s"value $value not found in TargetArchitecture")
    
}

enum ModuleAttributesConstants(val value: Int) {
  case ilOnly extends ModuleAttributesConstants(1)
  case required32Bit extends ModuleAttributesConstants(2)
  case ilLibrary extends ModuleAttributesConstants(4)
  case strongNameSigned extends ModuleAttributesConstants(8)
  case preferred32Bit extends ModuleAttributesConstants(0x00020000)
}

class ModuleCharacteristicsConsts {
  val highEntropyVA = 0x0020
  val dynamicBase = 0x0040
  val noSEH = 0x0400
  val nxCompat = 0x0100
  val appContainer = 0x1000
  val terminalServerAware = 0x8000
}