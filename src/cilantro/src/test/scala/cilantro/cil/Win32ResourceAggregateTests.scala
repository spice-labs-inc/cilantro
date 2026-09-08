// Win32ResourceAggregateTests — H4-xx: win32 resource aggregate caps
// and the directory visited set.
//
// Why these tests exist:
//   Plan 2026_09_01 (phase-04) closes two multiplicative holes in
//   readWin32Resources: (a) hostile directory-offset aliasing (entries
//   pointing at the same subdirectory, recursively) multiplies walk
//   work without growing the file — a visited set on directory raw
//   offsets walks each directory exactly once; (b) the per-blob cap
//   alone lets two 512 MiB leaves total 1 GiB of heap — aggregate
//   leaf and byte caps fail the walk before the second leaf is read.
//
// Theory of the test:
//   - The aliasing bomb: root(65536 type entries) -> one name dir(65536)
//     -> one language dir(65536) -> one shared data entry. With the
//     visited set the walk emits exactly 65536 leaves; without it the
//     walk attempts 65536^3 leaf reads (hangs).
//   - The leaf-cap boundary: 16 name dirs x 62,500 entries =
//     1,000,000 leaves succeeds; 1,000,001 fails with DataFormatException.
//   - The byte-cap bomb: a sparse file whose second leaf claims 512 MiB
//     (passing the per-blob check) fails the aggregate check before
//     the blob is read — the test completes fast and never reads the
//     sparse region.
//   - Real input: the pinned Newtonsoft RT_VERSION leaf (C5-04a
//     constants) is byte-identical; the Slow corpus walk proves the
//     caps are unreachable for real files.
//
// Requirements traced:
//   plans/2026_09_01_cilantro_hardening_and_dotnet_probe/phase-04.md
//   H4-01..H4-05 (suggestion cilantro #4; ADR-0013).
//
// LLM notes:
//   - Directory header = 12 zero bytes + i2(named) + i2(ids).
//   - Entry = i4(name-or-id) + i4(target); target high bit = subdir;
//     subdir targets are tree-relative, data offsets are
//     dirRva(0x2500)-relative (treeBaseRaw = 0x660 in the builder).
//   - The cap constants (plan 2026_09_02 phase B, D-10): the object
//     guards — per-directory entries 1,000,000, total leaves
//     1,000,000 — survive; the aggregate and per-leaf BYTE budgets
//     (512 MiB) are gone: leaves are extent-checked zero-copy slices.

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import io.spicelabs.cilantro.metadata.CorpusProvisioner
import java.io.FileOutputStream
import java.io.RandomAccessFile

class Win32ResourceAggregateTests extends munit.FunSuite {


  private def corpusRoot = CorpusProvisioner.ensureCorpus()

  private def i2(v: Int): Array[Byte] = Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte)

  private def i4(v: Int): Array[Byte] =
    Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte, ((v >> 16) & 0xff).toByte, ((v >> 24) & 0xff).toByte)

  private def zero(n: Int): Array[Byte] = Array.ofDim[Byte](n)

  private def readResources(path: String): scala.util.Try[Vector[io.spicelabs.cilantro.Win32Resource]] = {
    io.spicelabs.cilantro.ModuleDefinition.readModule(path).flatMap { module =>
      scala.util.Try {
        module.read(Vector.empty[io.spicelabs.cilantro.Win32Resource], (_, reader: io.spicelabs.cilantro.MetadataReader) => {
          val got = reader.readWin32Resources()
          val b = Vector.newBuilder[io.spicelabs.cilantro.Win32Resource]
          got.foreach(r => b += r)
          b.result()
        })
      }
    }
  }

  private def withPe(tree: Array[Byte])(body: String => Unit): Unit = {
    val file = java.io.File.createTempFile("rsrc", ".dll")
    val out = new FileOutputStream(file)
    out.write(new MinimalPeBuilder(win32ResourceTree = Some(tree)).build())
    out.close()
    try {
      body(file.getAbsolutePath)
    } finally {
      file.delete()
    }
  }

  private val dirRva = 0x2500

  // Plan 2026_09_02 phase B (D-3): leaf payloads are stream views
  // (PayloadSource). Stream helpers count/hash without materializing
  // (large leaves are extent-only now).
  private def payloadLength(p: io.spicelabs.cilantro.PayloadSource): Long =
    p.processStream { in =>
      var count = 0L
      val b = new Array[Byte](65536)
      var n = in.read(b)
      while (n >= 0) {
        if (n > 0) count += n
        n = in.read(b)
      }
      count
    }

  private def payloadSha256(p: io.spicelabs.cilantro.PayloadSource): String =
    p.processStream { in =>
      val md = java.security.MessageDigest.getInstance("SHA-256")
      val buf = new Array[Byte](65536)
      var n = in.read(buf)
      while (n >= 0) {
        if (n > 0) md.update(buf, 0, n)
        n = in.read(buf)
      }
      md.digest().map(b => f"${b & 0xff}%02x").mkString
    }

  // Cursor-based tree builder: root(ids=typeCount) -> one aliased name
  // dir -> one aliased language dir -> one shared data entry (1-byte
  // blob). The per-directory entry counts come from dirEntryCounts.
  private def aliasedTree(typeCount: Int, nameEntries: Int, langEntries: Int): Array[Byte] = {
    val rootSize = 16 + typeCount * 8
    val nameDirOff = rootSize
    val langDirOff = nameDirOff + 16 + nameEntries * 8
    val dataEntryOff = langDirOff + 16 + langEntries * 8
    val blobOff = dataEntryOff + 16
    val tree = new Array[Byte](blobOff + 1)
    var pos = 0
    def put(bytes: Array[Byte]): Unit = {
      System.arraycopy(bytes, 0, tree, pos, bytes.length)
      pos += bytes.length
    }
    def putI2(v: Int): Unit = {
      tree(pos) = (v & 0xff).toByte
      tree(pos + 1) = ((v >> 8) & 0xff).toByte
      pos += 2
    }
    def putI4(v: Int): Unit = {
      tree(pos) = (v & 0xff).toByte
      tree(pos + 1) = ((v >> 8) & 0xff).toByte
      tree(pos + 2) = ((v >> 16) & 0xff).toByte
      tree(pos + 3) = ((v >> 24) & 0xff).toByte
      pos += 4
    }
    // root: typeCount entries, all pointing at the one name dir
    pos += 12
    putI2(0)
    putI2(typeCount)
    var i = 0
    while (i < typeCount) {
      putI4(10)
      putI4(0x80000000 | nameDirOff)
      i += 1
    }
    // name dir: nameEntries entries, all pointing at the one lang dir
    pos += 12
    putI2(0)
    putI2(nameEntries)
    i = 0
    while (i < nameEntries) {
      putI4(1)
      putI4(0x80000000 | langDirOff)
      i += 1
    }
    // lang dir: langEntries entries, all pointing at the shared data entry
    pos += 12
    putI2(0)
    putI2(langEntries)
    i = 0
    while (i < langEntries) {
      putI4(0x409)
      putI4(dataEntryOff)
      i += 1
    }
    // data entry + 1-byte blob
    putI4(dirRva + blobOff)
    putI4(1)
    pos += 8
    tree(pos) = 7
    pos += 1
    tree
  }

  // Root(typeCount) -> typeCount distinct name dirs; name dir i holds
  // dirEntryCounts(i) entries, each pointing directly at the shared
  // data entry (1-byte blob).
  private def leafTree(typeCount: Int, dirEntryCounts: Array[Int]): Array[Byte] = {
    val rootSize = 16 + typeCount * 8
    val nameDirOffs = new Array[Int](typeCount)
    var acc = rootSize
    var i = 0
    while (i < typeCount) {
      nameDirOffs(i) = acc
      acc += 16 + dirEntryCounts(i) * 8
      i += 1
    }
    val dataEntryOff = acc
    val blobOff = dataEntryOff + 16
    val tree = new Array[Byte](blobOff + 1)
    var pos = 0
    def put(bytes: Array[Byte]): Unit = {
      System.arraycopy(bytes, 0, tree, pos, bytes.length)
      pos += bytes.length
    }
    def putI2(v: Int): Unit = {
      tree(pos) = (v & 0xff).toByte
      tree(pos + 1) = ((v >> 8) & 0xff).toByte
      pos += 2
    }
    def putI4(v: Int): Unit = {
      tree(pos) = (v & 0xff).toByte
      tree(pos + 1) = ((v >> 8) & 0xff).toByte
      tree(pos + 2) = ((v >> 16) & 0xff).toByte
      tree(pos + 3) = ((v >> 24) & 0xff).toByte
      pos += 4
    }
    pos += 12
    putI2(0)
    putI2(typeCount)
    i = 0
    while (i < typeCount) {
      putI4(10)
      putI4(0x80000000 | nameDirOffs(i))
      i += 1
    }
    i = 0
    while (i < typeCount) {
      pos += 12
      putI2(0)
      putI2(dirEntryCounts(i))
      var j = 0
      while (j < dirEntryCounts(i)) {
        putI4(1)
        putI4(dataEntryOff)
        j += 1
      }
      i += 1
    }
    putI4(dirRva + blobOff)
    putI4(1)
    pos += 8
    tree(pos) = 7
    pos += 1
    tree
  }

  test("H4-01: the directory visited set bounds the aliasing walk-bomb") {
    // 65535 type entries (the u16 entry-count maximum) -> one name dir
    // -> one lang dir -> one data entry. With the visited set exactly
    // 65535 leaves are emitted; without it the walk attempts 65535^3
    // leaf reads.
    val tree = aliasedTree(65535, 65535, 65535)
    withPe(tree) { path =>
      readResources(path) match {
        case Success(resources) =>
          assertEquals(resources.length, 65535, "each language entry emits exactly one leaf")
          assertEquals(resources.map(r => payloadLength(r)).distinct.toVector, Vector(1L), "all leaves share the 1-byte blob")
        case Failure(t) => fail(s"the aliased tree must walk: $t")
      }
    }
  }

  test("H4-02: the leaf-count cap accepts 1,000,000 and rejects 1,000,001") {
    val atCap = leafTree(16, Array.fill[Int](16)(62500))
    withPe(atCap) { path =>
      readResources(path) match {
        case Success(resources) => assertEquals(resources.length, 1000000, "1,000,000 leaves is the cap and must parse")
        case Failure(t) => fail(s"1,000,000 leaves must parse: $t")
      }
    }
    val overCap = leafTree(16, Array.fill[Int](15)(62500) :+ 62501)
    withPe(overCap) { path =>
      readResources(path) match {
        case Success(_) => fail("1,000,001 leaves must exceed the leaf cap")
        case Failure(_) => ()
      }
    }
  }

  test("H4-03: two 512 MiB leaves now enumerate as extent-only slices (D-10)") {
    // Plan 2026_09_02 phase B (D-10): the aggregate BYTE budget is
    // gone with the array path — a leaf's declared size is checked
    // only against the file extent, so two in-file 512 MiB leaves
    // both enumerate as zero-copy slices. (The full 512 MiB read is
    // pinned under a bounded heap by SliceViewTests CP-3d; here the
    // enumeration + the slice extents are the assertion.)
    // Tree: root -> name dir -> language dir(2 entries) -> data entry 1
    // (1 byte) + data entry 2 (claims 512 MiB, sparsely extended).
    val rootSize = 24
    val nameDirOff = rootSize
    val langDirOff = nameDirOff + 24
    val data1Off = langDirOff + 16 + 16
    val data2Off = data1Off + 16
    val blobOff1 = data2Off + 16
    val blobOff2 = blobOff1 + 1
    val tree = new Array[Byte](blobOff2)
    var pos = 0
    def put(bytes: Array[Byte]): Unit = {
      System.arraycopy(bytes, 0, tree, pos, bytes.length)
      pos += bytes.length
    }
    put(zero(12) ++ i2(0) ++ i2(1) ++ i4(10) ++ i4(0x80000000 | nameDirOff)) // root
    put(zero(12) ++ i2(0) ++ i2(1) ++ i4(1) ++ i4(0x80000000 | langDirOff)) // name dir
    put(zero(12) ++ i2(0) ++ i2(2)) // lang dir: 2 entries
    put(i4(0x409) ++ i4(data1Off))
    put(i4(0) ++ i4(data2Off))
    put(i4(dirRva + blobOff1) ++ i4(1) ++ zero(8)) // data entry 1: 1 byte
    put(i4(dirRva + blobOff2) ++ i4(0x20000000) ++ zero(8)) // data entry 2: 512 MiB claim

    val file = java.io.File.createTempFile("rsrc", ".dll")
    val out = new FileOutputStream(file)
    out.write(new MinimalPeBuilder(win32ResourceTree = Some(tree)).build())
    out.close()
    try {
      // Extend the file sparsely so the 512 MiB claim passes the
      // extent check (blobRaw + size <= fileSize) without physically
      // writing 512 MiB.
      val raf = new RandomAccessFile(file, "rw")
      try {
        val treeBaseRaw = 0x160 + (dirRva - 0x2000) // builder bodyOffset + dir offset
        val needed = treeBaseRaw + blobOff2 + 0x20000000L
        raf.setLength(needed + 1)
      } finally {
        raf.close()
      }
      readResources(file.getAbsolutePath) match {
        case Success(resources) =>
          assertEquals(resources.length, 2, "both in-file leaves must enumerate")
          assertEquals(payloadLength(resources(0)), 1L, "the 1-byte leaf")
          resources(1).processStream { in =>
            assertEquals(in.available(), 0x20000000, "the 512 MiB leaf is a full slice")
            assertEquals(in.read(), 0, "the sparse region reads as zeros")
          }
        case Failure(t) => fail(s"two in-file 512 MiB leaves must enumerate: $t")
      }
    } finally {
      file.delete()
    }
  }

  test("H4-04: the pinned Newtonsoft RT_VERSION leaf is byte-identical (regression)") {
    readResources(corpusRoot.resolve("bin/Newtonsoft.Json/12.0.3/net20/Newtonsoft.Json.dll").toString) match {
      case Success(resources) =>
        assertEquals(resources.length, 1, "the net20 assembly has one resource leaf")
        val r = resources(0)
        assertEquals(r.typeNameOrId, "RT_VERSION")
        assertEquals(r.nameId, 1)
        assertEquals(r.language, 0)
        assertEquals(payloadLength(r), 1110L)
        assertEquals(
          payloadSha256(r),
          "5bd2a0f900755c12a17b123b78a6280d22f4cdaad481f44c490661132f928fcc",
          "the pinned RT_VERSION blob sha256"
        )
      case Failure(t) => fail(s"resource walk failed: $t")
    }
  }

  test("H4-05: corpus leaf counts stay below the count caps") {
    // Plan 2026_09_02 phase B (D-10): the aggregate byte-total
    // assertion is gone (byte budgets died with the array path); the
    // object guards are asserted. Leaf byte totals are reported only
    // (informational — leaves are extent-only slices now).
    import org.json4s._
    val manifest = org.json4s.native.JsonMethods.parse(
      new String(java.nio.file.Files.readAllBytes(corpusRoot.resolve("manifest.json")), "UTF-8"))
    implicit val formats: DefaultFormats.type = DefaultFormats
    var withResources = 0
    var maxLeaves = 0L
    var maxBytes = 0L
    (manifest \ "packages").children.foreach { pkg =>
      (pkg \ "assemblies").children.foreach { asm =>
        val rel = (asm \ "path").extract[String]
        if (!rel.contains("corrupt")) {
          readResources(corpusRoot.resolve(rel).toString) match {
            case Success(resources) =>
              if (resources.nonEmpty) {
                withResources += 1
                maxLeaves = Math.max(maxLeaves, resources.length.toLong)
                maxBytes = Math.max(maxBytes, resources.map(r => payloadLength(r)).sum)
              }
            case Failure(t) => fail(s"$rel resource walk threw: $t")
          }
        }
      }
    }
    assert(withResources >= 140, s"nearly every corpus assembly carries resources, got $withResources")
    assert(maxLeaves < 1000000L, s"corpus max leaves $maxLeaves must stay below the leaf cap")
    println(s"H4-05 informational: corpus max leaf bytes = $maxBytes")
  }
}
