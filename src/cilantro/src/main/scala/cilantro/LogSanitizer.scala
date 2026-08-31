// LogSanitizer — hostile metadata names must never control terminal/log output.
//
// Why this exists:
//   Assembly/type/member names are attacker-controlled bytes. A hostile
//   assembly can carry names containing ESC sequences (`\x1b[2J` clears
//   the screen), bidi overrides (U+202E flips displayed text), or other
//   control characters. Any log line interpolating such a name could
//   inject terminal commands or visually spoof output. Every line that
//   carries corpus-controlled text must be passed through this
//   sanitizer (C4-08 pins the behavior).
//
// Theory:
//   Printable ASCII and normal whitespace pass through. Control
//   characters (< 0x20) except \n and \t are replaced with the U+FFFD
//   replacement character; ESC (0x1b), DEL (0x7f), and the Unicode bidi
//   override/isolate characters are replaced as well. Non-ASCII
//   printable characters are preserved. The result is a string that
//   cannot move the cursor, change terminal state, or reorder displayed
//   text.
//
// Requirements traced:
//   04_parity_harness_and_security_caps.md — Log sanitization: "Every
//   log line interpolating corpus data passes a sanitizer (strip/replace
//   < 0x20 except \n/\t, strip ESC and bidi overrides), pinned by a test
//   feeding `\x1b[2J`/`\u202E` names."

package io.spicelabs.cilantro

object LogSanitizer {
    private val replacement = '\uFFFD'

    def sanitize(value: String): String = {
        val builder = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value.charAt(i)
            if (isHostile(c)) {
                builder.append(replacement)
            }
            else {
                builder.append(c)
            }
            i += 1
        }
        builder.toString()
    }

    private def isHostile(c: Char): Boolean = {
        if (c < 0x20) {
            c != '\n' && c != '\t'
        }
        else {
            c == 0x7f || c == '\u202A' || c == '\u202B' || c == '\u202C' ||
            c == '\u202D' || c == '\u202E' || c == '\u2066' || c == '\u2067' ||
            c == '\u2068' || c == '\u2069'
        }
    }
}
