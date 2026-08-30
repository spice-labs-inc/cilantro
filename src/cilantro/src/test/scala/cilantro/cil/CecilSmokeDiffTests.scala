// CecilSmokeDiffTests — C2-08 (Phase 2 gate).
//
// Why this test exists:
//   The golden dumper (Mono.Cecil 0.11.6, the pinned oracle) recorded
//   per-instruction offsets, opcodes and resolved operands for the corpus.
//   This test decodes ten real method bodies — the eight ilasm-fixture
//   bodies plus two corpus methods — and diffs instruction-for-instruction
//   against the oracle. It is the Phase 2 gate: opcode-table errors,
//   operand-width errors and branch/switch bugs surface here before the
//   full-corpus harness lands in Phase 4.
//
// Theory of the test:
//   The body byte stream is reconstructed from the oracle's own rawBytes
//   (concatenated in offset order) wrapped in a fat header carrying the
//   golden's codeSize/maxStack/initLocals, so the comparison is against
//   bytes Cecil itself read. Operand comparison follows the golden JSON
//   shape: numbers are inline values or branch targets (disambiguated by
//   operand type), arrays are switch targets, "local.N"/"param.N" are
//   variables/arguments, and resolved names (tokens) are presence-checked
//   because token resolution lands with the reader wiring in Phase 3.

package io.spicelabs.cilantro.cil

import org.json4s._
import org.json4s.native.JsonMethods
import io.spicelabs.cilantro.metadata.CorpusHelpers

class CecilSmokeDiffTests extends munit.FunSuite {

  implicit val formats: DefaultFormats.type = DefaultFormats

  private def goldenJson(relPath: String): JValue = {
    CorpusHelpers.requireCorpus(CorpusHelpers.corpusRoot) match {
      case None => fail("corpus missing at ../../corpus — run scripts/ensure_corpus.sh")
      case Some(root) =>
        val path = root.resolve(relPath)
        JsonMethods.parse(CorpusHelpers.readGzipJson(path))
    }
  }

  private final case class GoldenBody(
      method: String,
      codeSize: Int,
      initLocals: Boolean,
      maxStack: Int,
      instructions: List[(Int, String, JValue)]
  )

  private def parseBodies(tier2: JValue): List[GoldenBody] = {
    (tier2 \ "bodies").extract[List[JValue]].map { body =>
      val instructions = (body \ "instructions").extract[List[JValue]].map { instr =>
        (
          (instr \ "offset").extract[Int],
          (instr \ "opcode").extract[String],
          instr \ "operand"
        )
      }
      GoldenBody(
        method = (body \ "method").extract[String],
        codeSize = (body \ "codeSize").extract[Int],
        initLocals = (body \ "initLocals").extract[Boolean],
        maxStack = (body \ "maxStack").extract[Int],
        instructions = instructions
      )
    }
  }

  private def rawBytesOf(tier2: JValue, body: GoldenBody): Array[Byte] = {
    // Reconstruct the raw body bytes from the oracle's own instruction
    // dumps; the golden must list rawBytes for every instruction.
    (tier2 \ "bodies").extract[List[JValue]].find(b => (b \ "method").extract[String] == body.method) match {
      case None => fail(s"body not found: ${body.method}")
      case Some(b) =>
        val chunks = (b \ "instructions").extract[List[JValue]].map { instr =>
          val hex = (instr \ "rawBytes").extract[String]
          hex.grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray
        }
        chunks.foldLeft(Array.emptyByteArray)(_ ++ _)
    }
  }

  private def decode(goldenBody: GoldenBody, bytes: Array[Byte]): MethodBody = {
    CodeReader.readBody(BodyBuilder.fat(
      bytes,
      maxStack = goldenBody.maxStack,
      initLocals = goldenBody.initLocals,
      localSigToken = 0
    ), _ => None, _ => None) match {
      case scala.util.Success(body) => body
      case scala.util.Failure(t) => fail(s"decode failed for ${goldenBody.method}: $t")
    }
  }

  private def compareOperand(golden: JValue, body: MethodBody, index: Int): Unit = {
    val instr = body.instructions(index)
    val ot = instr.opcode.operandType
    ot match {
      case OperandType.InlineNone | OperandType.InlinePhi =>
        assertEquals(instr.operand, None, s"operand of ${instr.opcode.name} at $index")

      case OperandType.ShortInlineBrTarget | OperandType.InlineBrTarget =>
        golden match {
          case JInt(targetOffset) =>
            instr.operand match {
              case Some(Operand.Branch(targetIndex)) =>
                assertEquals(
                  body.instructions(targetIndex).offset,
                  targetOffset.toInt,
                  s"branch target at $index"
                )
              case other => fail(s"expected Branch at $index, got $other")
            }
          case other => fail(s"golden branch operand must be a number, got $other at $index")
        }

      case OperandType.InlineSwitch =>
        val targets = golden match {
          case JArray(items) => items.map { case JInt(n) => n.toInt; case other => fail(s"switch target must be a number: $other") }
          case other => fail(s"golden switch operand must be an array, got $other at $index")
        }
        instr.operand match {
          case Some(Operand.Switch(indices)) =>
            assertEquals(indices.map(body.instructions(_).offset).toList, targets, s"switch targets at $index")
          case other => fail(s"expected Switch at $index, got $other")
        }

      case OperandType.InlineI | OperandType.ShortInlineI =>
        val goldenInt = golden match {
          case JInt(n) => n.toInt
          case other => fail(s"golden i4 operand must be a number, got $other at $index")
        }
        instr.operand match {
          case Some(Operand.I4(value)) => assertEquals(value, goldenInt, s"inline i4 at $index")
          case other => fail(s"expected I4 at $index, got $other")
        }

      case OperandType.InlineI8 =>
        val goldenLong = golden match {
          case JInt(n) => n.toLong
          case other => fail(s"golden i8 operand must be a number, got $other at $index")
        }
        instr.operand match {
          case Some(Operand.I8(value)) => assertEquals(value, goldenLong, s"inline i8 at $index")
          case other => fail(s"expected I8 at $index, got $other")
        }

      case OperandType.ShortInlineR | OperandType.InlineR =>
        val goldenDouble = golden match {
          case JString(s) => s.toDouble
          case JDouble(d) => d
          case other => fail(s"golden float operand must be a string, got $other at $index")
        }
        instr.operand match {
          case Some(Operand.R4(value)) => assertEquals(value.toDouble, goldenDouble, s"inline r4 at $index")
          case Some(Operand.R8(value)) => assertEquals(value, goldenDouble, s"inline r8 at $index")
          case other => fail(s"expected R4/R8 at $index, got $other")
        }

      case OperandType.InlineVar | OperandType.ShortInlineVar =>
        golden match {
          case JString(s) if s.startsWith("local.") =>
            val expected = s.stripPrefix("local.").toInt
            instr.operand match {
              case Some(Operand.Var(value)) => assertEquals(value, expected, s"var index at $index")
              case other => fail(s"expected Var at $index, got $other")
            }
          case other => fail(s"golden var operand must be local.N, got $other at $index")
        }

      case OperandType.InlineArg | OperandType.ShortInlineArg =>
        golden match {
          case JString(s) if s.startsWith("param.") =>
            val expected = s.stripPrefix("param.").toInt
            instr.operand match {
              case Some(Operand.Arg(value)) => assertEquals(value, expected, s"arg index at $index")
              case other => fail(s"expected Arg at $index, got $other")
            }
          case other => fail(s"golden arg operand must be param.N, got $other at $index")
        }

      case OperandType.InlineTok | OperandType.InlineMethod | OperandType.InlineField |
          OperandType.InlineType | OperandType.InlineString | OperandType.InlineSig =>
        // Token resolution lands in Phase 3; the smoke diff checks that an
        // operand exists and the golden records one (a resolved name).
        assert(instr.operand.isDefined, s"token operand missing at $index")
        golden match {
          case JString(_) => ()
          case other => fail(s"golden token operand must be a string, got $other at $index")
        }
    }
  }

  private def diffBody(tier2: JValue, goldenBody: GoldenBody): Unit = {
    val bytes = rawBytesOf(tier2, goldenBody)
    assertEquals(bytes.length, goldenBody.codeSize, s"reconstructed size for ${goldenBody.method}")
    val body = decode(goldenBody, bytes)
    assertEquals(
      body.instructions.length,
      goldenBody.instructions.length,
      s"instruction count for ${goldenBody.method}"
    )
    goldenBody.instructions.zipWithIndex.foreach { case ((offset, opcode, operand), idx) =>
      val instr = body.instructions(idx)
      assertEquals(instr.offset, offset, s"offset of instruction $idx in ${goldenBody.method}")
      assertEquals(instr.opcode.name, opcode, s"opcode of instruction $idx in ${goldenBody.method}")
      compareOperand(operand, body, idx)
    }
  }

  test("C2-08: fixture bodies decode identically to the Cecil oracle") {
    val tier2 = goldenJson("golden/fixtures/ilasm_fixture.tier2.json")
    val bodies = parseBodies(tier2)
    assert(bodies.nonEmpty)
    bodies.foreach(body => diffBody(tier2, body))
  }

  test("C2-08: two corpus methods decode identically to the Cecil oracle") {
    val tier2 = goldenJson("golden/bin/Newtonsoft.Json/12.0.3/net45/Newtonsoft.Json.dll.tier2.json")
    val bodies = parseBodies(tier2)
    val selected = bodies
      .filter { body =>
        // Phase 2 has no user-string heap reader; pick bodies without
        // ldstr so the smoke diff covers tokens via presence checks only.
        !body.instructions.exists { case (_, opcode, _) => opcode == "ldstr" }
      }
      .take(2)
    assertEquals(selected.length, 2, "expected two corpus methods for the smoke diff")
    selected.foreach(body => diffBody(tier2, body))
  }
}
