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
  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  private def goldenDumper: String =
    "/work/scripts/GoldenDumper/bin/Release/net8.0/GoldenDumper"

  override def beforeAll(): Unit = {
    // The docker dump reads corpus/bin and corpus/fixtures from the
    // mounted repo: the gate provisions them first (fast path on a
    // full-cache machine, population otherwise).
    CorpusProvisioner.ensureCorpus()
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

  test("C1-07b: committed fixture goldens are reproducible by the pinned dumper") {
    // ADR-0006 commits the fixture oracles (corpus/golden/fixtures/): the
    // hand-authored ilasm/x64 tier dumps (gzip) and the resources/debug
    // oracles (plain JSON). They are authoritative; the pinned dumper must
    // reproduce them byte-for-byte. GoldenWriter emits gzip with a fixed
    // header via .NET GZipStream, so comparing sha256 of the raw files is
    // sound (never re-gzip via CLI).
    val (code, output) = CorpusHelpers.runInFetchImage(
      s"""rm -rf /tmp/fixtures /tmp/out && mkdir -p /tmp/fixtures /tmp/out &&
         |cp /work/corpus/fixtures/*.dll /tmp/fixtures/ &&
         |cat > /tmp/fixmanifest.json <<'EOF'
         |{"schemaVersion":1,"packages":[]}
         |EOF
         |$goldenDumper dump --manifest /tmp/fixmanifest.json --out /tmp/out >/tmp/dump.log 2>&1 &&
         |for pair in ilasm_fixture.tier1 ilasm_fixture.tier2 x64_fixture.tier1 x64_fixture.tier2; do
         |  a=$$(sha256sum /tmp/out/fixtures/$$pair.json | cut -d' ' -f1)
         |  b=$$(sha256sum /work/corpus/golden/fixtures/$$pair.json | cut -d' ' -f1)
         |  [ "$$a" = "$$b" ] || { echo "tier golden differs from committed: $$pair"; exit 1; }
         |done &&
         |$goldenDumper resources --file /work/corpus/fixtures/resources_fixture.dll > /tmp/resources.json &&
         |$goldenDumper debug --file /work/corpus/fixtures/embedded_pdb_fixture.dll > /tmp/debug.json &&
         |sha256sum /tmp/resources.json /work/corpus/golden/fixtures/resources_fixture.resources.json | awk '{print $$1}' | sort -u | wc -l &&
         |sha256sum /tmp/debug.json /work/corpus/golden/fixtures/embedded_pdb_fixture.debug.json | awk '{print $$1}' | sort -u | wc -l
         |""".stripMargin
    )
    assertEquals(code, 0, s"fixture reproduction failed:\n$output")
    assertEquals(
      output.trim.linesIterator.toSeq.takeRight(2),
      Seq("1", "1"),
      s"committed oracles differ from the pinned dumper output:\n$output"
    )
  }
}
