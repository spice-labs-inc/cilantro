// StyleRulesSuite — enforcement tests for the Phase 0 style invariants.
//
// Why these tests exist:
//   The Phase 0 plan (workspace/2026_08_26_cilantro/00_style_and_ground_rules.md)
//   pins four style invariants with enforcement tests C0-01..C0-04. The
//   compiler enforces -Yexplicit-nulls and -no-indent at compile time, but a
//   regression that silently drops a flag would otherwise go unnoticed: these
//   tests read build.sbt and the source tree so that removing a flag turns a
//   test red instead of letting the invariant decay silently.
//
// C0-01: build.sbt contains -Yexplicit-nulls (scalacOptions).
// C0-02: build.sbt contains -no-indent (scalacOptions).
// C0-03: no `null` literal appears in src/test sources (main sources are
//        compiler-guaranteed by -Yexplicit-nulls).
// C0-04: no `throw` keyword appears in src/test sources. Interpretation note:
//        main sources legitimately throw OperationNotSupportedException /
//        IllegalArgumentException for unrecoverable states (Cecil parity).
//        The no-exceptions-for-control-flow rule is enforced by scanning the
//        test tree, which this program owns outright.
//
// LLM-friendly notes:
//   - The scans are line-based over .scala files only, excluding the
//     generated target/ directory.
//   - The regexes are word-boundary based to avoid matching identifiers
//     that merely contain "null" as a substring (e.g. "nullity").
//   - These tests intentionally contain no `null` literal and no `throw`
//     keyword so the suite does not flag itself.

package io.spicelabs.cilantro.metadata

import java.nio.file.{Files, Paths}

class StyleRulesSuite extends munit.FunSuite {

  private def buildSbtLines: Seq[String] = {
    val path = Paths.get("build.sbt")
    Files.readAllLines(path).toArray.map(_.toString).toSeq
  }

  private def scalaSourcesUnder(dir: String): Seq[String] = {
    val root = Paths.get(dir)
    if (!Files.exists(root)) Seq.empty
    else {
      val stream = Files.walk(root)
      try {
        stream.toArray.map(_.toString).toSeq.filter { p =>
          p.endsWith(".scala")
        }
      } finally {
        stream.close()
      }
    }
  }

  test("C0-01: build.sbt enables -Yexplicit-nulls") {
    val lines = buildSbtLines
    assert(
      lines.exists(_.contains("-Yexplicit-nulls")),
      "build.sbt must include -Yexplicit-nulls in scalacOptions"
    )
  }

  test("C0-02: build.sbt enables -no-indent") {
    val lines = buildSbtLines
    assert(
      lines.exists(_.contains("-no-indent")),
      "build.sbt must include -no-indent in scalacOptions"
    )
  }

  // Strips comments and string-literal contents so the scans below see code
  // only. State persists across lines because block comments and strings can
  // span line breaks.
  private def codeOnly(lines: Seq[String]): String = {
    val sb = new StringBuilder
    var inBlock = false
    var inString = false
    var escaped = false
    lines.foreach { line =>
      var i = 0
      while (i < line.length) {
        val c = line.charAt(i)
        val n = if (i + 1 < line.length) line.charAt(i + 1) else ' '
        if (inString) {
          if (escaped) {
            escaped = false
          } else if (c == '\\') {
            escaped = true
          } else if (c == '"') {
            inString = false
          }
        } else if (inBlock) {
          if (c == '*' && n == '/') {
            inBlock = false
            i += 1
          }
        } else if (c == '/' && n == '/') {
          i = line.length
        } else if (c == '/' && n == '*') {
          inBlock = true
          i += 1
        } else if (c == '"') {
          inString = true
        } else {
          sb.append(c)
        }
        i += 1
      }
      sb.append('\n')
    }
    sb.toString
  }

  private def codeTextOf(f: String): String = {
    val lines = Files
      .readAllLines(Paths.get(f))
      .toArray
      .map(_.toString)
      .toSeq
    codeOnly(lines)
  }

  test("C0-03: no null literal in test sources") {
    val files = scalaSourcesUnder("src/test")
    val offenders = files.filter { f =>
      codeTextOf(f).matches(".*\\bnull\\b.*")
    }
    assertEquals(
      offenders,
      Seq.empty[String],
      "src/test must contain no null literals"
    )
  }

  test("C0-03b: no null literal in main sources") {
    val files = scalaSourcesUnder("src/main")
    val offenders = files.filter { f =>
      codeTextOf(f).matches(".*\\bnull\\b.*")
    }
    assertEquals(
      offenders,
      Seq.empty[String],
      "src/main must contain no null literals (compiler-guaranteed for types, this pins the literals)"
    )
  }

  test("C0-04: no throw keyword in test sources") {
    val files = scalaSourcesUnder("src/test")
    val offenders = files.filter { f =>
      codeTextOf(f).matches(".*\\bthrow\\b.*")
    }
    assertEquals(
      offenders,
      Seq.empty[String],
      "src/test must contain no throw keyword (exceptions must not be used for control flow)"
    )
  }
}
