// NameHardeningTests — CP-6a..c: the cilantro-defined entry-name
// hardening contract (plan 2026_09_02, phase C; ADR-0014, D-5).
//
// Why these tests exist:
//   Goat Rodeo consumed names straight out of hostile metadata; the
//   handoff moves DotnetNameSanitizer into cilantro so every consumer
//   of the walk sees hardened names at the source. The contract is
//   deterministic, pure, and documented in DotnetNameSanitizer.scala;
//   these tests pin the transformation table case-by-case and sweep
//   the whole UTF-16 code-unit space.
//
// Theory of the test:
//   - CP-6a: control/format characters and lone surrogates are
//     escaped injectively ("%" + hex of the code unit) — two distinct
//     hostile names cannot collapse onto one output through the
//     escape or through later UTF-8 re-encoding (no U+FFFD merging).
//   - CP-6b: '/' '\' ':' never survive as structure; '.'/'..'
//     leading-dot forms are neutralized; outputs are single safe
//     components; exact transformation examples are pinned.
//   - CP-6c: the length cap boundary (255 UTF-16 units) and
//     idempotence; sanitized output is never empty.
//   - The property sweep runs all 65,536 UTF-16 code units (lone
//     surrogates included) through the sanitizer and checks the rule
//     set — not just ASCII.
//   - End-to-end: the walk's entry names are sanitized at the source
//     (hostile win32 and resource names reach the walk hardened; the
//     assembly walk suites in AssemblyWalkerTests pin the wiring).
//
// Requirements traced:
//   workspace/2026_09_01_cilantro_handoff.md §4 CP-6; ADR-0014 D-5.

package io.spicelabs.cilantro.cil

import io.spicelabs.cilantro.DotnetNameSanitizer

class NameHardeningTests extends munit.FunSuite {

  private val hex = "0123456789abcdef".toCharArray

  private def esc(c: Int): String =
    if (c < 0x100) "%" + hex((c >> 4) & 0x0f) + hex(c & 0x0f)
    else "%" + hex((c >> 12) & 0x0f) + hex((c >> 8) & 0x0f) + hex((c >> 4) & 0x0f) + hex(c & 0x0f)

  private def safeComponent(name: String): Boolean = {
    name.nonEmpty &&
    name.length <= DotnetNameSanitizer.maxNameUnits &&
    !name.contains('/') &&
    !name.contains('\\') &&
    !name.contains(':') &&
    !name.startsWith(".") &&
    !name.exists(c => c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == 0x200e || c == 0x200f || (c >= 0x202a && c <= 0x202e)) &&
    !name.endsWith(".") &&
    !name.endsWith(" ")
  }

  test("CP-6a: controls, format characters, and lone surrogates are escaped injectively") {
    // Control characters C0/C1 and the format/bidi controls become
    // percent escapes of the code unit.
    assertEquals(DotnetNameSanitizer.sanitize("a\u0000b"), "a" + esc(0) + "b")
    assertEquals(DotnetNameSanitizer.sanitize("a\u001fb"), "a" + esc(0x1f) + "b")
    assertEquals(DotnetNameSanitizer.sanitize("a\u007fb"), "a" + esc(0x7f) + "b")
    assertEquals(DotnetNameSanitizer.sanitize("a\u009fb"), "a" + esc(0x9f) + "b")
    assertEquals(DotnetNameSanitizer.sanitize("a\u200eb"), "a" + esc(0x200e) + "b")
    assertEquals(DotnetNameSanitizer.sanitize("a\u202eb"), "a" + esc(0x202e) + "b")

    // A lone surrogate escapes as its own code unit (never U+FFFD),
    // so two distinct hostile names stay distinct after any UTF-8
    // round trip.
    val high = DotnetNameSanitizer.sanitize("a\ud800b")
    val low = DotnetNameSanitizer.sanitize("a\udc00b")
    assertEquals(high, "a" + esc(0xd800) + "b")
    assertEquals(low, "a" + esc(0xdc00) + "b")
    assertNotEquals(high, low, "distinct surrogate halves must not collapse")

    // '%' itself is escaped, so the escape cannot be forged or
    // confused with literal input: sanitize is injective on the
    // escaped classes.
    val literalPercent = DotnetNameSanitizer.sanitize("a%" + esc(0x00).substring(1) + "b")
    val actualControl = DotnetNameSanitizer.sanitize("a\u0000b")
    assertNotEquals(literalPercent, actualControl, "a literal percent escape must not equal the real control character")
  }

  test("CP-6b: separators never survive as structure; dots and traversal forms are neutralized") {
    assertEquals(DotnetNameSanitizer.sanitize("a/b"), "a" + esc('/') + "b")
    assertEquals(DotnetNameSanitizer.sanitize("a\\b"), "a" + esc('\\') + "b")
    assertEquals(DotnetNameSanitizer.sanitize("a:b"), "a" + esc(':') + "b")
    assertEquals(DotnetNameSanitizer.sanitize("../x"), "_.." + esc('/') + "x")
    assertEquals(DotnetNameSanitizer.sanitize("x/.."), "x" + esc('/')) // trailing dots are stripped
    assertEquals(DotnetNameSanitizer.sanitize(".hidden"), "_.hidden")
    assertEquals(DotnetNameSanitizer.sanitize(".."), "_") // prefix then trailing-dot strip
    assertEquals(DotnetNameSanitizer.sanitize("."), "_")
    // Trailing dots/spaces are stripped (filesystems strip them too).
    assertEquals(DotnetNameSanitizer.sanitize("name."), "name")
    assertEquals(DotnetNameSanitizer.sanitize("name "), "name")
    assertEquals(DotnetNameSanitizer.sanitize("name.. "), "name")
    // Ordinary characters pass through untouched.
    assertEquals(DotnetNameSanitizer.sanitize("Newtonsoft.Json.Tests"), "Newtonsoft.Json.Tests")
    assertEquals(DotnetNameSanitizer.sanitize("RT_VERSION-1-0"), "RT_VERSION-1-0")
    assertEquals(DotnetNameSanitizer.sanitize("certificate-0"), "certificate-0")
  }

  test("CP-6c: the length cap boundary is pinned; truncation is stable and sanitize never empty") {
    val within = "a" * (DotnetNameSanitizer.maxNameUnits - 1)
    val atCap = "a" * DotnetNameSanitizer.maxNameUnits
    val over = "a" * (DotnetNameSanitizer.maxNameUnits + 1)
    assertEquals(DotnetNameSanitizer.sanitize(within).length, DotnetNameSanitizer.maxNameUnits - 1)
    assertEquals(DotnetNameSanitizer.sanitize(atCap).length, DotnetNameSanitizer.maxNameUnits)
    assertEquals(DotnetNameSanitizer.sanitize(over).length, DotnetNameSanitizer.maxNameUnits)
    assertEquals(DotnetNameSanitizer.sanitize(over), DotnetNameSanitizer.sanitize(atCap), "truncation is deterministic")

    // The cap/truncation is stable (an output within the cap
    // re-sanitizes unchanged). The ESCAPE is deliberately not
    // idempotent (injectivity and idempotence are incompatible —
    // see DotnetNameSanitizer's contract note): names are sanitized
    // exactly once, at the source.
    assertEquals(DotnetNameSanitizer.sanitize(DotnetNameSanitizer.sanitize(over)), DotnetNameSanitizer.sanitize(over))
    assertEquals(DotnetNameSanitizer.sanitize("name.. "), "name")
    assertEquals(DotnetNameSanitizer.sanitize(DotnetNameSanitizer.sanitize("name")), "name")
    // Never empty.
    assertEquals(DotnetNameSanitizer.sanitize(""), "_")
    assertEquals(DotnetNameSanitizer.sanitize("   "), "_") // stripped to empty -> fallback
  }

  test("CP-6d: the whole UTF-16 code-unit space sanitizes to safe components (seeded sweep)") {
    var unit = 0
    while (unit < 65536) {
      val name = "x" + unit.toChar + "y"
      val out = DotnetNameSanitizer.sanitize(name)
      assert(safeComponent(out), s"code unit $unit (${f"0x$unit%04x"}) must sanitize to a safe component, got: ${out.map(c => f"${c.toInt}%04x").mkString}")
      val again = DotnetNameSanitizer.sanitize(out)
      assert(safeComponent(again), s"code unit $unit: re-sanitizing must keep the output safe")
      unit += 1
    }
  }
}
