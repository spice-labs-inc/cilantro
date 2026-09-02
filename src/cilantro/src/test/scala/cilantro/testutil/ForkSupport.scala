// ForkSupport — spawns ForkedHelper in a child JVM with a bounded
// heap and extra flags. Used by the CP-1/CP-3/CP-4 bounded-heap
// proofs (plan 2026_09_02, phase B): a child heap of 32m makes any
// whole-payload materialization or hostile-driven allocation fail
// the child, which the parent asserts against.

package io.spicelabs.cilantro.testutil

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object ForkSupport {

    // Returns (exitCode, combined stdout+stderr).
    def runForked(
        args: List[String],
        maxHeap: String = "32m",
        extraJvmArgs: List[String] = Nil,
        timeoutSeconds: Long = 120
    ): (Int, String) = {
        val javaBin = new File(System.getProperty("java.home"), "bin/java").getAbsolutePath
        val classpath = System.getProperty("java.class.path")
        val cmd = List(javaBin, s"-Xmx$maxHeap", "-Xss8m") ++
            extraJvmArgs ++
            List("-cp", classpath, "io.spicelabs.cilantro.testutil.ForkedHelper") ++
            args
        val pb = new ProcessBuilder(cmd*)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            sys.error(s"forked helper timed out: ${args.mkString(" ")}")
        }
        (proc.exitValue(), out)
    }
}
