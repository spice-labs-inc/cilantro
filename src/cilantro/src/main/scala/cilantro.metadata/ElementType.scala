//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.Metadata/ElementType.cs

package io.spicelabs.cilantro.metadata
import io.spicelabs.cilantro.MetadataType


enum ElementType (val value: Byte) {
  case none extends ElementType(0x00)
  case void extends ElementType(0x01)
  case boolean extends ElementType(0x02)
  case char extends ElementType(0x03)
  case i1 extends ElementType(0x04)
  case u1 extends ElementType(0x05)
  case i2 extends ElementType(0x06)
  case u2 extends ElementType(0x07)
  case i4 extends ElementType(0x08)
  case u4 extends ElementType(0x09)
  case i8 extends ElementType(0x0a)
  case u8 extends ElementType(0x0b)
  case r4 extends ElementType(0x0c)
  case r8 extends ElementType(0x0d)
  case string extends ElementType(0x0e)
  case ptr extends ElementType(0x0f)   // Followed by <type> token
  case byRef extends ElementType(0x10)   // Followed by <type> token
  case valueType extends ElementType(0x11)   // Followed by <type> token
  case `class` extends ElementType(0x12)   // Followed by <type> token
  case `var` extends ElementType(0x13)   // Followed by generic parameter number
  case array extends ElementType(0x14)   // <type> <rank> <boundsCount> <bound1>  <loCount> <lo1>
  case genericInst extends ElementType(0x15)   // <type> <type-arg-count> <type-1> ... <type-n> */
  case typedByRef extends ElementType(0x16)
  case i extends ElementType(0x18)   // System.IntPtr
  case u extends ElementType(0x19)   // System.UIntPtr
  case fnPtr extends ElementType(0x1b)   // Followed by full method signature
  case `object` extends ElementType(0x1c)   // System.Object
  case szArray extends ElementType(0x1d)   // Single-dim array with 0 lower bound
  case mVar extends ElementType(0x1e)   // Followed by generic parameter number
  case cModReqD extends ElementType(0x1f)   // Required modifier : followed by a TypeDef or TypeRef token
  case cModOpt extends ElementType(0x20)   // Optional modifier : followed by a TypeDef or TypeRef token
  case internal extends ElementType(0x21)   // Implemented within the CLI
  case modifier extends ElementType(0x40)   // Or'd with following element types
  case sentinel extends ElementType(0x41)   // Sentinel for varargs method signature
  case pinned extends ElementType(0x45)   // Denotes a local variable that points at a pinned object

  // special undocumented constants
  case `type` extends ElementType(0x50)
  case boxed extends ElementType(0x51)
  case `enum` extends ElementType(0x55)

  def asMetadataType: MetadataType =
    MetadataType.fromOrdinalValue(value)
  
  def isPrimitive =
    this match
      case ElementType.boolean | ElementType.char | ElementType.i | ElementType.u
      | ElementType.i1 | ElementType.u1 | ElementType.i2 | ElementType.u2
      | ElementType.i4 | ElementType.u4 | ElementType.i8 | ElementType.u8
      | ElementType.r4 | ElementType.r8 => true
      case _ => false
    
}

object ElementType {
  def fromOrdinalValue(value: Byte) =
    ElementType.values.find(x => {x.value == value}) match
      case Some(result) => result
      case None => throw IllegalArgumentException(s"value $value not found in ElementType")

}