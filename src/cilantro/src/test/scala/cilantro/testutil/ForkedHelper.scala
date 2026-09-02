// ForkedHelper — a test-scope main launched by tests in a child JVM
// with a bounded heap (-Xmx32m) to prove behavioral properties that
// in-process assertions cannot: that payload reads never materialize
// whole payloads on the heap, that refusals precede allocation, and
// that the probe never touches temp state.
//
// Scenarios (argv(0)):
//   "win32hash <file> [leafIndex]"
//       read the given win32 leaf's payload fully and print its
//       sha256 (streamed in 64 KiB chunks — the bounded heap proves
//       no whole-payload materialization).
//   "probe <file>"
//       run DotnetAssemblyProbe.isDotnetAssembly over the File
//       overload; print TRUE/FALSE. The parent launches this with
//       -Djava.io.tmpdir pointing at a nonexistent directory, so any
//       temp-file creation would fail the child with an exception.
//   "certrefuse <file>"
//       run readCertificateEntries inside a Try; print "REFUSED" if
//       the call failed (clean refusal), "OK:<count>" otherwise.
//       The bounded heap proves refusals precede any hostile-driven
//       allocation (a regression that allocated would OOM the child).
//
// Exit code 0 on success, 1 on any unexpected exception or wrong
// scenario usage. Output is one line on stdout.

package io.spicelabs.cilantro.testutil

import io.spicelabs.cilantro._
import scala.util.{Success, Failure}

object ForkedHelper {

    def main(args: Array[String]): Unit = {
        val code = args.toList match {
            case "win32hash" :: file :: rest =>
                val leaf = rest.headOption.map(_.toInt).getOrElse(0)
                win32Hash(file, leaf)
            case "probe" :: file :: Nil =>
                val result = DotnetAssemblyProbe.isDotnetAssembly(new java.io.File(file))
                println(if (result) "TRUE" else "FALSE")
                0
            case "certrefuse" :: file :: Nil =>
                certRefuse(file)
            case "walk" :: file :: Nil =>
                walkAssembly(file)
            case _ =>
                println("USAGE")
                1
        }
        sys.exit(code)
    }

    private def win32Hash(file: String, leafIndex: Int): Int = {
        val outcome: scala.util.Try[Vector[Win32Resource]] = ModuleDefinition.readModule(file).flatMap { module =>
            scala.util.Try {
                module.read(Vector.empty[Win32Resource], (_, reader: MetadataReader) => {
                    val b = Vector.newBuilder[Win32Resource]
                    reader.readWin32Resources().foreach(r => b += r)
                    b.result()
                })
            }
        }
        outcome match {
            case Failure(t) =>
                println("ERROR " + t.getClass.getName + ":" + Option(t.getMessage).getOrElse(""))
                1
            case Success(entries) =>
                if (entries.isEmpty || leafIndex >= entries.length) {
                    println("ERROR no such win32 leaf")
                    1
                }
                else {
                    entries(leafIndex).processStream { in =>
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        val buf = new Array[Byte](65536)
                        var n = in.read(buf)
                        var total = 0L
                        while (n >= 0) {
                            if (n > 0) {
                                md.update(buf, 0, n)
                                total += n
                            }
                            n = in.read(buf)
                        }
                        println(md.digest().map(b => f"${b & 0xff}%02x").mkString + ":" + total)
                    }
                    0
                }
        }
    }

    private def walkAssembly(file: String): Int = {
        AssemblyWalker.withinAssemblyStream[Int](new java.io.File(file))(_ => 0)(None) match {
            case None => println("WALK-NONE")
            case Some(_) => println("WALK-SOME")
        }
        0
    }

    private def certRefuse(file: String): Int = {
        val outcome: scala.util.Try[Int] = ModuleDefinition.readModule(file).flatMap { module =>
            scala.util.Try {
                module.read(Vector.empty[CertificateEntry], (_, reader: MetadataReader) => {
                    val entries = reader.readCertificateEntries()
                    entries.length
                })
            }
        }
        outcome match {
            case Success(count) =>
                println("OK:" + count)
                0
            case Failure(_) =>
                println("REFUSED")
                0
        }
    }
}
