// EhRangeTests — C3-03.
//
// Why this test exists:
//   Clause offsets become instruction references; the boundary cases
//   (try starting at offset 0, ranges running to the end of the body,
//   a handler containing only endfinally, identical and nested try
//   ranges, and the leave-out-of-a-nested-try shape) are where an
//   off-by-one shows up. This test pins the offset->index resolution
//   against hand-laid instruction vectors, including the oracle's null
//   semantics: an end offset equal to the code size resolves to None.
//
// Theory of the test:
//   A synthetic MethodBody (nops at known offsets) is built and raw
//   clauses are resolved with MethodBodyReader.resolveHandlers (package
//   visible for the test); every handler field is asserted as an index
//   into the instruction vector.

package io.spicelabs.cilantro.cil

import io.spicelabs.cilantro.TypeReference

class EhRangeTests extends munit.FunSuite {

  private def nop(offset: Int): Instruction = {
    new Instruction(offset, OpCodes.forCode(0x00).get, None)
  }

  // Body with nops at 0, 2, 5, 7, 9 (codeSize 10).
  private def sampleBody: MethodBody = {
    val instructions = Vector(0, 2, 5, 7, 9).map(nop)
    new MethodBody(instructions, 8, false, 10, None, Vector.empty)
  }

  private def resolve(body: MethodBody, clause: EhReader.RawExceptionClause): ExceptionHandler = {
    MethodBodyReader.resolveHandlers(Vector(clause), body).head
  }

  test("C3-03: try starting at offset 0 resolves to the first instruction") {
    val handler = resolve(sampleBody, EhReader.RawExceptionClause(
      ExceptionHandlerType.Catch, tryOffset = 0, tryLength = 2, handlerOffset = 2, handlerLength = 3,
      Some(TypeReference("System", "Exception")), None
    ))
    assertEquals(handler.tryStart, Some(0))
    assertEquals(handler.tryEnd, Some(1))
    assertEquals(handler.handlerStart, Some(1))
    assertEquals(handler.handlerEnd, Some(2))
  }

  test("C3-03: a try ending at the last instruction resolves tryEnd to the end") {
    // try [5, 10): starts at the instruction at 5, runs to the body end.
    val handler = resolve(sampleBody, EhReader.RawExceptionClause(
      ExceptionHandlerType.Finally, tryOffset = 5, tryLength = 5, handlerOffset = 0, handlerLength = 2,
      None, None
    ))
    assertEquals(handler.tryStart, Some(2))
    assertEquals(handler.tryEnd, None)
  }

  test("C3-03: a handler containing only endfinally resolves its own range") {
    // handler [7, 9): a single instruction at 7.
    val handler = resolve(sampleBody, EhReader.RawExceptionClause(
      ExceptionHandlerType.Finally, tryOffset = 0, tryLength = 2, handlerOffset = 7, handlerLength = 2,
      None, None
    ))
    assertEquals(handler.handlerStart, Some(3))
    assertEquals(handler.handlerEnd, Some(4))
  }

  test("C3-03: a handler running to the body end resolves handlerEnd to None") {
    val handler = resolve(sampleBody, EhReader.RawExceptionClause(
      ExceptionHandlerType.Finally, tryOffset = 0, tryLength = 2, handlerOffset = 9, handlerLength = 1,
      None, None
    ))
    assertEquals(handler.handlerStart, Some(4))
    assertEquals(handler.handlerEnd, None)
  }

  test("C3-03: identical try ranges (multi-catch) are legal") {
    val body = sampleBody
    val clauseA = EhReader.RawExceptionClause(
      ExceptionHandlerType.Catch, 0, 5, 5, 2, Some(TypeReference("System", "ArgumentException")), None
    )
    val clauseB = EhReader.RawExceptionClause(
      ExceptionHandlerType.Catch, 0, 5, 7, 2, Some(TypeReference("System", "Exception")), None
    )
    val handlers = MethodBodyReader.resolveHandlers(Vector(clauseA, clauseB), body)
    assertEquals(handlers.length, 2)
    assertEquals(handlers(0).tryStart, Some(0))
    assertEquals(handlers(1).tryStart, Some(0))
    assertEquals(handlers(0).tryEnd, Some(2))
    assertEquals(handlers(1).tryEnd, Some(2))
  }

  test("C3-03: nested try ranges are legal") {
    val outer = EhReader.RawExceptionClause(
      ExceptionHandlerType.Finally, 0, 10, 9, 1, None, None
    )
    val inner = EhReader.RawExceptionClause(
      ExceptionHandlerType.Catch, 2, 3, 5, 2, Some(TypeReference("System", "Exception")), None
    )
    val handlers = MethodBodyReader.resolveHandlers(Vector(outer, inner), sampleBody)
    assertEquals(handlers.length, 2)
    assertEquals(handlers(0).tryStart, Some(0))
    assertEquals(handlers(0).tryEnd, None)
    assertEquals(handlers(1).tryStart, Some(1))
  }

  test("C3-03: a filter offset resolves to the filter instruction") {
    val handler = resolve(sampleBody, EhReader.RawExceptionClause(
      ExceptionHandlerType.Filter, 0, 2, 5, 2, None, Some(7)
    ))
    assertEquals(handler.filterStart, Some(3))
  }

  test("C3-03: a filter offset that lands off-instruction resolves to None") {
    // mono ilasm emits catch clauses whose trailing token lands in the
    // filter slot; the oracle tolerates the miss as a null FilterStart.
    val handler = resolve(sampleBody, EhReader.RawExceptionClause(
      ExceptionHandlerType.Filter, 0, 2, 5, 2, None, Some(3)
    ))
    assertEquals(handler.filterStart, None)
  }
}
