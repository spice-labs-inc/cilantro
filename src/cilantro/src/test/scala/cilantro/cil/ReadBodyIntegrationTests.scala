// ReadBodyIntegrationTests — C3-06 (Phase 3 gate).
//
// Why this test exists:
//   This is the end-to-end gate: MethodDefinition.readBody on real
//   corpus files — RVA mapping, header parse, code decode, EH section
//   walk, clause resolution — must reproduce the oracle's instruction
//   offsets, opcodes and exception handlers. Any divergence in the
//   wiring (not the decoder, which C2-08 covers) surfaces here.
//
// Theory of the test:
//   Assemblies are loaded with our own reader; methods are located by
//   RVA (goldens record the RVA). readBody results are compared
//   instruction-for-instruction (offset + opcode) and handler-for-
//   handler (type, try/handler boundaries as offsets, catch type full
//   name, filter start) against the golden JSON. Sample set: every
//   fixture body, the x64 fixture body with EH, and five EH-bearing
//   Newtonsoft.Json methods.

package io.spicelabs.cilantro.cil

import org.json4s._
import org.json4s.native.JsonMethods
import io.spicelabs.cilantro.{AssemblyDefinition, MethodDefinition}
import io.spicelabs.cilantro.metadata.CorpusHelpers

class ReadBodyIntegrationTests extends munit.FunSuite {

  implicit val formats: DefaultFormats.type = DefaultFormats

  private def goldenJson(relPath: String): JValue = {
    CorpusHelpers.requireCorpus(CorpusHelpers.corpusRoot) match {
      case None => fail("corpus missing at ../../corpus — run scripts/ensure_corpus.sh")
      case Some(root) =>
        JsonMethods.parse(CorpusHelpers.readGzipJson(root.resolve(relPath)))
    }
  }

  private def loadAssembly(rel: String): AssemblyDefinition = {
    AssemblyDefinition.readAssembly(rel) match {
      case scala.util.Success(assembly) => assembly
      case scala.util.Failure(t) => fail(s"failed to load $rel: $t")
    }
  }

  private def allMethods(assembly: AssemblyDefinition): Map[Int, MethodDefinition] = {
    val module = assembly.mainModule.get
    module.types.flatMap { typeDef =>
      typeDef.methods.map(m => (m.RVA, m))
    }.toMap
  }

  private final case class GoldenHandler(
      handlerType: String,
      tryStart: Int,
      tryEnd: Int,
      handlerStart: Int,
      handlerEnd: Int,
      catchType: Option[String],
      filterStart: Option[Int]
  )

  private def goldenHandlers(body: JValue): List[GoldenHandler] = {
    (body \ "exceptionHandlers").children.map { handler =>
      GoldenHandler(
        handlerType = (handler \ "type").extract[String],
        tryStart = (handler \ "tryStart").extract[Int],
        tryEnd = (handler \ "tryEnd").extract[Int],
        handlerStart = (handler \ "handlerStart").extract[Int],
        handlerEnd = (handler \ "handlerEnd").extract[Int],
        catchType = (handler \ "catchType") match {
          case JString(s) => Some(s)
          case _          => None
        },
        filterStart = (handler \ "filterStart") match {
          case JInt(n) => Some(n.toInt)
          case _       => None
        }
      )
    }
  }

  private def instructionOffsets(body: JValue): List[(Int, String)] = {
    (body \ "instructions").children.map { instr =>
      ((instr \ "offset").extract[Int], (instr \ "opcode").extract[String])
    }
  }

  private def offsetOfIndex(decoded: MethodBody, index: Option[Int]): Int = {
    index.map(decoded.instructions(_).offset).getOrElse(-1)
  }

  private def diffBody(goldenBody: JValue, method: MethodDefinition): Unit = {
    val goldenRva = (goldenBody \ "rva").extract[Int]
    val methodName = (goldenBody \ "method").extract[String]
    method.readBody() match {
      case scala.util.Failure(t) => fail(s"readBody failed for $methodName: $t")
      case scala.util.Success(None) => fail(s"readBody returned no body for $methodName")
      case scala.util.Success(Some(decoded)) =>
        // Instructions: offsets + opcodes.
        val goldenInstructions = instructionOffsets(goldenBody)
        assertEquals(
          decoded.instructions.map(i => (i.offset, i.opcode.name)).toList,
          goldenInstructions,
          s"instructions for $methodName"
        )

        // Handlers.
        val golden = goldenHandlers(goldenBody)
        assertEquals(decoded.exceptionHandlers.length, golden.length, s"handler count for $methodName")
        decoded.exceptionHandlers.zip(golden).foreach { case (handler, expected) =>
          assertEquals(handler.handlerType.toString, expected.handlerType, s"handler type for $methodName")
          assertEquals(
            offsetOfIndex(decoded, handler.tryStart),
            expected.tryStart,
            s"tryStart for $methodName"
          )
          assertEquals(
            offsetOfIndex(decoded, handler.tryEnd),
            expected.tryEnd,
            s"tryEnd for $methodName"
          )
          assertEquals(
            offsetOfIndex(decoded, handler.handlerStart),
            expected.handlerStart,
            s"handlerStart for $methodName"
          )
          assertEquals(
            offsetOfIndex(decoded, handler.handlerEnd),
            expected.handlerEnd,
            s"handlerEnd for $methodName"
          )
          assertEquals(
            handler.catchType.map(_.fullName),
            expected.catchType,
            s"catchType for $methodName"
          )
          assertEquals(
            offsetOfIndex(decoded, handler.filterStart),
            expected.filterStart.getOrElse(-1),
            s"filterStart for $methodName"
          )
        }
    }
  }

  test("C3-06: every ilasm fixture body decodes and matches its golden") {
    val assembly = loadAssembly("../../corpus/fixtures/ilasm_fixture.dll")
    val methods = allMethods(assembly)
    val tier2 = goldenJson("golden/fixtures/ilasm_fixture.tier2.json")
    val bodies = (tier2 \ "bodies").children
    assert(bodies.length >= 12, "fixture must carry its EH bodies")
    bodies.foreach { goldenBody =>
      val rva = (goldenBody \ "rva").extract[Int]
      methods.get(rva) match {
        case None => fail(s"method not found by RVA 0x${rva.toHexString}")
        case Some(method) => diffBody(goldenBody, method)
      }
    }
  }

  test("C3-06: the x64 fixture catch handler matches its golden") {
    val assembly = loadAssembly("../../corpus/fixtures/x64_fixture.dll")
    val methods = allMethods(assembly)
    val tier2 = goldenJson("golden/fixtures/x64_fixture.tier2.json")
    val bodies = (tier2 \ "bodies").children
    val withCatch = bodies.find(b => (b \ "method").extract[String].contains("WithCatch"))
      .getOrElse(fail("x64 WithCatch body missing"))
    val rva = (withCatch \ "rva").extract[Int]
    diffBody(withCatch, methods(rva))
    val expected = goldenHandlers(withCatch)
    assertEquals(expected.length, 1)
    assertEquals(expected.head.handlerType, "Catch")
    assertEquals(expected.head.catchType, Some("System.ArgumentException"))
  }

  test("C3-06: five Newtonsoft.Json methods with EH match their goldens") {
    val assembly = loadAssembly("../../corpus/bin/Newtonsoft.Json/12.0.3/net45/Newtonsoft.Json.dll")
    val methods = allMethods(assembly)
    val tier2 = goldenJson("golden/bin/Newtonsoft.Json/12.0.3/net45/Newtonsoft.Json.dll.tier2.json")
    val bodies = (tier2 \ "bodies").children
    val selected = bodies
      .filter(b => (b \ "exceptionHandlers").children.nonEmpty)
      .filter(b => (b \ "instructions").children.length <= 400)
      // Nested-type declaring types land with the Phase 4 parity work;
      // pick top-level methods only (no "/" in the canonical name).
      .filter(b => !(b \ "method").extract[String].contains("/"))
      .take(5)
    assertEquals(selected.length, 5, "expected five EH-bearing Newtonsoft methods")
    selected.foreach { goldenBody =>
      val rva = (goldenBody \ "rva").extract[Int]
      methods.get(rva) match {
        case None => fail(s"method not found by RVA 0x${rva.toHexString}")
        case Some(method) => diffBody(goldenBody, method)
      }
    }
  }
}
