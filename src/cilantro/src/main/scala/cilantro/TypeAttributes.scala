//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/TypeAttributes.cs

package io.spicelabs.cilantro

enum TypeAttributes(val value: Int) {
  case visibilityMask extends TypeAttributes(0x00000007)
  case notPublic extends TypeAttributes(0x00000000)
  case public extends TypeAttributes(0x00000001)
  case nestedPublic extends TypeAttributes(0x00000002)
  case nestedPrivate extends TypeAttributes(0x00000003)
  case nestedFamily extends TypeAttributes(0x00000004)
  case nestedAssembly extends TypeAttributes(0x00000005)
  case nestedFamANDAssem extends TypeAttributes(0x00000006)
  case nestedFamORAssem extends TypeAttributes(0x00000007)
  
  case layoutMask extends TypeAttributes(0x00000018)
  case autoLayout extends TypeAttributes(0x00000000)
  case sequentialLayout extends TypeAttributes(0x00000008)
  case explicitLayout extends TypeAttributes(0x00000010)
  
  case classSemanticMask extends TypeAttributes(0x00000020)
  case `class` extends TypeAttributes(0x00000000)
  case interface extends TypeAttributes(0x00000020)

  case `abstract` extends TypeAttributes(0x00000080)
  case `sealed` extends TypeAttributes(0x00000100)
  case specialName extends TypeAttributes(0x00000400)

  case `import` extends TypeAttributes(0x00001000)
  case serializable extends TypeAttributes(0x00002000)
  case windowsRuntime extends TypeAttributes(0x00004000)

  case stringFormatMask extends TypeAttributes(0x00030000)
  case ansiClass extends TypeAttributes(0x00000000)
  case unicodeClass extends TypeAttributes(0x00010000)
  case autoClass extends TypeAttributes(0x00020000)
  
  case beforeFieldInit extends TypeAttributes(0x00100000)
  case rtSpecialName extends TypeAttributes(0x00000800)
  case hasSecurity extends TypeAttributes(0x00040000)
  case forwarder extends TypeAttributes(0x00200000)

}
