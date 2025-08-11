//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/Treatments.cs

package io.spicelabs.cilantro

enum TypeDefinitionTreatment(value: Int) {
  case none extends TypeDefinitionTreatment(0)
  case kindMask extends TypeDefinitionTreatment(0xf)
  case normalType extends TypeDefinitionTreatment(0x1)
  case normalAttribute extends TypeDefinitionTreatment(0x2)
  case unmangleWindowsRuntimeName extends TypeDefinitionTreatment(0x3)
  case prefixWindowsRuntimeName extends TypeDefinitionTreatment(0x4)
  case redirectToClrType extends TypeDefinitionTreatment(0x5)
  case redirectToClrAttribute extends TypeDefinitionTreatment(0x6)
  case redirectImplementedMethods extends TypeDefinitionTreatment(0x7)
  case `abstract` extends TypeDefinitionTreatment(0x10)
  case internal extends TypeDefinitionTreatment(0x20)
}

enum TypeReferenceTreatment(value: Int) {
  case none extends TypeReferenceTreatment(0)
  case systemDelegate extends TypeReferenceTreatment(0x1)
  case systemAttribute extends TypeReferenceTreatment(0x2)
  case userProjectionInfo extends TypeReferenceTreatment(0x3)
}

enum MethodDefinitionTreatment(value: Int) {
  case none extends MethodDefinitionTreatment(0)
  case `abstract` extends MethodDefinitionTreatment(0x2)
  case `private` extends MethodDefinitionTreatment(0x4)
  case `public` extends MethodDefinitionTreatment(0x8)
  case runtime extends MethodDefinitionTreatment(0x10)
  case internalCall extends MethodDefinitionTreatment(0x20)
}

enum FieldDefinitionTreatment(value: Int) {
  case none extends FieldDefinitionTreatment(0)
  case `public` extends FieldDefinitionTreatment(1)
}

enum CustomAttributeValueTreatment(value: Int) {
  case none extends CustomAttributeValueTreatment(0)
  case allowSingle extends CustomAttributeValueTreatment(1)
  case allowMultiple extends CustomAttributeValueTreatment(2)
  case versionAttribute extends CustomAttributeValueTreatment(3)
  case deprecatedAttribute extends CustomAttributeValueTreatment(4)
}
