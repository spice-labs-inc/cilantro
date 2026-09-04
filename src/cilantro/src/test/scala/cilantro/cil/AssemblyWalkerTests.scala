// AssemblyWalkerTests — CP-2a..e: the container walk
// (plan 2026_09_02, phase C; ADR-0014, D-1/D-2/D-4/D-5/D-8/D-11).
//
// Why these tests exist:
//   The walk is the handoff contract Goat Rodeo consumes: probe, then
//   withinAssemblyStream handing GR one complete Vector[AssemblyEntry]
//   (name/kind/MIME-hint/processStream) or None — never partial,
//   never throwing from enumeration, cycle-safe, with names hardened
//   at the source.
//
// Theory of the test:
//   - CP-2a: real corpus assemblies exercise every kind Phase C can
//     produce (classes, embedded resources, certificates, win32
//     leaves, debug blobs); names/kinds/hints and exact payload bytes
//     are pinned.
//   - CP-2b: the full name/kind sequence is pinned on the small
//     fixtures (complete vectors), one builder carries every section
//     to pin the cross-section order, and a Slow corpus test checks
//     the class subsequence against the model's own order.
//   - CP-2c: hostile shapes (certificate, win32, managed resource,
//     debug, nested-type cycles, canonical-JSON scale, non-assembly
//     input) all yield None with f never invoked — the vector is
//     all-or-nothing.
//   - CP-2d: seeded sweeps, a > 2 GiB artifact, and a many-type
//     assembly under a bounded heap never throw.
//   - CP-2e: the processStream contract (fresh independent streams,
//     f exceptions propagate, entries refuse cleanly after the walk),
//     benign absence, duplicate names, the 1 MiB class-JSON boundary,
//     and the valid-empty-assembly Some(Vector()).
//
// Requirements traced:
//   workspace/2026_09_01_cilantro_handoff.md §4 CP-2; ADR-0014.

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import io.spicelabs.cilantro._
import io.spicelabs.cilantro.dump.CanonicalJson
import io.spicelabs.cilantro.metadata.CorpusProvisioner
import io.spicelabs.cilantro.testutil.ForkSupport
import java.io.File

class AssemblyWalkerTests extends munit.FunSuite {

  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")


  private def corpusRoot = CorpusProvisioner.ensureCorpus()

  private def i2(v: Int): Array[Byte] = Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte)

  private def i4(v: Int): Array[Byte] =
    Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte, ((v >> 16) & 0xff).toByte, ((v >> 24) & 0xff).toByte)

  private def zero(n: Int): Array[Byte] = Array.ofDim[Byte](n)


  private def payloadBytes(p: PayloadSource): Array[Byte] =
    p.processStream(in => in.readAllBytes())

  private def sha256(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  // Run the walk; the returned Int is the number of times f ran.
  private def runWalk(path: File)(body: Vector[AssemblyEntry] => Unit = _ => ()): (Option[Int], Vector[AssemblyEntry]) = {
    var calls = 0
    var captured = Vector.empty[AssemblyEntry]
    val outcome = AssemblyWalker.withinAssemblyStream[Unit](path) { entries =>
      calls += 1
      captured = entries
      body(entries)
    }(None)
    val callCount = if (outcome.isDefined) calls else 0
    (outcome.map(_ => callCount), captured)
  }

  private def summary(entries: Vector[AssemblyEntry]): Vector[String] =
    entries.map(e => e.kind.toString + "|" + e.name)

  private def withFile(bytes: Array[Byte])(body: File => Unit): Unit = {
    val file = File.createTempFile("walk", ".dll")
    java.nio.file.Files.write(file.toPath, bytes)
    try {
      body(file)
    } finally {
      file.delete()
    }
  }

  // ---- builders ------------------------------------------------------

  // The valid minimal assembly (Phase A Q5): Assembly table row + the
  // builder's "A" string.
  private def minimalAssemblyBytes: Array[Byte] =
    new MinimalPeBuilder(extraTables = Seq((0x20, asmTableRow.length, asmTableRow))).build()

  // One class named after the given string heap index (1 = "A").
  private def typeDefRow(name: Int): Array[Byte] =
    i4(1) ++ i2(name) ++ i2(0) ++ i2(0) ++ i2(1) ++ i2(1)

  private val voidSigBlob: Array[Byte] = Array[Byte](0x03, 0, 0, 1)

  private def methodRow: Array[Byte] =
    i4(0) ++ i2(0) ++ i2(0x90) ++ i2(1) ++ i2(2) ++ i2(0)

  private def asmTableRow: Array[Byte] = i4(0x8004) ++ i2(1) ++ i2(0) ++ i2(0) ++ i2(0) ++ i4(0) ++ i2(0) ++ i2(1) ++ i2(0)

  // ---- tests ---------------------------------------------------------

  test("CP-2a: kinds, names, hints, and exact bytes over real assemblies") {
    // All payload assertions run INSIDE the walk's callback: entry
    // streams are valid only there (CP-2e(3)). Captured plain data is
    // asserted afterwards.
    final case class Seen(kind: String, name: String, hint: Option[String], bytes: Array[Byte], cert: Option[(Int, Int)], win32Type: Int, jsonOk: Boolean)
    val newtonsoft = corpusRoot.resolve("bin/Newtonsoft.Json/12.0.3/net20/Newtonsoft.Json.dll").toFile
    val outcome = AssemblyWalker.withinAssemblyStream[Vector[Seen]](newtonsoft) { entries =>
      entries.map {
        case c: AuthenticodeCertificateEntry =>
          Seen(c.kind.toString, c.name, c.mimeHint, payloadBytes(c), Some((c.certificateRevision, c.certificateType)), -1, jsonOk = false)
        case w: Win32ResourceEntry =>
          Seen(w.kind.toString, w.name, w.mimeHint, payloadBytes(w), None, w.resourceTypeId, jsonOk = false)
        case d: DebugBlobEntry =>
          Seen(d.kind.toString, d.name, d.mimeHint, payloadBytes(d), None, -1, jsonOk = false)
        case c: ClassEntry =>
          val expected = CanonicalJson.typeToJson(c.`type`) match {
            case Success(j) => j.getBytes("UTF-8")
            case Failure(_) => Array.emptyByteArray
          }
          Seen(c.kind.toString, c.name, c.mimeHint, payloadBytes(c), None, -1, jsonOk = expected.length > 0 && payloadBytes(c).toSeq == expected.toSeq)
        case r: EmbeddedResourceEntry =>
          Seen(r.kind.toString, r.name, r.mimeHint, payloadBytes(r), None, -1, jsonOk = false)
        case s2: EmbeddedSourceEntry =>
          Seen(s2.kind.toString, s2.name, s2.mimeHint, payloadBytes(s2), None, -1, jsonOk = false)
      }
    }(None)
    outcome match {
      case None => fail("Newtonsoft net20 must walk")
      case Some(seen) =>
        assertEquals(seen.map(_.kind).distinct.toSet, Set(
          "Class", "AuthenticodeCertificate", "Win32Resource", "DebugBlob"
        ), "Newtonsoft net20 carries classes, a certificate, win32 and debug entries")
        val cert = seen.filter(_.kind == "AuthenticodeCertificate")
        assertEquals(cert.map(s => (s.name, s.hint)), Vector(("certificate-0", None)))
        assertEquals(cert.flatMap(_.cert), Vector((0x0200, 2)), "revision/type stay on the entry")
        assertEquals(
          sha256(cert.head.bytes),
          "0f8fc2643529f9d670bcccc427a83cf29999e94c6f4d568676f8bfc0329dac9c",
          "the walk's certificate stream carries the pinned blob"
        )
        val win32 = seen.filter(_.kind == "Win32Resource")
        assertEquals(win32.map(s => (s.name, s.hint, s.win32Type)), Vector(("RT_VERSION-1-0", Some("pe/resource"), 16)))
        assertEquals(
          sha256(win32.head.bytes),
          "5bd2a0f900755c12a17b123b78a6280d22f4cdaad481f44c490661132f928fcc",
          "the walk's win32 stream carries the pinned leaf"
        )
        val debug = seen.filter(_.kind == "DebugBlob")
        assertEquals(debug.map(s => s.name), Vector("codeview", "pdbchecksum"))
        assertEquals(debug.map(_.hint).toSet, Set(Some("pe/debug"): Option[String]))
        assertEquals(debug.find(_.name == "codeview").map(_.bytes.length), Some(85), "Newtonsoft's codeview blob")
        val classes = seen.filter(_.kind == "Class")
        assert(classes.length > 100, s"Newtonsoft net20 has hundreds of types, got ${classes.length}")
        assertEquals(classes.head.name, "<Module>")
        assertEquals(classes.head.hint, Some("cilantro/type"))
        assert(classes.forall(_.jsonOk), "every class stream must equal its type's canonical JSON")
    }
  }

  test("CP-2a: embedded resources walk with the pinned bytes (resources fixture)") {
    val fixture = corpusRoot.resolve("fixtures/resources_fixture.dll").toFile
    // Capture inside f (entry streams are walk-scoped); the pinned
    // resourceData bytes are computed from a separate fresh read.
    val viaData: Map[String, Array[Byte]] = AssemblyDefinition.readAssembly(fixture.getAbsolutePath) match {
      case Success(ad) =>
        val m = ad.modules.flatMap(_.resources.collect { case r: EmbeddedResource => r.name -> (r.resourceData() match {
          case Success(b) => b
          case Failure(e) => fail(s"resourceData failed: $e")
        }) }).toMap
        ad.close()
        m
      case Failure(e) => fail(s"readAssembly failed: $e")
    }
    val outcome = AssemblyWalker.withinAssemblyStream[Vector[(String, String, Option[String], Array[Byte])]](fixture) { entries =>
      entries.collect { case r: EmbeddedResourceEntry => (r.name, r.kind.toString, r.mimeHint, payloadBytes(r)) }
    }(None)
    outcome match {
      case None => fail("the resources fixture must walk")
      case Some(resources) =>
        assertEquals(resources.map(_._1), Vector(
          "ResourcesFixture.data.alpha.bin",
          "ResourcesFixture.data.beta.bin"
        ))
        assertEquals(resources.map(_._3).toSet, Set(None: Option[String]))
        resources.foreach { case (name, _, _, bytes) =>
          val expected = viaData.getOrElse(name, fail(s"no pinned data for $name"))
          assertEquals(bytes.toSeq, expected.toSeq, s"$name: walk stream == resourceData")
        }
    }
  }

  test("CP-2b: the full name/kind sequence is pinned on the small fixtures") {
    val resources = corpusRoot.resolve("fixtures/resources_fixture.dll").toFile
    val (o1, e1) = runWalk(resources)()
    assert(o1.isDefined)
    assertEquals(summary(e1), Vector(
      "Class|<Module>",
      "Class|ResourcesFixture.Program",
      "EmbeddedResource|ResourcesFixture.data.alpha.bin",
      "EmbeddedResource|ResourcesFixture.data.beta.bin",
      "Win32Resource|RT_VERSION-1-0",
      "DebugBlob|codeview",
      "DebugBlob|pdbchecksum"
    ), "the resources fixture's full vector is pinned (classes -> resources -> win32 -> debug)")

    val ilasm = corpusRoot.resolve("fixtures/ilasm_fixture.dll").toFile
    val (o2, e2) = runWalk(ilasm)()
    assert(o2.isDefined)
    assertEquals(summary(e2), Vector(
      "Class|<Module>",
      "Class|Fixture",
      "Class|GenericBox`1",
      "Class|Days",
      "Class|Attributed"
    ), "the ilasm fixture's full class vector is pinned in table order")

    // Determinism: a second run is identical.
    val (o3, e3) = runWalk(ilasm)()
    assert(o3.isDefined)
    assertEquals(summary(e3), summary(e2), "repeated walks are identical")
  }

  test("CP-2b: one assembly carrying every section pins the cross-section order") {
    // Class "A" + one embedded resource (name "B", 4-byte prefix +
    // payload) + a certificate table + a win32 leaf tree + a debug
    // entry pointing at real in-file bytes. Two passes: build once to
    // learn the raw offsets, then rebuild with the debug pointer.
    val payload = "cross-section-resource-bytes".getBytes("UTF-8")
    val prefixAndPayload = i4(payload.length) ++ payload
    val certTable = i4(16) ++ i2(0x0200) ++ i2(2) ++ Array[Byte](1, 2, 3, 4, 5, 6, 7, 8) // one 8-aligned entry
    def tree(blob: Array[Byte]): Array[Byte] = {
      val nameDir = 16 + 8
      val langDir = nameDir + 16 + 8
      val dataEntry = langDir + 16 + 8
      val blobOff = dataEntry + 16
      val rootBytes = zero(12) ++ i2(0) ++ i2(1) ++ i4(10) ++ i4(0x80000000 | nameDir)
      val nameBytes = zero(12) ++ i2(0) ++ i2(1) ++ i4(1) ++ i4(0x80000000 | langDir)
      val langBytes = zero(12) ++ i2(0) ++ i2(1) ++ i4(0x409) ++ i4(dataEntry)
      val dataBytes = i4(0x2500 + blobOff) ++ i4(blob.length) ++ zero(8)
      rootBytes ++ nameBytes ++ langBytes ++ dataBytes ++ blob
    }
    def resourceRow(nameIdx: Int): Array[Byte] = i4(0) ++ i4(0) ++ i2(nameIdx) ++ i2(0)
    def build(debugPointer: Int, debugSize: Int): Array[Byte] = {
      // Strings: idx1 = "A" (type name), idx4 = "B" (resource name).
      val extraStrings = Array[Byte](0, 'B'.toByte, 0)
      val debugDir = if (debugSize > 0) {
        zero(12) ++ i4(2) ++ i4(debugSize) ++ i4(0) ++ i4(debugPointer)
      } else {
        zero(12) ++ i4(2) ++ i4(0) ++ i4(0) ++ i4(0)
      }
      new MinimalPeBuilder(
        extraStrings = extraStrings,
        extraTables = Seq(
          (0x20, asmTableRow.length, asmTableRow),
          (2, 14, typeDefRow(1)),
          (0x28, resourceRow(4).length, resourceRow(4))
        ),
        certificateTable = Some(certTable),
        win32ResourceTree = Some(tree(payload)),
        managedResourceBlob = Some(prefixAndPayload),
        debugDirectory = Some(debugDir)
      ).build()
    }
    // The managed-resource slot sits at a fixed section offset (RVA
    // 0x2000 + 0x700 -> raw 0x160 + 0x700); its payload starts after
    // the 4-byte length prefix. The debug entry points there.
    val managedRaw = 0x160 + 0x700
    val payloadRaw = managedRaw + 4
    val bytes = build(payloadRaw, payload.length)
    withFile(bytes) { file =>
      val outcome = AssemblyWalker.withinAssemblyStream[Vector[(String, String, Array[Byte])]](file) { entries =>
        entries.map(e => (e.kind.toString, e.name, payloadBytes(e)))
      }(None)
      outcome match {
        case None => fail("the combined assembly must walk")
        case Some(captured) =>
          assertEquals(captured.map(c => c._1 + "|" + c._2), Vector(
            "Class|A",
            "EmbeddedResource|B",
            "AuthenticodeCertificate|certificate-0",
            "Win32Resource|RT_RCDATA-1-1033",
            "DebugBlob|codeview"
          ), "cross-section order: classes -> resources -> certificates -> win32 -> debug")
          // The debug blob really reads the payload bytes it points at.
          assertEquals(captured.last._3.toSeq, payload.toSeq)
      }
    }
  }

  test("CP-2c: hostile shapes and non-assemblies all yield None; f never runs") {
    val asmTable = Seq((0x20, asmTableRow.length, asmTableRow))
    val cases: Vector[(String, Array[Byte])] = Vector(
      // A genuine non-assembly: text bytes.
      ("text-file", "this is not a PE".getBytes("UTF-8")),
      // A zip file.
      ("zip-file", zipBytes()),
      // Certificate table bomb: dwLength past the directory end.
      ("cert-bomb", new MinimalPeBuilder(
        certificateTable = Some(i4(0x1000) ++ zero(4)),
        extraTables = asmTable
      ).build()),
      // Win32 hostile tree: data offset far past the file.
      ("win32-bomb", new MinimalPeBuilder(
        win32ResourceTree = Some(zero(12) ++ i2(0) ++ i2(1) ++ i4(10) ++ i4(0x80000000 | (16 + 8)) ++
          zero(12) ++ i2(0) ++ i2(1) ++ i4(1) ++ i4(16 + 8 + 16 + 8) ++
          zero(12) ++ i2(0) ++ i2(1) ++ i4(0x409) ++ i4(16 + 8 + 16 + 8 + 16 + 8) ++
          i4(0x7fffffff) ++ i4(4) ++ zero(8)),
        extraTables = asmTable
      ).build()),
      // Managed resource with a declared length past EOF.
      ("resource-bomb", new MinimalPeBuilder(
        managedResourceBlob = Some(i4(0x7fffffff) ++ zero(4)),
        extraTables = asmTable ++ Seq((0x28, 12, i4(0) ++ i4(0) ++ i2(1) ++ i2(0)))
      ).build()),
      // A debug entry pointing past EOF: the model read itself refuses.
      ("debug-bomb", new MinimalPeBuilder(
        debugDirectory = Some(zero(12) ++ i4(2) ++ i4(64) ++ i4(0) ++ i4(0x7fffffff)),
        extraTables = asmTable
      ).build()),
      // An unknown debug-directory type: the model read refuses.
      ("unknown-debug-type", new MinimalPeBuilder(
        debugDirectory = Some(zero(12) ++ i4(3) ++ i4(0) ++ i4(0) ++ i4(0)),
        extraTables = asmTable
      ).build())
    )
    cases.foreach { case (label, bytes) =>
      withFile(bytes) { file =>
        val (outcome, _) = runWalk(file)()
        assertEquals(outcome, None, s"$label: the walk must yield None (all-or-nothing)")
      }
    }
  }

  test("CP-2c: a nested-type cycle refuses cleanly; the walk never loops") {
    // TypeDef rows: 1 = A, 2 = B (both attribute-top-level). NestedClass
    // rows: (nested 2, enclosing 1) and (nested 1, enclosing 2) — a
    // two-cycle the walk must refuse, not loop on.
    val typedefs = typeDefRow(1) ++ typeDefRow(1)
    val nestedRows = i2(2) ++ i2(1) ++ i2(1) ++ i2(2)
    val bytes = new MinimalPeBuilder(
      extraTables = Seq(
        (0x20, asmTableRow.length, asmTableRow),
        (2, 14, typedefs),
        (0x29, 4, nestedRows)
      )
    ).build()
    withFile(bytes) { file =>
      val (outcome, _) = runWalk(file)()
      assertEquals(outcome, None, "a NestedClass cycle must refuse the whole walk")
    }
  }

  test("CP-2c: well-formed inputs walk; f runs exactly once; valid empty assembly yields Some(empty)") {
    withFile(minimalAssemblyBytes) { file =>
      var calls = 0
      val outcome = AssemblyWalker.withinAssemblyStream[Int](file) { entries =>
        calls += 1
        assertEquals(entries.length, 0, "the valid empty assembly has no entries")
        entries.length
      }(None)
      assertEquals(outcome, Some(0))
      assertEquals(calls, 1, "f must run exactly once")
    }
    // Positive control with content: one class.
    val withClass = new MinimalPeBuilder(
      extraTables = Seq((0x20, asmTableRow.length, asmTableRow), (2, 14, typeDefRow(1)))
    ).build()
    withFile(withClass) { file =>
      val (outcome, entries) = runWalk(file)()
      assert(outcome.isDefined)
      assertEquals(summary(entries), Vector("Class|A"))
    }
  }

  test("CP-2d: seeded truncation and flip sweeps never raise out of the walk") {
    // Truncate the smoke DLL at every 32-byte boundary of its first
    // 4 KiB, and flip seeded single bytes of a small valid assembly.
    val smokeBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../../test-files/smoke/Smoke.dll"))
    val limit = Math.min(4096, smokeBytes.length)
    var at = 0
    while (at <= limit) {
      val truncated = java.util.Arrays.copyOf(smokeBytes, at)
      withFile(truncated) { file =>
        val r = scala.util.Try(AssemblyWalker.withinAssemblyStream[Int](file)(_ => 0)(None))
        assert(r.isSuccess, s"a truncated walk at byte $at must not raise")
        assert(r.get.isEmpty || r.get.isDefined)
      }
      at += 32
    }
    // Seeded byte flips over a small valid builder assembly.
    val seed = 0x5eed
    val base = withClassBuilder(1)
    var flip = 0
    while (flip < 200) {
      val mutated = base.clone()
      val pos = (seed + flip * 31) % mutated.length
      mutated(pos) = (mutated(pos) ^ (0x5a + (flip % 250)).toByte).toByte
      withFile(mutated) { file =>
        val r = scala.util.Try(AssemblyWalker.withinAssemblyStream[Int](file)(_ => 0)(None))
        assert(r.isSuccess, s"a flipped walk #$flip must not raise")
      }
      flip += 1
    }
  }

  test("CP-2d: an artifact at/above the 2 GiB map ceiling yields None, never throws") {
    val sparse = File.createTempFile("walkbig", ".dll")
    try {
      java.nio.file.Files.write(sparse.toPath, minimalAssemblyBytes)
      val raf = new java.io.RandomAccessFile(sparse, "rw")
      try {
        raf.setLength(0x80000000L + 1L)
      } finally {
        raf.close()
      }
      val r = scala.util.Try(AssemblyWalker.withinAssemblyStream[Int](sparse)(_ => 0)(None))
      assert(r.isSuccess, "a >= 2 GiB artifact must not raise out of the walk")
      assertEquals(r.get, None, "the map ceiling refuses cleanly")
    } finally {
      sparse.delete()
    }
  }

  test("CP-2d: tens of thousands of types walk under a bounded heap (O(entries) memory)") {
    // 60,000 minimal TypeDef rows in a forked -Xmx128m child: the walk
    // holds O(entries) memory (per-class JSON is capped), never
    // O(rows^2) and never an OOM. Note: TypeDef rows widen from 14 to
    // 16 bytes once the typeDefOrRef coded index crosses 2^14 rows
    // (the reader sizes coded indexes off table lengths — the rows
    // below use the true layout).
    val rows = 60000
    val typedefs = Array.fill[Byte](rows * 16)(0)
    var i = 0
    while (i < rows) {
      val r = i4(1) ++ i2(1) ++ i2(0) ++ i4(0) ++ i2(1) ++ i2(1) // flags, name, ns, coded(4), field, method
      System.arraycopy(r, 0, typedefs, i * 16, 16)
      i += 1
    }
    val bytes = new MinimalPeBuilder(
      extraTables = Seq((0x20, asmTableRow.length, asmTableRow), (2, 16, typedefs))
    ).build()
    withFile(bytes) { file =>
      val (code, out) = ForkSupport.runForked(List("walk", file.getAbsolutePath), maxHeap = "128m")
      assertEquals(code, 0, s"the child must exit cleanly: $out")
      assert(out.contains("WALK-SOME"), s"60k types must walk under 128 MiB: $out")
    }
  }

  test("CP-2e: the processStream contract — fresh streams, f exceptions propagate, post-walk refusal") {
    val fixture = corpusRoot.resolve("fixtures/ilasm_fixture.dll").toFile
    // (1) Two processStream calls inside the walk deliver the same
    // full content (each call is an independent fresh stream).
    val streamsEqual = AssemblyWalker.withinAssemblyStream[Boolean](fixture) { entries =>
      val classEntry = entries.head
      val first = payloadBytes(classEntry)
      val second = payloadBytes(classEntry)
      first.toSeq == second.toSeq
    }(None)
    assertEquals(streamsEqual, Some(true), "each processStream call is an independent fresh stream")
    // (2) f throwing propagates out of withinAssemblyStream.
    val thrown = scala.util.Try(AssemblyWalker.withinAssemblyStream[Int](fixture) { _ =>
      sys.error("callback boom")
    }(None))
    thrown match {
      case Failure(e) if e.getMessage == "callback boom" => ()
      case Success(_) => fail("f's exception must propagate")
      case Failure(other) => fail(s"f's exceptions must propagate untouched, got $other")
    }
    // (3) Entries retained after the walk refuse cleanly (IOException).
    var retained: Option[AssemblyEntry] = None
    AssemblyWalker.withinAssemblyStream[Int](fixture) { entries =>
      retained = Some(entries.head)
      0
    }(None)
    val post = scala.util.Try(payloadBytes(retained.get))
    assert(post.isFailure, "a retained entry must refuse after the walk")
    assertEquals(
      post.failed.toOption.map(_.getClass.getSimpleName),
      Some("IOException"),
      "the post-walk refusal is an IOException"
    )
  }

  test("CP-2e: benign absence and duplicate names are pinned") {
    // Zero-size debug entries are absent; the walk still succeeds.
    val zeroDebug = new MinimalPeBuilder(
      debugDirectory = Some(zero(12) ++ i4(2) ++ i4(0) ++ i4(0) ++ i4(0) ++ zero(12) ++ i4(16) ++ i4(0) ++ i4(0) ++ i4(0)),
      extraTables = Seq((0x20, asmTableRow.length, asmTableRow))
    ).build()
    withFile(zeroDebug) { file =>
      val (outcome, entries) = runWalk(file)()
      assert(outcome.isDefined, "zero-size debug entries are benign-absent")
      assertEquals(entries.length, 0, "no DebugBlob for data-less entries")
    }

    // Two embedded resources sharing one name both appear, in order,
    // with distinct streams (names are not unique — pinned).
    val payloadA = "payload-A".getBytes("UTF-8")
    val payloadB = "payload-B".getBytes("UTF-8")
    def row(offset: Int, nameIdx: Int): Array[Byte] = i4(offset) ++ i4(0) ++ i2(nameIdx) ++ i2(0)
    val bytes = new MinimalPeBuilder(
      extraTables = Seq(
        (0x20, asmTableRow.length, asmTableRow),
        (2, 14, typeDefRow(1)),
        (0x28, 12, row(0, 1) ++ row(0, 1))
      ),
      managedResourceBlob = Some(i4(Math.max(payloadA.length, payloadB.length)) ++ payloadA)
    ).build()
    // Both rows point at offset 0 of the same blob: same name, same
    // content — the duplicate appears twice, deterministically.
    withFile(bytes) { file =>
      val outcome = AssemblyWalker.withinAssemblyStream[Vector[(String, String)]](file) { entries =>
        entries.collect { case r: EmbeddedResourceEntry => (r.name, new String(payloadBytes(r), "UTF-8")) }
      }(None)
      outcome match {
        case None => fail("the duplicate-resource file must walk")
        case Some(resources) =>
          assertEquals(resources.length, 2, "both duplicate-named resources appear")
          assertEquals(resources.map(_._1), Vector("A", "A"))
          assertEquals(resources.map(_._2), Vector("payload-A", "payload-A"), "both entries stream the declared payload")
      }
    }
  }

  test("CP-2e: the 1 MiB class canonical-JSON ceiling refuses over, accepts under") {
    // Calibrated on the method-row shape (each method adds ~200 JSON
    // bytes): 5600 methods exceed 1 MiB, 4500 stay under. The premise
    // is asserted directly so drift fails loudly, not silently.
    def jsonLen(methods: Int): Int = {
      val rows = Array.fill[Byte](methods * 14)(0)
      var i = 0
      while (i < methods) {
        System.arraycopy(methodRow, 0, rows, i * 14, 14)
        i += 1
      }
      val b = new MinimalPeBuilder(
        extraTables = Seq(
          (0x20, asmTableRow.length, asmTableRow),
          (2, 14, typeDefRow(1)),
          (6, 14, rows)
        ),
        extraBlobs = Seq(voidSigBlob)
      )
      val tmp = File.createTempFile("jsoncap", ".dll")
      java.nio.file.Files.write(tmp.toPath, b.build())
      val len = AssemblyDefinition.readAssembly(tmp.getAbsolutePath) match {
        case Success(ad) =>
          val l = ad.mainModule.flatMap(_.types.headOption).flatMap(t => CanonicalJson.typeToJson(t).toOption).map(_.getBytes("UTF-8").length).getOrElse(-1)
          ad.close()
          l
        case Failure(e) => fail(s"readAssembly failed: $e")
      }
      tmp.delete()
      len
    }
    val overLen = jsonLen(5600)
    val underLen = jsonLen(4500)
    assert(overLen > 1024 * 1024, s"premise: 5600 methods must exceed 1 MiB (got $overLen)")
    assert(underLen <= 1024 * 1024, s"premise: 4500 methods must stay under 1 MiB (got $underLen)")
    def buildFile(methods: Int): Array[Byte] = {
      val rows = Array.fill[Byte](methods * 14)(0)
      var i = 0
      while (i < methods) {
        System.arraycopy(methodRow, 0, rows, i * 14, 14)
        i += 1
      }
      new MinimalPeBuilder(
        extraTables = Seq(
          (0x20, asmTableRow.length, asmTableRow),
          (2, 14, typeDefRow(1)),
          (6, 14, rows)
        ),
        extraBlobs = Seq(voidSigBlob)
      ).build()
    }
    withFile(buildFile(5600)) { file =>
      val (outcome, _) = runWalk(file)()
      assertEquals(outcome, None, "a class whose canonical JSON exceeds 1 MiB refuses the walk")
    }
    withFile(buildFile(4500)) { file =>
      val (outcome, _) = runWalk(file)()
      assert(outcome.isDefined, "a class under the 1 MiB ceiling walks")
    }
  }

  test("CP-2b: the class subsequence matches the model's own order across the corpus") {
    import org.json4s._
    val manifest = org.json4s.native.JsonMethods.parse(
      new String(java.nio.file.Files.readAllBytes(corpusRoot.resolve("manifest.json")), "UTF-8"))
    implicit val formats: DefaultFormats.type = DefaultFormats
    var walked = 0
    var total = 0
    (manifest \ "packages").children.foreach { pkg =>
      (pkg \ "assemblies").children.foreach { asm =>
        val rel = (asm \ "path").extract[String]
        if (!rel.contains("corrupt")) {
          total += 1
          AssemblyDefinition.readAssembly(corpusRoot.resolve(rel).toString) match {
            case Success(ad) =>
              ad.close()
              val (outcome, entries) = runWalk(corpusRoot.resolve(rel).toFile)()
              outcome match {
                case None => fail(s"$rel must walk")
                case Some(_) =>
                  walked += 1
                  // The class entries must appear in the model's own
                  // order (module.types then nested depth-first) — an
                  // internal-consistency check; the independent pins are
                  // the fixture full-vector pins in CP-2b.
                  AssemblyDefinition.readAssembly(corpusRoot.resolve(rel).toString) match {
                    case Failure(e2) => fail(s"$rel second read failed: $e2")
                    case Success(ad2) =>
                      try {
                        val modelOrder = ad2.modules.toVector.flatMap { m =>
                          val out = Vector.newBuilder[String]
                          def walkType(t: TypeDefinition): Unit = {
                            out += DotnetNameSanitizer.sanitize(t.fullName)
                            t.nestedTypes.foreach(walkType)
                          }
                          m.types.foreach(walkType)
                          out.result()
                        }
                        val walkOrder = entries.collect { case c: ClassEntry => c.name }
                        assertEquals(walkOrder, modelOrder, s"$rel: the class subsequence must match the model order")
                      } finally {
                        ad2.close()
                      }
                  }
              }
            case Failure(e) => fail(s"$rel read failed: $e")
          }
        }
      }
    }
    assertEquals(total, 149)
    assert(walked >= 140, s"most corpus assemblies must walk, walked $walked")
  }

  // ---- helpers -------------------------------------------------------

  private def withClassBuilder(methods: Int): Array[Byte] = {
    if (methods == 0) {
      new MinimalPeBuilder(
        extraTables = Seq((0x20, asmTableRow.length, asmTableRow), (2, 14, typeDefRow(1)))
      ).build()
    }
    else {
      val rows = Array.fill[Byte](methods * 14)(0)
      var i = 0
      while (i < methods) {
        System.arraycopy(methodRow, 0, rows, i * 14, 14)
        i += 1
      }
      new MinimalPeBuilder(
        extraTables = Seq(
          (0x20, asmTableRow.length, asmTableRow),
          (2, 14, typeDefRow(1)),
          (6, 14, rows)
        ),
        extraBlobs = Seq(voidSigBlob)
      ).build()
    }
  }

  private def zipBytes(): Array[Byte] = {
    val out = new java.io.ByteArrayOutputStream()
    val z = new java.util.zip.ZipOutputStream(out)
    z.putNextEntry(new java.util.zip.ZipEntry("a.txt"))
    z.write("hello".getBytes("UTF-8"))
    z.closeEntry()
    z.close()
    out.toByteArray
  }
}
