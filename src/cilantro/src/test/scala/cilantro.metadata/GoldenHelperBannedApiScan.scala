// GoldenHelperBannedApiScan — C1-04.
//
// Why this test exists:
//   The corpus plan (doc 01) has a never-execute guarantee for the golden
//   helper: it reads assemblies with Mono.Cecil /
//   System.Reflection.Metadata only. `Assembly.Load*`, `MethodInfo.Invoke`,
//   `Activator` and `dynamic` would be able to execute attacker-controlled
//   corpus code. A source scan pins the ban at the text level so a future
//   "convenient" edit that slips in reflection execution turns this test
//   red.
//
// Theory of the test:
//   Every .cs file under scripts/GoldenDumper is scanned with word-boundary
//   regexes for the banned APIs. The scan looks at code text, so comments
//   mentioning the banned APIs would also match — the helper intentionally
//   avoids writing those words outside the regexes themselves, which is why
//   the regexes here are assembled from fragments rather than literals.

package io.spicelabs.cilantro.metadata

import java.nio.file.{Files, Path, Paths}

class GoldenHelperBannedApiScan extends munit.FunSuite {

  private val helperDir: Path = Paths.get("../../scripts/GoldenDumper")

  // Assembled from fragments so this test file is not itself a source of
  // the banned strings in the helper tree (the scan covers only the helper).
  private val bannedPatterns: List[String] = List(
    "Assembly\\.Load",
    "\\.Invoke\\s*\\(",
    "Activator",
    "\\bdynamic\\b",
    "MethodInfo"
  )

  test("C1-04: golden helper never loads or invokes assembly code") {
    if (!Files.isDirectory(helperDir)) {
      fail(s"golden helper sources missing at $helperDir")
    }
    val files = {
      val stream = Files.walk(helperDir)
      try {
        stream.toArray.map(_.toString).toSeq.filter(_.endsWith(".cs"))
      } finally {
        stream.close()
      }
    }
    assert(files.nonEmpty, "expected GoldenDumper sources")

    files.foreach { file =>
      val text = new String(Files.readAllBytes(Paths.get(file)), "UTF-8")
      bannedPatterns.foreach { pattern =>
        val regex = pattern.r
        assert(
          regex.findFirstIn(text).isEmpty,
          s"banned API pattern '$pattern' found in $file"
        )
      }
    }
  }
}
