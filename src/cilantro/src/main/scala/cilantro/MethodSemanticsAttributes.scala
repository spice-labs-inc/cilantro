// MethodSemanticsAttributes — ECMA-335 II.22.28 MethodSemantics flags.
//
// Port of Mono.Cecil's MethodSemanticsAttributes: how a method relates to
// its property/event association (getter/setter/other, add/remove/fire).

package io.spicelabs.cilantro

enum MethodSemanticsAttributes(val value: Int) {
  case setter extends MethodSemanticsAttributes(0x0001)
  case getter extends MethodSemanticsAttributes(0x0002)
  case other extends MethodSemanticsAttributes(0x0004)
  case addOn extends MethodSemanticsAttributes(0x0008)
  case removeOn extends MethodSemanticsAttributes(0x0010)
  case fire extends MethodSemanticsAttributes(0x0020)
}
