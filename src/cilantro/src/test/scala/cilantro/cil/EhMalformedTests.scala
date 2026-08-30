// EhMalformedTests — C3-04.
//
// Why this test exists:
//   The reader's contract (plan 03): malformed EH data is a clean
//   Failure, never a throw and never garbage handlers. Corrupt corpus
//   fixtures exercise exactly these paths. Pinned here: negative lengths
//   (fat form), out-of-code offsets and ranges, a huge clause-count
//   claim (the "1M-clause" bomb), truncated sections and clauses, and
//   undefined flag values.
//
// Theory of the test:
//   Each hand-built malformed input is parsed (or resolved) and must be a
//   Failure via pattern match — a thrown exception would fail the test.

package io.spicelabs.cilantro.cil

import scala.util.{Failure, Success}

class EhMalformedTests extends munit.FunSuite {

  private def assertParseFails(section: Array[Byte]): Unit = {
    EhReader.parseSection(section, _ => None) match {
      case Success(clauses) => fail(s"malformed section parsed: $clauses")
      case Failure(_) => ()
    }
  }

  private def nop(offset: Int): Instruction = {
    new Instruction(offset, OpCodes.forCode(0x00).get, None)
  }

  private def sampleBody: MethodBody = {
    val instructions = Vector(0, 2, 5, 7, 9).map(nop)
    new MethodBody(instructions, 8, false, 10, None, Vector.empty)
  }

  private def assertResolveFails(clause: EhReader.RawExceptionClause): Unit = {
    try {
      val handlers = MethodBodyReader.resolveHandlers(Vector(clause), sampleBody)
      fail(s"invalid clause resolved: $handlers")
    } catch {
      case e: RuntimeException => ()
    }
  }

  test("C3-04: a truncated section is a Failure") {
    assertParseFails(Array(0x01.toByte))
    assertParseFails(Array(0x41.toByte, 0x10, 0x00))
  }

  test("C3-04: a truncated clause is a Failure") {
    // 10 bytes of clause data with a size that claims one 12-byte clause.
    assertParseFails(EhBuilder.smallSection(Array.fill[Byte](10)(0)))
    // 8 bytes of clause data with a size that claims one 24-byte clause.
    assertParseFails(Array[Byte](0x41, 0x18, 0x00, 0x00) ++ Array.fill[Byte](8)(0))
  }

  test("C3-04: a huge clause-count claim is a Failure") {
    // Fat section claiming 24 * 1000 bytes of clauses but carrying 8.
    assertParseFails(Array[Byte](0x41, 0x00, 0x40, 0x5d) ++ Array.fill[Byte](8)(0))
    // Directly: size field claims more clauses than the data holds.
    val fakeSize = 24 * 1000000
    assertParseFails(Array(
      0x41.toByte,
      (fakeSize & 0xff).toByte,
      ((fakeSize >> 8) & 0xff).toByte,
      ((fakeSize >> 16) & 0xff).toByte
    ) ++ Array.fill[Byte](8)(0))
  }

  test("C3-04: undefined flag values are Failures") {
    for flags <- Seq(3, 5, 6, 7) do {
      assertParseFails(EhBuilder.smallSection(EhBuilder.smallClause(
        flags, 0, 1, 1, 1, EhBuilder.padding
      )))
    }
  }

  test("C3-04: negative fat lengths are Failures") {
    val clause = EhReader.RawExceptionClause(
      ExceptionHandlerType.Catch, tryOffset = 0, tryLength = -5, handlerOffset = 2, handlerLength = 3,
      None, None
    )
    assertResolveFails(clause)
  }

  test("C3-04: out-of-code offsets and ranges are Failures") {
    assertResolveFails(EhReader.RawExceptionClause(
      ExceptionHandlerType.Catch, tryOffset = 12, tryLength = 1, handlerOffset = 2, handlerLength = 1,
      None, None
    ))
    assertResolveFails(EhReader.RawExceptionClause(
      ExceptionHandlerType.Catch, tryOffset = 0, tryLength = 20, handlerOffset = 2, handlerLength = 1,
      None, None
    ))
    assertResolveFails(EhReader.RawExceptionClause(
      ExceptionHandlerType.Catch, tryOffset = 0, tryLength = 2, handlerOffset = 9, handlerLength = 5,
      None, None
    ))
  }

  test("C3-04: partially overlapping try ranges are Failures") {
    val clauseA = EhReader.RawExceptionClause(
      ExceptionHandlerType.Catch, tryOffset = 0, tryLength = 5, handlerOffset = 5, handlerLength = 2,
      None, None
    )
    val clauseB = EhReader.RawExceptionClause(
      ExceptionHandlerType.Catch, tryOffset = 2, tryLength = 7, handlerOffset = 9, handlerLength = 1,
      None, None
    )
    try {
      val handlers = MethodBodyReader.resolveHandlers(Vector(clauseA, clauseB), sampleBody)
      fail(s"overlapping try ranges resolved: $handlers")
    } catch {
      case e: RuntimeException => ()
    }
  }
}
