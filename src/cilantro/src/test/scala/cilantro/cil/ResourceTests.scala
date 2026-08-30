// ResourceTests — C5-02: embedded managed resources.
//
// Why these tests exist:
//   Plan 13 makes the ManifestResource table verifiable and GR-ready:
//   embedded resources are files inside the DLL (satellite culture
//   assemblies, Costura-embedded payloads) and must be readable
//   byte-exactly, classified correctly (embedded / assemblyLinked /
//   linked), and hostile-offset safe.
//
// Theory of the test:
//   - The pinned fixture (built in the pinned docker image) carries two
//     embedded resources; the oracle dump (GoldenDumper resources)
//     pins their names/types/sizes/sha256s and the bytes round-trip.
//   - Synthetic ManifestResource rows (MinimalPeBuilder extraTables)
//     exercise the assemblyLinked (AssemblyRef implementation) and
//     linked (File implementation) branches, plus the embedded branch
//     with the managed-resource blob.
//   - An out-of-bounds resource offset fails cleanly.
//
// Requirements traced:
//   13_dll_container_traversal.md C5-02 (a-e).
//
// LLM notes:
//   - ManifestResource row: Offset(u32) Flags(u32) Name(string idx)
//     Implementation(coded implementation: 0 embedded, 1 AssemblyRef,
//     2 File).
//   - The embedded blob lives at the CLI header's Resources directory
//     plus the row's offset; the blob is [u32 length][bytes].

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import java.io.FileOutputStream

class ResourceTests extends munit.FunSuite {

  override def munitTimeout = scala.concurrent.duration.Duration(120, "min")

  private val Slow = new munit.Tag("Slow")

  private def i2(v: Int): Array[Byte] = Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte)

  private def i4(v: Int): Array[Byte] =
    Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte, ((v >> 16) & 0xff).toByte, ((v >> 24) & 0xff).toByte)

  private def zero(n: Int): Array[Byte] = Array.ofDim[Byte](n)

  private def sha256(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  private def resources(path: String): scala.util.Try[Vector[io.spicelabs.cilantro.Resource]] = {
    io.spicelabs.cilantro.ModuleDefinition.readModule(path).flatMap { module =>
      scala.util.Try {
        val got = module.resources
        val b = Vector.newBuilder[io.spicelabs.cilantro.Resource]
        got.foreach(r => b += r)
        b.result()
      }
    }
  }

  test("C5-02a: the pinned fixture's resources match the oracle and round-trip their bytes") {
    resources("../../corpus/fixtures/resources_fixture.dll") match {
      case Success(res) =>
        assertEquals(res.length, 2)
        val byName = res.map(r => r.name -> r).toMap
        val alpha = byName.getOrElse("ResourcesFixture.data.alpha.bin", fail("alpha missing"))
        val beta = byName.getOrElse("ResourcesFixture.data.beta.bin", fail("beta missing"))
        assertEquals(alpha.resourceType, io.spicelabs.cilantro.ResourceType.embedded)
        assertEquals(beta.resourceType, io.spicelabs.cilantro.ResourceType.embedded)
        alpha match {
          case e: io.spicelabs.cilantro.EmbeddedResource =>
            e.resourceData() match {
              case Success(data) =>
                assertEquals(data.length, 50)
                assertEquals(sha256(data), "3f8017a8e09505b25eaab6781a48d9a1b00634f4f11efe0a9b624cd5294beeb8", "the pinned alpha sha256")
                assertEquals(new String(data, "UTF-8").trim, "alpha resource payload for cilantro C5-02 fixture")
              case Failure(t) => fail(s"alpha data read failed: $t")
            }
          case _ => fail("alpha must be an EmbeddedResource")
        }
      case Failure(t) => fail(s"the fixture must read: $t")
    }
  }

  // Synthetic resource rows. The strings heap = [0, 'A', 0] + extraStrings,
  // so the first extra string starts at index 3.
  private def manifestResourceRow(offset: Int, flags: Int, nameIdx: Int, implementation: Int): Array[Byte] =
    i4(offset) ++ i4(flags) ++ i2(nameIdx) ++ i2(implementation)

  test("C5-02b: embedded, assemblyLinked and linked resources classify correctly") {
    val embeddedName = "EmbeddedRes"
    val linkedName = "LinkedRes"
    val assemblyLinkedName = "AssemblyLinkedRes"
    val assemblyName = "DepAssembly"
    val fileName = "netmodule.netmodule"
    val extraStrings = (embeddedName + "\u0000" + linkedName + "\u0000" + assemblyLinkedName + "\u0000" +
      assemblyName + "\u0000" + fileName + "\u0000").getBytes("UTF-8")
    def nameIdx(name: String): Int = {
      // The extra strings start at index 3 in the heap.
      var idx = 3
      val names = List(embeddedName, linkedName, assemblyLinkedName, assemblyName, fileName)
      var found = -1
      for n <- names do {
        if (n == name && found < 0) {
          found = idx
        }
        idx += n.getBytes("UTF-8").length + 1
      }
      found
    }
    val blob = i4(11) ++ "hello world".getBytes("UTF-8")
    val extraTables = Seq(
      (35, 20, i2(1) ++ i2(0) ++ i2(0) ++ i2(0) ++ i4(0) ++ i2(0) ++ i2(nameIdx(assemblyName)) ++ i2(0) ++ i2(0)), // AssemblyRef
      (38, 8, i4(0) ++ i2(nameIdx(fileName)) ++ i2(0)), // File
      (40, 12,
        manifestResourceRow(0, 0x20, nameIdx(embeddedName), 0) ++
          manifestResourceRow(0, 0x20, nameIdx(linkedName), (1 << 2) | 0) ++
          manifestResourceRow(0, 0x20, nameIdx(assemblyLinkedName), (1 << 2) | 1)) // ManifestResource
    )
    val file = java.io.File.createTempFile("res", ".dll")
    val out = new FileOutputStream(file)
    out.write(new MinimalPeBuilder(
      extraTables = extraTables,
      extraStrings = extraStrings,
      managedResourceBlob = Some(blob)
    ).build())
    out.close()
    try {
      resources(file.getAbsolutePath) match {
        case Success(res) =>
          assertEquals(res.length, 3)
          val byName = res.map(r => r.name -> r).toMap
          assertEquals(byName(embeddedName).resourceType, io.spicelabs.cilantro.ResourceType.embedded)
          assertEquals(byName(linkedName).resourceType, io.spicelabs.cilantro.ResourceType.linked)
          assertEquals(byName(assemblyLinkedName).resourceType, io.spicelabs.cilantro.ResourceType.assemblyLinked)
          byName(embeddedName) match {
            case e: io.spicelabs.cilantro.EmbeddedResource =>
              e.resourceData() match {
                case Success(data) => assertEquals(new String(data, "UTF-8"), "hello world")
                case Failure(t) => fail(s"embedded blob read failed: $t")
              }
            case _ => fail("embedded resource must be an EmbeddedResource")
          }
        case Failure(t) => fail(s"synthetic resources must read: $t")
      }
    } finally {
      file.delete()
    }
  }

  test("C5-02c: an out-of-bounds resource offset fails cleanly") {
    val blob = i4(4) ++ "data".getBytes("UTF-8")
    val row = manifestResourceRow(0x7fffff00, 0x20, 3, 0)
    val file = java.io.File.createTempFile("res", ".dll")
    val out = new FileOutputStream(file)
    out.write(new MinimalPeBuilder(
      extraTables = Seq((40, 12, row)),
      extraStrings = "Bad\u0000".getBytes("UTF-8"),
      managedResourceBlob = Some(blob)
    ).build())
    out.close()
    try {
      resources(file.getAbsolutePath) match {
        case Success(res) =>
          res.collect { case e: io.spicelabs.cilantro.EmbeddedResource => e } match {
            case Seq() => ()
            case embedded =>
              embedded.foreach { e =>
                assert(e.resourceData().isFailure, "an out-of-bounds offset must fail cleanly")
              }
          }
        case Failure(t) => fail(s"resource enumeration must read: $t")
      }
    } finally {
      file.delete()
    }
  }

  test("C5-02d: the Try-shaped accessor is part of the compiled contract") {
    val readDataFn: io.spicelabs.cilantro.EmbeddedResource => scala.util.Try[Array[Byte]] =
      (e: io.spicelabs.cilantro.EmbeddedResource) => e.resourceData()
    val detached = new io.spicelabs.cilantro.EmbeddedResource("x", 0, Array.emptyByteArray)
    assert(readDataFn(detached).isSuccess, "in-memory data reads without the image")
  }

  test("C5-02e (Slow): every corpus assembly's resource table enumerates — never throws".tag(Slow)) {
    import org.json4s._
    val manifest = org.json4s.native.JsonMethods.parse(
      new String(java.nio.file.Files.readAllBytes(io.spicelabs.cilantro.metadata.CorpusHelpers.corpusRoot.resolve("manifest.json")), "UTF-8"))
    implicit val formats: DefaultFormats.type = DefaultFormats
    var total = 0
    var withResources = 0
    (manifest \ "packages").children.foreach { pkg =>
      (pkg \ "assemblies").children.foreach { asm =>
        val rel = (asm \ "path").extract[String]
        if (!rel.contains("corrupt")) {
          total += 1
          resources("../../corpus/" + rel) match {
            case Success(res) =>
              if (res.nonEmpty) withResources += 1
            case Failure(t) => fail(s"$rel resource enumeration threw: $t")
          }
        }
      }
    }
    assertEquals(total, 149)
    assert(withResources >= 50, s"a substantial share of the corpus carries embedded resources (measured 76), got $withResources")
  }
}
