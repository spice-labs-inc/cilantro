//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/AssemblyHashAlgorithm.cs

package io.spicelabs.cilantro

enum AssemblyHashAlgorithm(val value: Int) {
  case none extends AssemblyHashAlgorithm(0x0000)
  case md5 extends AssemblyHashAlgorithm(0x8003)
  case sha1 extends AssemblyHashAlgorithm(0x8004)
  case sha256 extends AssemblyHashAlgorithm(0x800c)
  case sha384 extends AssemblyHashAlgorithm(0x800d)
  case sha512 extends AssemblyHashAlgorithm(0x800e)
  case reserved extends AssemblyHashAlgorithm(0x8003) // md5
}

object  AssemblyHashAlgorithm {
  def fromOrdinalValue(value: Int) =
    AssemblyHashAlgorithm.values.find(x => {x.value == value}) match
      case Some(result) => result
      case None => throw IllegalArgumentException(s"value $value not found in AssemblyHashAlgorithm")  
}
