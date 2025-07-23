//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.Metadata/TokenType.cs

package io.spicelabs.cilantro

import java.lang.*;

enum TokenType(val value: Int) {
  case module extends TokenType(0x00000000)
  case typeRef extends TokenType(0x01000000)
  case typeDef extends TokenType(0x02000000)
  case field extends TokenType(0x04000000)
  case method extends TokenType(0x06000000)
  case param extends TokenType(0x08000000)
  case interfaceImpl extends TokenType(0x09000000)
  case memberRef extends TokenType(0x0a000000)
  case customAttribute extends TokenType(0x0c000000)
  case permission extends TokenType(0x0e000000)
  case signature extends TokenType(0x11000000)
  case event extends TokenType(0x14000000)
  case property extends TokenType(0x17000000)
  case moduleRef extends TokenType(0x1a000000)
  case typeSpec extends TokenType(0x1b000000)
  case assembly extends TokenType(0x20000000)
  case assemblyRef extends TokenType(0x23000000)
  case file extends TokenType(0x26000000)
  case exportedType extends TokenType(0x27000000)
  case manifestResource extends TokenType(0x28000000)
  case genericParam extends TokenType(0x2a000000)
  case methodSpec extends TokenType(0x2b000000)
  case genericParamConstraint extends TokenType(0x2c000000)
  case document extends TokenType(0x30000000)
  case methodDebugInformation extends TokenType(0x31000000)
  case localScope extends TokenType(0x32000000)
  case localVariable extends TokenType(0x33000000)
  case localConstant extends TokenType(0x34000000)
  case importScope extends TokenType(0x35000000)
  case stateMachineMethod extends TokenType(0x36000000)
  case customDebugInformation extends TokenType(0x37000000)
  case string extends TokenType(0x70000000)
}

object TokenType {
  def fromOrdinalValue(value: Int) =
    TokenType.values.find(x => {x.value == value}) match
      case Some(result) => result
      case None => throw IllegalArgumentException(s"value $value not found in TokenType")  
}
