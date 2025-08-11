//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/GenericParameterAttributes.cs

package io.spicelabs.cilantro

enum GenericParameterAttributes(val value: Char) {
  case varianceMask extends GenericParameterAttributes(0x0003)
  case nonVariant extends GenericParameterAttributes(0x0000)
  case covariant extends GenericParameterAttributes(0x0001)
  case contravariant extends GenericParameterAttributes(0x0002)
  case specialConstraintMask extends GenericParameterAttributes(0x001c)
  case referenceTypeConstraint extends GenericParameterAttributes(0x0004)
  case notNullableValueTypeConstraint extends GenericParameterAttributes(0x0008)
  case defaultConstructorConstraint extends GenericParameterAttributes(0x0010)
  case allowByRefLikeConstraint extends GenericParameterAttributes(0x0020)
  
  def fromOrdinalValue(value: Char) =
    GenericParameterAttributes.values.find(x => {x.value == value}) match
      case Some(result) => result
      case None => throw IllegalArgumentException(s"value $value not found in GenericParameterAttributes")  

}
