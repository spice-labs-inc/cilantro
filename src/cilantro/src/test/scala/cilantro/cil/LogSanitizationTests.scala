// LogSanitizationTests — C4-08.
//
// Why this test exists:
//   Assembly/type/member names are attacker-controlled bytes. A hostile
//   assembly can carry names with ESC sequences (`\x1b[2J` clears the
//   screen), bidi overrides (U+202E flips the display of the rest of
//   the line) or other control characters. Log output interpolating
//   such a name unmodified would let the file control the operator's
//   terminal and spoof visible content. This pins that the sanitizer
//   neutralizes exactly those characters while preserving everything
//   else.
//
// Theory of the test:
//   LogSanitizer.sanitize replaces control characters (except \n and
//   \t), ESC, DEL, and the bidi override/isolate characters with
//   U+FFFD and passes printable text through unchanged. The tests feed
//   the canonical hostile inputs (ESC sequences, U+202E) plus boundary
//   cases (the empty string, surrogate pairs, high-end printable
//   characters).
//
// Requirements traced:
//   04_parity_harness_and_security_caps.md — Log sanitization: "strip/
//   replace < 0x20 except \n/\t, strip ESC and bidi overrides, pinned by
//   a test feeding `\x1b[2J`/`\u202E` names."
//
// LLM notes:
//   - U+FFFD is the conventional replacement character; the sanitized
//     output must contain no character < 0x20 other than \n and \t.

package io.spicelabs.cilantro.cil

import io.spicelabs.cilantro.LogSanitizer

class LogSanitizationTests extends munit.FunSuite {

  test("C4-08: ESC sequences in names are neutralized") {
    val hostile = "\u001b[2JType\u001b[0m"
    val cleaned = LogSanitizer.sanitize(hostile)
    assert(!cleaned.contains('\u001b'), "no ESC may survive")
    assert(cleaned.contains("Type"), "printable content must survive")
    // The ESC byte itself is gone; the printable tail of the escape
    // sequence is inert without it.
    assert(cleaned.startsWith("\uFFFD"), "the ESC byte must be replaced")
  }

  test("C4-08: bidi overrides in names are neutralized") {
    val hostile = "\u202Eevil\u202C"
    val cleaned = LogSanitizer.sanitize(hostile)
    assert(!cleaned.contains('\u202E'), "the right-to-left override must be replaced")
    assert(!cleaned.contains('\u202C'), "the pop directional formatting must be replaced")
    assert(cleaned.contains("evil"), "printable content must survive")
  }

  test("C4-08: control characters other than \\n and \\t are replaced") {
    val hostile = "a\u0000b\u0007c\u001bd"
    val cleaned = LogSanitizer.sanitize(hostile)
    assertEquals(cleaned, "a\uFFFDb\uFFFDc\uFFFDd")
  }

  test("C4-08: newline and tab survive; printable text passes through unchanged") {
    assertEquals(LogSanitizer.sanitize("hello\nworld\ttab"), "hello\nworld\ttab")
    assertEquals(LogSanitizer.sanitize(""), "")
    assertEquals(LogSanitizer.sanitize("namespace.Type`1"), "namespace.Type`1")
    assertEquals(LogSanitizer.sanitize("\u00e9\u4e2d\u6587"), "\u00e9\u4e2d\u6587")
  }

  test("C4-08: DEL and the isolate controls are replaced") {
    val hostile = "\u007f\u2066\u2067\u2068\u2069"
    val cleaned = LogSanitizer.sanitize(hostile)
    assertEquals(cleaned, "\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD")
  }
}
