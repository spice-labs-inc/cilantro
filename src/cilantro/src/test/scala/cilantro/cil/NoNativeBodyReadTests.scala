// NoNativeBodyReadTests — C3-07.
//
// Why this test exists:
//   Native-entrypoint and mixed-mode methods have bodies that are not
//   comparable to pure-managed output (Phase 1 ADR): they participate in
//   metadata-only parity. readBody must yield metadata-only results —
//   Success(None) — for RVA == 0, P/Invoke, internal-call, runtime,
//   native and unmanaged methods instead of attempting a bogus read, and
//   must never throw while enumerating a real mixed-mode assembly.
//
// Theory of the test:
//   Synthetic methods with the relevant attribute combinations pin the
//   hasBody gate. The real mixed-mode corpus specimen
//   (Stub.System.Data.SQLite.Core.NetFramework, C++/CLI) is loaded and
//   every method's readBody must return Success (Some or None) — the
//   metadata-only contract.

package io.spicelabs.cilantro.cil

import scala.util.{Failure, Success}
import io.spicelabs.cilantro.{AssemblyDefinition, MethodDefinition, MethodAttributes, MethodImplAttributes, TypeReference}
import io.spicelabs.cilantro.metadata.CorpusProvisioner

class NoNativeBodyReadTests extends munit.FunSuite {
  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  private def methodWith(
      attributes: Char = 0,
      implAttributes: Char = 0
  ): MethodDefinition = {
    val method = MethodDefinition("M", attributes, TypeReference("", ""))
    method.implAttributes = implAttributes
    method
  }

  test("C3-07: P/Invoke methods yield no body") {
    val method = methodWith(attributes = MethodAttributes.pInvokeImpl.value)
    assert(!method.hasBody)
    MethodBodyReader.readBody(method) match {
      case Success(result) => assertEquals(result, None)
      case Failure(t) => fail(s"P/Invoke must be Success(None): $t")
    }
  }

  test("C3-07: internal-call methods yield no body") {
    val method = methodWith(implAttributes = MethodImplAttributes.internalCall.value.toChar)
    assert(!method.hasBody)
    MethodBodyReader.readBody(method) match {
      case Success(result) => assertEquals(result, None)
      case Failure(t) => fail(s"internal call must be Success(None): $t")
    }
  }

  test("C3-07: runtime-implemented methods yield no body") {
    val method = methodWith(implAttributes = MethodImplAttributes.runtime.value.toChar)
    assert(!method.hasBody)
    MethodBodyReader.readBody(method) match {
      case Success(result) => assertEquals(result, None)
      case Failure(t) => fail(s"runtime method must be Success(None): $t")
    }
  }

  test("C3-07: abstract methods yield no body") {
    val method = methodWith(attributes = MethodAttributes.`abstract`.value)
    assert(!method.hasBody)
  }

  test("C3-07: a plain method has a body") {
    assert(methodWith().hasBody)
  }

  test("C3-07: every method of the mixed-mode corpus specimen reads or skips cleanly") {
    val root = CorpusProvisioner.ensureCorpus()
    val assembly = AssemblyDefinition.readAssembly(
      root.resolve("bin/Stub.System.Data.SQLite.Core.NetFramework/1.0.119/net46/System.Data.SQLite.dll").toString
    ) match {
      case Success(a) => a
      case Failure(t) => fail(s"failed to load mixed-mode specimen: $t")
    }
    val module = assembly.mainModule.get
    var methods = 0
    var bodies = 0
    var skipped = 0
    module.types.foreach { typeDef =>
      typeDef.methods.foreach { method =>
        methods += 1
        method.readBody() match {
          case Success(Some(_)) => bodies += 1
          case Success(None) => skipped += 1
          case Failure(t) => fail(s"mixed-mode method read failed: ${method.name}: $t")
        }
      }
    }
    assert(methods > 0, "the specimen must expose methods (metadata parity)")
    assert(
      skipped > 0,
      "a mixed-mode assembly must include native/P/Invoke methods that skip body reads"
    )
    // Metadata-only: enumerating every method never requires a body.
    assert(bodies + skipped == methods)
  }
}
