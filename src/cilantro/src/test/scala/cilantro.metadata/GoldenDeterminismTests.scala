// GoldenDeterminismTests — C1-07.
//
// Why this test exists:
//   Goldens are the oracle the parity harness diff against. Two runs of the
//   helper must produce byte-identical output (stable ordering, no
//   timestamps, no absolute paths, culture-invariant formatting); otherwise
//   every diff is noise. The plan (doc 01) pins determinism with a
//   byte-identical two-run comparison.
//
// Theory of the test:
//   A small sub-manifest is written into the container referencing four
//   diverse assemblies (old-TFM, F#, unsafe, mixed-mode). The dump command
//   runs twice into two output directories; the file trees must be
//   byte-identical (names, sizes and sha256s).
//
// LLM-friendly notes:
//   - Runs in docker (house rule); fails loudly when docker is missing.
//   - The sub-manifest is JSON written from the test to keep the test
//     self-contained and fast (the full corpus dump is exercised by the
//     fetch script; determinism over the full corpus follows from
//     determinism of the same code path).

package io.spicelabs.cilantro.metadata

class GoldenDeterminismTests extends munit.FunSuite {

  private def goldenDumper: String =
    "/work/scripts/GoldenDumper/bin/Release/net8.0/GoldenDumper"

  override def beforeAll(): Unit = {
    if (!CorpusHelpers.dockerAvailable()) {
      fail("docker is required for the golden-determinism test (house rule)")
    }
    CorpusHelpers.ensureFetchImage()
    CorpusHelpers.ensureGoldenDumperBuilt()
  }

  test("C1-07: two dump runs are byte-identical") {
    val (dumpCode, dumpOutput) = CorpusHelpers.runInFetchImage(
      """cat > /tmp/submanifest.json <<'EOF'
        |{
        |  "schemaVersion": 1,
        |  "packages": [
        |    { "id": "sub", "version": "1", "mixedMode": false,
        |      "assemblies": [
        |        { "path": "/work/corpus/bin/Newtonsoft.Json/12.0.3/net20/Newtonsoft.Json.dll" },
        |        { "path": "/work/corpus/bin/Newtonsoft.Json/12.0.3/net45/Newtonsoft.Json.dll" },
        |        { "path": "/work/corpus/bin/FSharp.Core/8.0.400/netstandard2.0/FSharp.Core.dll" },
        |        { "path": "/work/corpus/bin/SixLabors.ImageSharp/3.1.6/net6.0/SixLabors.ImageSharp.dll" }
        |      ]
        |    },
        |    { "id": "sub-mixed", "version": "1", "mixedMode": true,
        |      "assemblies": [
        |        { "path": "/work/corpus/bin/Stub.System.Data.SQLite.Core.NetFramework/1.0.119/net46/System.Data.SQLite.dll" }
        |      ]
        |    }
        |  ]
        |}
        |EOF
        |""".stripMargin +
      s"rm -rf /tmp/goldenA /tmp/goldenB && " +
        s"$goldenDumper dump --manifest /tmp/submanifest.json --out /tmp/goldenA && " +
        s"$goldenDumper dump --manifest /tmp/submanifest.json --out /tmp/goldenB && " +
        "find /tmp/goldenA -type f | sed 's|/tmp/goldenA/||' | sort > /tmp/listA && " +
        "find /tmp/goldenB -type f | sed 's|/tmp/goldenB/||' | sort > /tmp/listB && " +
        "diff /tmp/listA /tmp/listB && " +
        "cd /tmp && for f in $(cat /tmp/listA); do " +
        "sha256sum goldenA/$f goldenB/$f | awk '{print $1}' | sort -u | wc -l; done | grep -v '^1$' | wc -l"
    )
    assertEquals(dumpCode, 0, s"dump/diff failed:\n$dumpOutput")
    assertEquals(
      dumpOutput.trim.linesIterator.toSeq.last,
      "0",
      s"goldens are not byte-identical between runs:\n$dumpOutput"
    )
  }
}
