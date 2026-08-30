// ExtractionSafetyTests — C1-05.
//
// Why this test exists:
//   The corpus contains third-party nupkgs; a hostile package is data, but
//   it must never escape the cache directory during extraction. The plan
//   (doc 01) requires an entry-safe unzip that rejects absolute paths,
//   `..` segments, backslash paths, symlink/hardlink entries and absurd
//   sizes. This test drives the real extractor (scripts/GoldenDumper,
//   inside the pinned docker image) against synthetic nupkgs and asserts
//   both the rejection behavior and the benign behavior.
//
// Theory of the test:
//   - make-hostile writes a hand-crafted zip containing a parent-traversal
//     entry, an absolute entry, a backslash entry and a symlink entry plus
//     one benign DLL. Extraction must fail (non-zero exit) and write
//     nothing outside the output directory.
//   - make-benign writes a normal nupkg; extraction must succeed and
//     produce exactly the lib DLLs.
//   - Docker is required by house rule; a missing docker fails the test
//     loudly rather than skipping.

package io.spicelabs.cilantro.metadata


class ExtractionSafetyTests extends munit.FunSuite {

  private def goldenDumper: String =
    "/work/scripts/GoldenDumper/bin/Release/net8.0/GoldenDumper"

  override def beforeAll(): Unit = {
    if (!CorpusHelpers.dockerAvailable()) {
      fail("docker is required for the extraction-safety test (house rule)")
    }
    CorpusHelpers.ensureFetchImage()
    CorpusHelpers.ensureGoldenDumperBuilt()
  }

  private val vectors = Seq("traversal", "absolute", "backslash", "symlink")

  test("C1-05: hostile nupkg is rejected and nothing escapes") {
    vectors.foreach { vector =>
      val (code, output) = CorpusHelpers.runInFetchImage(
        s"$goldenDumper make-hostile --output /tmp/hostile-$vector.nupkg --vector $vector && " +
          s"rm -rf /tmp/escape-out /tmp/escape-parent && mkdir -p /tmp/escape-parent && " +
          s"$goldenDumper extract --nupkg /tmp/hostile-$vector.nupkg --out /tmp/escape-parent/out; " +
          "echo EXTRACT_EXIT=$?; find /tmp/escape-parent /tmp/escape-out -type f 2>/dev/null | wc -l"
      )
      assert(
        output.contains("hostile entry"),
        s"vector $vector: rejection reason must mention the hostile entry:\n$output"
      )
      assert(
        output.contains("EXTRACT_EXIT=1"),
        s"vector $vector: hostile nupkg must be rejected:\n$output"
      )
      assert(
        output.linesIterator.exists(_.trim == "0"),
        s"vector $vector: hostile extraction must write nothing:\n$output"
      )
    }
  }

  test("C1-05: benign nupkg extracts only its lib DLLs") {
    val (code, output) = CorpusHelpers.runInFetchImage(
      s"$goldenDumper make-benign --output /tmp/benign.nupkg && " +
        s"rm -rf /tmp/benign-out && $goldenDumper extract --nupkg /tmp/benign.nupkg --out /tmp/benign-out && " +
        "find /tmp/benign-out -type f | sort"
    )
    assertEquals(code, 0, s"benign nupkg must extract:\n$output")
    val lines = output.linesIterator.toSeq
    val extracted = lines.filter(_.startsWith("/tmp/benign-out"))
    assertEquals(
      extracted.sorted,
      Seq(
        "/tmp/benign-out/net20/Good.dll",
        "/tmp/benign-out/net45/Good.dll"
      )
    )
  }

  test("C1-05: extractor rejects a nonexistent nupkg") {
    val (code, output) = CorpusHelpers.runInFetchImage(
      s"$goldenDumper extract --nupkg /tmp/does-not-exist.nupkg --out /tmp/nope; echo E=" + "$?"
    )
    assert(output.contains("E=1"), s"missing nupkg must fail:\n$output")
  }
}
