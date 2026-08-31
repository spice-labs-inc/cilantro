// EhFormatTests — C3-01.
//
// Why this test exists:
//   The two EH clause encodings differ in every field width, and the
//   classic off-by-source bug is which base the offsets use. The oracle
//   (Mono.Cecil 0.11.6, recovered from its source and pinned by the ilasm
//   fixture goldens) reads BOTH formats with offsets as IL offsets
//   relative to the start of the IL stream, applying no section-relative
//   base (see ADR-0005). This test pins the small-vs-fat field layouts,
//   the identical offset interpretation, the flag decoding (catch /
//   filter / finally / fault, masked with 0x7) and the per-type extra
//   field (type token for catch, i32 offset for filter, skipped otherwise).
//
// Theory of the test:
//   Hand-built sections are parsed with EhReader.parseSection and the raw
//   clauses are compared field by field. The same clause values encoded
//   in both formats must yield identical clauses.

package io.spicelabs.cilantro.cil

import scala.util.Success
import io.spicelabs.cilantro.TypeReference

class EhFormatTests extends munit.FunSuite {

  private def parse(section: Array[Byte]): Vector[EhReader.RawExceptionClause] = {
    EhReader.parseSection(section, _ => None) match {
      case Success(clauses) => clauses
      case scala.util.Failure(t) => fail(s"parse failed: $t")
    }
  }

  test("C3-01: a small catch clause decodes field by field") {
    val section = EhBuilder.smallSection(EhBuilder.smallClause(
      flags = 0,
      tryOff = 5,
      tryLen = 9,
      handOff = 14,
      handLen = 4,
      extra = EhBuilder.catchToken(3)
    ))
    val clauses = parse(section)
    assertEquals(clauses.length, 1)
    val clause = clauses.head
    assertEquals(clause.handlerType, ExceptionHandlerType.Catch)
    assertEquals(clause.tryOffset, 5)
    assertEquals(clause.tryLength, 9)
    assertEquals(clause.handlerOffset, 14)
    assertEquals(clause.handlerLength, 4)
    assertEquals(clause.filterOffset, None)
    assertEquals(clause.catchType, None) // resolver returned None in this test
  }

  test("C3-01: a fat clause with values beyond the small range decodes") {
    val section = EhBuilder.fatSection(EhBuilder.fatClause(
      flags = 0,
      tryOff = 0x12345,
      tryLen = 0x200,
      handOff = 0x12545,
      handLen = 0x80,
      extra = EhBuilder.catchToken(7)
    ))
    val clause = parse(section).head
    assertEquals(clause.handlerType, ExceptionHandlerType.Catch)
    assertEquals(clause.tryOffset, 0x12345)
    assertEquals(clause.tryLength, 0x200)
    assertEquals(clause.handlerOffset, 0x12545)
    assertEquals(clause.handlerLength, 0x80)
  }

  test("C3-01: both formats interpret offsets identically (IL-relative)") {
    // The same clause values encoded small and fat must produce identical
    // raw clauses — the fat format gets no section-relative base (the
    // oracle applies none).
    val small = parse(EhBuilder.smallSection(EhBuilder.smallClause(
      0, tryOff = 10, tryLen = 20, handOff = 30, handLen = 40, EhBuilder.padding
    ))).head
    val fat = parse(EhBuilder.fatSection(EhBuilder.fatClause(
      0, tryOff = 10, tryLen = 20, handOff = 30, handLen = 40, EhBuilder.padding
    ))).head
    assertEquals(fat.tryOffset, small.tryOffset)
    assertEquals(fat.tryLength, small.tryLength)
    assertEquals(fat.handlerOffset, small.handlerOffset)
    assertEquals(fat.handlerLength, small.handlerLength)
    assertEquals(fat.handlerType, small.handlerType)
  }

  test("C3-01: catch resolves the type token through the resolver") {
    val section = EhBuilder.smallSection(EhBuilder.smallClause(
      0, 0, 1, 1, 1, EhBuilder.catchToken(0x2a)
    ))
    val resolved = EhReader.parseSection(section, token => {
      assertEquals(token.token, 0x0100002a)
      Some(TypeReference("System", "Exception"))
    }) match {
      case Success(clauses) => clauses
      case scala.util.Failure(t) => fail(s"parse failed: $t")
    }
    resolved.head.catchType match {
      case Some(t) => assertEquals(t.fullName, "System.Exception")
      case None => fail("catch type must resolve")
    }
  }

  test("C3-01: filter, finally and fault flags decode with their extras") {
    val filter = parse(EhBuilder.smallSection(EhBuilder.smallClause(
      1, 0, 4, 4, 2, EhBuilder.filterOffset(7)
    ))).head
    assertEquals(filter.handlerType, ExceptionHandlerType.Filter)
    assertEquals(filter.filterOffset, Some(7))
    assertEquals(filter.catchType, None)

    val finallyClause = parse(EhBuilder.smallSection(EhBuilder.smallClause(
      2, 0, 4, 4, 2, EhBuilder.padding
    ))).head
    assertEquals(finallyClause.handlerType, ExceptionHandlerType.Finally)
    assertEquals(finallyClause.filterOffset, None)
    assertEquals(finallyClause.catchType, None)

    val fault = parse(EhBuilder.smallSection(EhBuilder.smallClause(
      4, 0, 4, 4, 2, EhBuilder.padding
    ))).head
    assertEquals(fault.handlerType, ExceptionHandlerType.Fault)
  }

  test("C3-01: flags are masked with 0x7 (duplicated bit is ignored)") {
    val clause = parse(EhBuilder.smallSection(EhBuilder.smallClause(
      0x09, 0, 4, 4, 2, EhBuilder.filterOffset(3)
    ))).head
    assertEquals(clause.handlerType, ExceptionHandlerType.Filter)
    assertEquals(clause.filterOffset, Some(3))
  }

  test("C3-01: several clauses share one section") {
    val clauses = Vector(
      EhBuilder.smallClause(0, 0, 4, 4, 2, EhBuilder.catchToken(1)),
      EhBuilder.smallClause(0, 0, 4, 6, 2, EhBuilder.catchToken(2)),
      EhBuilder.smallClause(2, 0, 8, 8, 1, EhBuilder.padding)
    )
    val parsed = parse(EhBuilder.smallSection(clauses.flatten.toArray))
    assertEquals(parsed.length, 3)
    assertEquals(parsed.map(_.handlerType), Vector(
      ExceptionHandlerType.Catch,
      ExceptionHandlerType.Catch,
      ExceptionHandlerType.Finally
    ))
  }
}
