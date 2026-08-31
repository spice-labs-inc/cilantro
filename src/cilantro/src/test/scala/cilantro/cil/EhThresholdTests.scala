// EhThresholdTests — C3-02.
//
// Why this test exists:
//   The small/fat decision lives in the emitter, but the reader must be
//   exact at the format boundaries: the largest small-form values
//   (0xFFFF offsets, 0xFF lengths, 3 clauses) and the values just beyond
//   them (0x10000 offsets, 4 clauses) which force the fat form. This test
//   pins both formats at those exact boundaries — including the mono
//   ilasm quirk that a small section may carry four clauses (the oracle
//   reads them: count = size / 12), which the corpus fixture depends on.
//
// Theory of the test:
//   Hand-built small and fat sections at the boundary values are parsed
//   and the fields compared. A four-clause small section must parse all
//   four (matching the oracle's handling of the fixture's EhFat method).

package io.spicelabs.cilantro.cil

import scala.util.Success

class EhThresholdTests extends munit.FunSuite {

  private def parse(section: Array[Byte]): Vector[EhReader.RawExceptionClause] = {
    EhReader.parseSection(section, _ => None) match {
      case Success(clauses) => clauses
      case scala.util.Failure(t) => fail(s"parse failed: $t")
    }
  }

  test("C3-02: small offsets at the 0xFFFF boundary decode exactly") {
    val clause = parse(EhBuilder.smallSection(EhBuilder.smallClause(
      0, tryOff = 0xFFFF, tryLen = 0xFF, handOff = 0xFFFF, handLen = 0xFF,
      EhBuilder.catchToken(1)
    ))).head
    assertEquals(clause.tryOffset, 0xFFFF)
    assertEquals(clause.tryLength, 0xFF)
    assertEquals(clause.handlerOffset, 0xFFFF)
    assertEquals(clause.handlerLength, 0xFF)
  }

  test("C3-02: offsets beyond 0xFFFF require the fat form and decode") {
    val clause = parse(EhBuilder.fatSection(EhBuilder.fatClause(
      0, tryOff = 0x10000, tryLen = 0x200, handOff = 0x10200, handLen = 0x80,
      EhBuilder.catchToken(1)
    ))).head
    assertEquals(clause.tryOffset, 0x10000)
    assertEquals(clause.handlerOffset, 0x10200)
  }

  test("C3-02: three clauses parse in the small form") {
    val clauses = (0 until 3).map(i =>
      EhBuilder.smallClause(0, 0, i + 1, i + 1, 1, EhBuilder.catchToken(i + 1))
    ).flatten.toArray
    val parsed = parse(EhBuilder.smallSection(clauses))
    assertEquals(parsed.length, 3)
  }

  test("C3-02: four clauses parse in the small form (mono ilasm quirk)") {
    // mono ilasm emits four-clause small sections; the oracle reads them
    // (count = size / 12). Pinned by the corpus fixture's EhFat method.
    val clauses = (0 until 4).map(i =>
      EhBuilder.smallClause(2, 0, i + 1, i + 1, 1, EhBuilder.padding)
    ).flatten.toArray
    val parsed = parse(EhBuilder.smallSection(clauses))
    assertEquals(parsed.length, 4)
  }

  test("C3-02: four clauses parse in the fat form") {
    val clauses = (0 until 4).map(i =>
      EhBuilder.fatClause(2, 0, i + 1, i + 1, 1, EhBuilder.padding)
    ).flatten.toArray
    val parsed = parse(EhBuilder.fatSection(clauses))
    assertEquals(parsed.length, 4)
  }

  test("C3-02: a 0xFFFF-length try in the fat form decodes") {
    val clause = parse(EhBuilder.fatSection(EhBuilder.fatClause(
      0, tryOff = 0, tryLen = 0xFFFF, handOff = 0xFFFF, handLen = 0xFFFF,
      EhBuilder.catchToken(1)
    ))).head
    assertEquals(clause.tryLength, 0xFFFF)
    assertEquals(clause.handlerLength, 0xFFFF)
  }
}
