// CapTests — C4-02 (cap limits) and C4-03 (header-parse bombs).
//
// Why these tests exist:
//   Phase 4's resource caps bound hostile-input DoS (multi-GB claims on
//   tiny files must fail at header parse, before any allocation or
//   read). Each cap needs a pass at the limit and a clean Failure at
//   limit+1, plus a "bomb" (absurd claim) that fails at the same header
//   check. Without these, a hostile PE could claim a 4 GB metadata heap
//   and either OOM or read far past EOF.
//
// Theory of the test:
//   MinimalPeBuilder emits a valid minimal PE whose headers declare the
//   values under test. Reading it exercises exactly the ImageReader
//   header walk (sections -> CLI -> metadata root -> stream sizes ->
//   table row counts). Success = the reader accepted the shape; Failure
//   = a DataFormatException from the cap check, not an OOM or an EOF
//   read past the file. The bomb cases pin the same code paths with
//   claims near 2^31.
//
// Requirements traced:
//   04_parity_harness_and_security_caps.md — the caps table:
//   PE sections <= 96; raw/virtual size <= 512 MB; metadata table rows
//   <= 10M; heaps <= 256 MB (with rows x rowSize overflow checks and
//   field lengths <= heap size).
//
// LLM notes:
//   - readModule returns Try[ModuleDefinition]; a cap violation is a
//     clean Failure, never a thrown escape.
//   - The section-size cap fires on the declared header values, so no
//     large backing data is needed.
//   - The table-row cap fires while reading the #~ row counts (before
//     any per-row allocation), so a 10M-row claim on a tiny file is
//     safe to test at the limit.

package io.spicelabs.cilantro.cil

import java.io.FileOutputStream
import io.spicelabs.cilantro.ModuleDefinition

class CapTests extends munit.FunSuite {

  private def withPe(builder: MinimalPeBuilder)(body: String => Unit): Unit = {
    val file = java.io.File.createTempFile("cap", ".dll")
    val out = new FileOutputStream(file)
    out.write(builder.build())
    out.close()
    try {
      body(file.getAbsolutePath)
    } finally {
      file.delete()
    }
  }

  test("C4-02: section count cap accepts the limit and rejects limit+1") {
    withPe(new MinimalPeBuilder(sectionCount = 96)) { path =>
      assert(ModuleDefinition.readModule(path).isSuccess, "96 sections is the cap and must parse")
    }
    withPe(new MinimalPeBuilder(sectionCount = 97)) { path =>
      assert(ModuleDefinition.readModule(path).isFailure, "97 sections must fail cleanly")
    }
  }

  test("C4-02: section virtual size cap accepts the limit and rejects limit+1") {
    val limit = 512 * 1024 * 1024
    withPe(new MinimalPeBuilder(sectionVirtualSize = limit)) { path =>
      assert(ModuleDefinition.readModule(path).isSuccess, "512 MB virtual size claim must parse")
    }
    withPe(new MinimalPeBuilder(sectionVirtualSize = limit + 1)) { path =>
      assert(ModuleDefinition.readModule(path).isFailure, "512 MB + 1 must fail cleanly")
    }
  }

  test("C4-02: section raw size cap accepts the limit and rejects limit+1") {
    val limit = 512 * 1024 * 1024
    withPe(new MinimalPeBuilder(sectionRawSize = limit)) { path =>
      assert(ModuleDefinition.readModule(path).isSuccess, "512 MB raw size claim must parse")
    }
    withPe(new MinimalPeBuilder(sectionRawSize = limit + 1)) { path =>
      assert(ModuleDefinition.readModule(path).isFailure, "512 MB + 1 must fail cleanly")
    }
  }

  test("C4-02: table row cap accepts the limit and rejects limit+1") {
    withPe(new MinimalPeBuilder(typeDefRows = 10_000_000)) { path =>
      assert(ModuleDefinition.readModule(path).isSuccess, "10M TypeDef rows is the cap and must parse")
    }
    withPe(new MinimalPeBuilder(typeDefRows = 10_000_001)) { path =>
      assert(ModuleDefinition.readModule(path).isFailure, "10M + 1 TypeDef rows must fail cleanly")
    }
  }

  test("C4-03: section count bomb (65535) fails at header parse") {
    withPe(new MinimalPeBuilder(sectionCount = 65535)) { path =>
      assert(ModuleDefinition.readModule(path).isFailure, "65535 sections must fail before allocation")
    }
  }

  test("C4-03: section size bomb (2^31) fails at header parse") {
    withPe(new MinimalPeBuilder(sectionVirtualSize = 0x7fffffff)) { path =>
      assert(ModuleDefinition.readModule(path).isFailure, "2 GB virtual size claim must fail before reads")
    }
  }

  test("C4-03: blob heap bomb (2^31 declared) fails before the heap read") {
    withPe(new MinimalPeBuilder(blobHeapSize = 0x7fffffff)) { path =>
      assert(ModuleDefinition.readModule(path).isFailure, "2 GB #Blob claim must fail before the heap read")
    }
  }

  test("C4-02: rows x rowSize offset arithmetic cannot overflow Int") {
    // Plan 04: the cumulative table offset must be computed in 64-bit and
    // checked. Enough tables at the row cap push the running offset past
    // Int.MaxValue; the reader must fail cleanly instead of wrapping.
    val extraRows = (1 to 55).filterNot(id => id == 0 || id == 2 || id == 4 || id == 6)
      .map(id => id -> 10_000_000).toMap
    withPe(new MinimalPeBuilder(typeDefRows = 10_000_000, tableRowCounts = extraRows)) { path =>
      assert(ModuleDefinition.readModule(path).isFailure, "overflowing table offsets must fail cleanly")
    }
    // The single-table case (the C4-02 row-cap test above) parses: the
    // arithmetic stays within Int for one 10M-row table.
  }

  test("C4-03: a base-type chain deeper than 128 aborts; a 100-deep chain reads") {
    withPe(new MinimalPeBuilder(typeDefChain = 129)) { path =>
      val result = ModuleDefinition.readModule(path).flatMap { m =>
        scala.util.Try { m.types.length }
      }
      assert(result.isFailure, "a 129-deep base chain must abort cleanly (recursion cap 128)")
    }
    withPe(new MinimalPeBuilder(typeDefChain = 100)) { path =>
      val result = ModuleDefinition.readModule(path).flatMap { m =>
        scala.util.Try { m.types.length }
      }
      assertEquals(result.toOption, Some(100), "a 100-deep chain reads within the cap")
    }
  }

  test("C4-03: a signature nesting more than 128 types aborts; a 100-deep signature reads") {
    withPe(new MinimalPeBuilder(typeDefChain = 1, deepFieldSigDepth = 130)) { path =>
      val result = ModuleDefinition.readModule(path).flatMap { m =>
        scala.util.Try {
          m.types.foreach { t =>
            t.fields.foreach(f => f.fieldType)
          }
        }
      }
      assert(result.isFailure, "a 130-deep signature must abort cleanly (recursion cap 128)")
    }
    withPe(new MinimalPeBuilder(typeDefChain = 1, deepFieldSigDepth = 100)) { path =>
      val result = ModuleDefinition.readModule(path).flatMap { m =>
        scala.util.Try {
          m.types.foreach { t =>
            t.fields.foreach(f => f.fieldType)
          }
        }
      }
      assert(result.isSuccess, "a 100-deep signature reads within the cap")
    }
  }

  test("C4-02: method body cap fires above 64 MB and passes exactly at 64 MB") {
    // The cap is a pre-check on the fat-header code size, before any
    // code read: a header claiming 64 MB + 1 fails with the cap message
    // on a tiny file; a header claiming exactly 64 MB passes the cap and
    // fails later on the truncated data (proving the boundary).
    withPe(new MinimalPeBuilder(typeDefChain = 1, methodBodyClaim = 64 * 1024 * 1024 + 1)) { path =>
      val result = ModuleDefinition.readModule(path).flatMap { m =>
        m.types.head.methods.head.readBody()
      }
      assert(result.isFailure, "a 64 MB + 1 body claim must fail")
      assert(result.failed.get.getMessage.contains("64 MB cap"), "the failure must be the cap itself")
    }
    withPe(new MinimalPeBuilder(typeDefChain = 1, methodBodyClaim = 64 * 1024 * 1024)) { path =>
      val result = ModuleDefinition.readModule(path).flatMap { m =>
        m.types.head.methods.head.readBody()
      }
      assert(result.isFailure, "a 64 MB claim on a tiny file fails on the missing data")
      val message = Option(result.failed.get.getMessage)
      assert(!message.exists(_.contains("64 MB cap")), "exactly 64 MB must pass the cap check")
    }
  }

  test("C4-03: a hostile custom-attribute type string deeper than 128 aborts") {
    val deep = new StringBuilder("T`1")
    for _ <- 1 to 130 do deep.append("[[T`1")
    for _ <- 1 to 130 do deep.append("]]")
    val result = scala.util.Try {
      io.spicelabs.cilantro.TypeParser.parseType(None, deep.toString)
    }
    assert(result.isFailure, "a 130-deep generic type string must abort cleanly (recursion cap 128)")
    val shallow = new StringBuilder("T`1")
    for _ <- 1 to 100 do shallow.append("[[T`1")
    for _ <- 1 to 100 do shallow.append("]]")
    val ok = scala.util.Try {
      io.spicelabs.cilantro.TypeParser.parseType(None, shallow.toString)
    }
    assert(ok.isSuccess, "a 100-deep generic type string parses within the cap")
  }
}
