// DotnetNameSanitizer — cilantro-defined entry-name hardening
// (plan 2026_09_02, phase C; ADR-0014, D-5).
//
// Applied at the source to every entry name (class fullNames, resource
// names, win32 composite names, debug-type names, later document
// names) so every consumer sees hardened names. The contract is
// deterministic and pure — same input, same output, no state:
//
//   1. Escape class: control characters (C0 U+0000..U+001F, C1
//      U+007F..U+009F), format/bidi controls (U+200E, U+200F,
//      U+202A..U+202E), the path separators '/' '\\' and ':' , and
//      '%' itself, are each replaced by a percent-style escape
//      ("%" + 2 lowercase hex digits of the UTF-16 code unit). The
//      mapping is injective over the escaped classes: two distinct
//      hostile names cannot collapse onto one output through the
//      escape, and later UTF-8 re-encoding cannot merge them (lone
//      surrogates escape as their code-unit value, never as U+FFFD).
//   2. Leading-dot neutralization: an output that starts with '.'
//      gets a '_' prefix (no hidden files / no '.' '..' forms at the
//      head).
//   3. Trailing dots and spaces are stripped (filesystems that strip
//      them would otherwise collapse distinct names).
//   4. Length cap: at most maxNameUnits (255) UTF-16 code units,
//      truncated deterministically. Truncation can collide shared
//      prefixes — accepted and documented: sanitized names are NOT
//      guaranteed unique (CP-2e(6) pins the guarantee's absence).
//      The truncation itself is stable (re-sanitizing an output that
//      is already within the cap changes nothing).
//   5. Never empty: an empty input yields "_" (the per-kind display
//      prefixes used by the walk make collisions across kinds
//      practically moot; uniqueness is never promised).
//
// Note on injectivity vs idempotence: escaping '%' makes the escape
// injective, which is the security property (distinct hostile names
// cannot collapse). Injectivity and whole-string idempotence are
// mathematically incompatible (an injective endomorphism that fixes
// its image is the identity), so the escape is injective but NOT
// idempotent; names are sanitized exactly once, at the source.
//
// Threat model scope: this hardens names for safe single-component
// use (no path structure, no control characters, bounded length,
// stable under encoding). Platform-reserved device names (CON, NUL,
// ...) are out of scope — artifact-level policy stays in Goat Rodeo.

package io.spicelabs.cilantro

object DotnetNameSanitizer {

    val maxNameUnits: Int = 255

    private val hex = "0123456789abcdef".toCharArray

    private def escapeUnit(c: Char): String = {
        if (c < 0x100) {
            // Two hex digits for the byte range.
            "%" + hex((c >> 4) & 0x0f) + hex(c & 0x0f)
        }
        else {
            // Four hex digits for the full UTF-16 code unit.
            "%" + hex((c >> 12) & 0x0f) + hex((c >> 8) & 0x0f) + hex((c >> 4) & 0x0f) + hex(c & 0x0f)
        }
    }

    private def mustEscape(c: Char): Boolean = {
        c < 0x20 ||
        (c >= 0x7f && c <= 0x9f) ||
        c == 0x200e ||
        c == 0x200f ||
        (c >= 0x202a && c <= 0x202e) ||
        (c >= 0xd800 && c <= 0xdfff) || // lone surrogates: escape, never U+FFFD
        c == '/' ||
        c == '\\' ||
        c == ':' ||
        c == '%'
    }

    def sanitize(name: String): String = {
        val escaped = new StringBuilder(name.length + 8)
        var i = 0
        while (i < name.length) {
            val c = name.charAt(i)
            if (mustEscape(c)) {
                escaped.append(escapeUnit(c))
            }
            else {
                escaped.append(c)
            }
            i += 1
        }
        var result = escaped.toString
        if (result.nonEmpty && result.charAt(0) == '.') {
            result = "_" + result
        }
        var end = result.length
        while (end > 0 && (result.charAt(end - 1) == '.' || result.charAt(end - 1) == ' ')) {
            end -= 1
        }
        if (end < result.length) {
            result = result.substring(0, end)
        }
        if (result.length > maxNameUnits) {
            result = result.substring(0, maxNameUnits)
        }
        if (result.isEmpty) {
            "_"
        }
        else {
            result
        }
    }
}
