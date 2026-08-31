// InlineOperandTests — C2-05.
//
// Why this test exists:
//   Each inline operand family has its own width, signedness and
//   resolution path; a single wrong case (e.g. reading an InlineI as a
//   byte, or sign-extending a token) shifts the rest of the stream. This
//   test pins tokens (with the resolver round-trip), the embedded calli
//   signature blob index, inline i4/i8/r4/r8 and the short-form
//   boundaries for locals, arguments and short integers.
//
// Theory of the test:
//   Bodies are hand-laid per operand family; decode-time resolvers return
//   pinned values so both the raw and the resolved operand are asserted.
//   Short-form sign extension is pinned with 0x80 (-128) and the
//   ldarg.s/ldloc.s boundaries with the maximum one-byte index (255).

package io.spicelabs.cilantro.cil

import scala.util.Success
import io.spicelabs.cilantro.MetadataToken

class InlineOperandTests extends munit.FunSuite {

  private def decode(
      insns: Array[Byte],
      resolveString: Int => Option[String] = _ => None,
      resolveToken: MetadataToken => Option[String] = _ => None
  ): MethodBody = {
    CodeReader.readBody(BodyBuilder.tiny(insns), resolveString, resolveToken) match {
      case Success(body) => body
      case scala.util.Failure(t) => fail(s"decode failed: $t")
    }
  }

  test("C2-05: ldtoken resolves through the token resolver") {
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(Array(0xd0.toByte), BodyBuilder.i4(0x02000007))), // ldtoken <type>
      Array(0x2a.toByte)
    ))
    val body = decode(insns, resolveToken = t => Some("Ns.Type"))
    body.instructions(0).operand match {
      case Some(Operand.Tok(token, resolved)) =>
        assertEquals(token.token, 0x02000007)
        assertEquals(token.tokenType.toString, "typeDef")
        assertEquals(resolved, Some("Ns.Type"))
      case other => fail(s"expected Tok, got $other")
    }
  }

  test("C2-05: an unresolved token keeps the raw token and None") {
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(Array(0xd0.toByte), BodyBuilder.i4(0x1a000003))),
      Array(0x2a.toByte)
    ))
    val body = decode(insns)
    body.instructions(0).operand match {
      case Some(Operand.Tok(token, resolved)) =>
        assertEquals(token.token, 0x1a000003)
        assertEquals(resolved, None)
      case other => fail(s"expected Tok, got $other")
    }
  }

  test("C2-05: ldstr resolves through the string resolver") {
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(Array(0x72.toByte), BodyBuilder.i4(0x70000005))), // ldstr 0x70000005
      Array(0x2a.toByte)
    ))
    val body = decode(insns, resolveString = t => Some("hello corpus"))
    body.instructions(0).operand match {
      case Some(Operand.StringLit(value)) => assertEquals(value, "hello corpus")
      case other => fail(s"expected StringLit, got $other")
    }
  }

  test("C2-05: calli carries the embedded signature blob index") {
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(Array(0x29.toByte), BodyBuilder.i4(0x2b))) // calli sig blob 0x2b
    ))
    val body = decode(insns)
    body.instructions(0).operand match {
      case Some(Operand.Sig(blobIndex)) => assertEquals(blobIndex, 0x2b)
      case other => fail(s"expected Sig, got $other")
    }
  }

  test("C2-05: inline i4/i8/r4/r8 widths and values") {
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(Array(0x20.toByte), BodyBuilder.i4(0x12345678))), // ldc.i4
      BodyBuilder.concat(Seq(Array(0x21.toByte), BodyBuilder.i8(0x1122334455667788L))), // ldc.i8
      BodyBuilder.concat(Seq(Array(0x22.toByte), BodyBuilder.f4(1.5f))), // ldc.r4
      BodyBuilder.concat(Seq(Array(0x23.toByte), BodyBuilder.f8(2.5d))), // ldc.r8
      Array(0x2a.toByte)
    ))
    val body = decode(insns)
    assertEquals(body.instructions(0).operand, Some(Operand.I4(0x12345678)))
    assertEquals(body.instructions(1).operand, Some(Operand.I8(0x1122334455667788L)))
    assertEquals(body.instructions(2).operand, Some(Operand.R4(1.5f)))
    assertEquals(body.instructions(3).operand, Some(Operand.R8(2.5d)))
  }

  test("C2-05: ldc.i4.s sign-extends (0x80 is -128, 0x7f is 127)") {
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(Array(0x1f.toByte), BodyBuilder.i1(0x80.toInt))),
      BodyBuilder.concat(Seq(Array(0x1f.toByte), BodyBuilder.i1(0x7f))),
      Array(0x2a.toByte)
    ))
    val body = decode(insns)
    assertEquals(body.instructions(0).operand, Some(Operand.I4(-128)))
    assertEquals(body.instructions(1).operand, Some(Operand.I4(127)))
  }

  test("C2-05: argument and local short forms hit their boundaries") {
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(Array(0x0e.toByte), BodyBuilder.i1(255))), // ldarg.s 255
      BodyBuilder.concat(Seq(Array(0x11.toByte), BodyBuilder.i1(255))), // ldloc.s 255
      BodyBuilder.concat(Seq(Array(0x10.toByte), BodyBuilder.i1(0))),   // starg.s 0
      BodyBuilder.concat(Seq(Array(0x13.toByte), BodyBuilder.i1(0))),   // stloc.s 0
      Array(0x2a.toByte)
    ))
    val body = decode(insns)
    assertEquals(body.instructions(0).operand, Some(Operand.Arg(255)))
    assertEquals(body.instructions(1).operand, Some(Operand.Var(255)))
    assertEquals(body.instructions(2).operand, Some(Operand.Arg(0)))
    assertEquals(body.instructions(3).operand, Some(Operand.Var(0)))
  }

  test("C2-05: long argument and local forms read two-byte indices") {
    val insns = BodyBuilder.concat(Seq(
      BodyBuilder.concat(Seq(Array(0xc5.toByte), BodyBuilder.i2(0x1234))), // ldarg 0x1234
      BodyBuilder.concat(Seq(Array(0xc8.toByte), BodyBuilder.i2(0xabcd))), // ldloc 0xabcd
      Array(0x2a.toByte)
    ))
    val body = decode(insns)
    assertEquals(body.instructions(0).operand, Some(Operand.Arg(0x1234)))
    assertEquals(body.instructions(1).operand, Some(Operand.Var(0xabcd)))
  }
}
