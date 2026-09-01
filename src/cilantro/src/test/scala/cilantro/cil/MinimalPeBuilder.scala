// MinimalPeBuilder — synthetic PE assembly builder for cap/bomb tests.
//
// Why this exists:
//   C4-02/C4-03 pin the resource caps at the PE header level (section
//   count, section sizes, metadata table row counts, heap sizes) and the
//   reader-recursion guards (type base chains, nested signatures). Real
//   corpus files never approach the limits, so the tests need files that
//   declare hostile values on tiny inputs — a hand-built minimal PE is
//   the only way to exercise the header validation without multi-GB
//   fixtures.
//
// Theory:
//   The ImageReader walks: DOS -> PE -> COFF -> optional -> sections ->
//   CLI header -> metadata root -> stream headers -> table heap. This
//   builder emits exactly that shape with configurable section count,
//   section size claims, TypeDef row count, real TypeDef rows (for a
//   base-type chain), a Field table with a deep-nested signature blob, a
//   Method table with a fat body header claiming an arbitrary code size,
//   arbitrary extra row counts, and a #Blob heap size claim. Reading the
//   produced file must either succeed (limits respected) or fail with a
//   clean DataFormatException (limit violated) — never OOM and never
//   read past the file.
//
// LLM notes:
//   - All sizes are little-endian; the optional header is PE32 (0x10b).
//   - The ImageReader treats the CLI data directory as authoritative
//     (offset 208 within the optional header).
//   - The metadata root stream headers are 4-byte aligned relative to
//     the root start.
//   - Table ids: 0 Module, 2 TypeDef, 4 Field, 6 Method (see
//     cilantro.metadata.Table).
//   - TypeDef row: Flags(4) Name(2) Namespace(2) Extends(2) FieldList(2)
//     MethodList(2); Extends is the TypeDefOrRef coded index (TypeDef tag
//     = 0, value = rid << 2).

package io.spicelabs.cilantro.cil

class MinimalPeBuilder(
    sectionCount: Int = 1,
    sectionVirtualSize: Int = 0x1000,
    sectionRawSize: Int = 0x800,
    typeDefRows: Int = 0,
    blobHeapSize: Int = 0,
    // Real TypeDef rows forming a base chain: row i extends row i+1; the
    // last row extends 0 (no base). Forces the eager base-type read to
    // recurse N deep.
    typeDefChain: Int = 0,
    // Field table with one field whose signature nests this many
    // pointers before an object type.
    deepFieldSigDepth: Int = 0,
    // Method table with one method whose fat body header claims this
    // code size (the body bytes themselves are absent).
    methodBodyClaim: Int = 0,
    // A raw WIN_CERTIFICATE table appended to the section; the Security
    // data directory is set with the overlay convention (VA = the raw
    // file offset of the table).
    certificateTable: Option[Array[Byte]] = None,
    // A raw resource-directory tree placed in the section at RVA 0x2500;
    // the Resource data directory points at it with the standard
    // section-relative mapping (dirRva == the tree's RVA).
    win32ResourceTree: Option[Array[Byte]] = None,
    // A raw debug directory (28-byte IMAGE_DEBUG_DIRECTORY entries)
    // placed in the section at RVA 0x2600; the Debug data directory
    // points at it.
    debugDirectory: Option[Array[Byte]] = None,
    // When false, the CLI data directory is zeroed — the file is a
    // plain PE with no .NET header (the probe's negative sample).
    cliHeaderPresent: Boolean = true,
    // When set, the metadata root's version-length field is written as
    // this claim instead of the real version length (hostile input for
    // the probe's version-string bound).
    versionLengthClaim: Option[Int] = None,
    // Extra per-table row counts (table id -> rows), for offset-arithmetic
    // overflow tests.
    tableRowCounts: Map[Int, Int] = Map.empty,
    // Extra blobs appended to the #Blob heap after the built-in field /
    // method signature blobs; each entry is already length-prefixed
    // (compressed u32 length + bytes). The first extra blob's index is
    // 2 + fieldSigBlob + methodSigBlob when those are present.
    extraBlobs: Seq[Array[Byte]] = Seq.empty,
    // Extra tables with real rows: (table id, row size, row bytes).
    // Emitted after the built-in rows in table-id order.
    extraTables: Seq[(Int, Int, Array[Byte])] = Seq.empty,
    // Extra bytes appended to the #Strings heap.
    extraStrings: Array[Byte] = Array.emptyByteArray,
    // The managed-resources blob (length-prefixed) placed in the section
    // at RVA 0x2700; the CLI header's Resources directory points at it.
    managedResourceBlob: Option[Array[Byte]] = None
) {

  private def i2(v: Int): Array[Byte] =
    Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte)

  private def i4(v: Int): Array[Byte] =
    Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte, ((v >> 16) & 0xff).toByte, ((v >> 24) & 0xff).toByte)

  private def i8(v: Long): Array[Byte] = {
    val b = new Array[Byte](8)
    for i <- 0 until 8 do b(i) = ((v >> (8 * i)) & 0xff).toByte
    b
  }

  private def pad4(parts: Seq[Array[Byte]]): Array[Byte] = {
    val len = parts.map(_.length).sum
    parts.toArray.flatten ++ Array.ofDim[Byte]((4 - (len % 4)) % 4)
  }

  private def zero(n: Int): Array[Byte] = Array.ofDim[Byte](n)

  private def compressedUInt32(v: Int): Array[Byte] = {
    if (v < 0x80) Array(v.toByte)
    else if (v < 0x4000) Array(((v & 0x7f) | 0x80).toByte, (v >> 7).toByte)
    else Array(((v & 0x3f) | 0x80).toByte, (((v >> 7) & 0x7f) | 0x80).toByte, (v >> 14).toByte)
  }

  private def fieldSigBlob: Array[Byte] = {
    val sig = Array[Byte](0x06) ++ Array.fill[Byte](deepFieldSigDepth)(0x0f.toByte) ++ Array[Byte](0x1c.toByte)
    compressedUInt32(sig.length) ++ sig
  }

  private def fieldSigBlobOffset: Int = 2

  private def methodSigBlobOffset: Int = {
    if (deepFieldSigDepth > 0) 2 + fieldSigBlob.length else 2
  }

  // Method signature: default convention, no params, void return.
  private def methodSigBlob: Array[Byte] = Array[Byte](0x03, 0x00, 0x00, 0x01)

  // The method body lives at a fixed RVA inside the section, past the
  // metadata (the metadata size does not depend on this value).
  private val bodyRva: Int = 0x2400
  private val managedResourceOffsetInSection: Int = 0x700

  // The #~ table heap. Valid tables in table-id order: Module (0),
  // TypeDef (2), Field (4), Method (6), plus the extra row counts.
  private def tableHeap(): Array[Byte] = {
    val fieldTable = deepFieldSigDepth > 0
    val methodTable = methodBodyClaim > 0
    val extraIds = extraTables.map(_._1)
    val valid: Long = (1L << 0) | (1L << 2) |
      (if (fieldTable) 1L << 4 else 0L) |
      (if (methodTable) 1L << 6 else 0L) |
      tableRowCounts.keys.foldLeft(0L) { case (acc, id) => acc | (1L << id) } |
      extraIds.foldLeft(0L) { case (acc, id) => acc | (1L << id) }
    val sorted: Long = 0L
    val head = i4(0) ++ Array[Byte](2, 0, 0, 1) ++ i8(valid) ++ i8(sorted)

    val extraRowsById = extraTables.map { case (id, _, rows) => id -> rows }.toMap
    val countById: Map[Int, Int] = tableRowCounts ++
      Map(0 -> 1, 2 -> typeDefRows.max(typeDefChain)) ++
      (if (fieldTable) Map(4 -> 1) else Map.empty) ++
      (if (methodTable) Map(6 -> 1) else Map.empty) ++
      extraTables.map { case (id, rowSize, rows) => id -> rows.length / rowSize }.toMap
    val counts = (0 until 64).filter(id => (valid & (1L << id)) != 0).map(id => i4(countById(id))).flatten

    // Module row: Generation, Name, Mvid, EncId, EncBaseId.
    val moduleRow = i2(0) ++ i2(1) ++ i2(1) ++ i2(0) ++ i2(0)

    // TypeDef rows: Flags, Name, Namespace, Extends, FieldList, MethodList.
    val chainRows = if (typeDefChain > 0) {
      (1 to typeDefChain).map { rid =>
        val extendsValue = if (rid < typeDefChain) (rid + 1) << 2 else 0
        i4(1) ++ i2(0) ++ i2(0) ++ i2(extendsValue) ++ i2(1) ++ i2(1)
      }
    } else {
      Seq.empty
    }

    // Field row: Flags, Name, Signature.
    val fieldRow = if (fieldTable) Some(i2(1) ++ i2(0) ++ i2(fieldSigBlobOffset)) else None

    // Method row: RVA, ImplFlags, Flags, Name, Signature, ParamList.
    val methodRow = if (methodTable) Some(i4(bodyRva) ++ i2(0) ++ i2(0x90) ++ i2(0) ++ i2(methodSigBlobOffset) ++ i2(0)) else None

    val extraRowData = (0 until 64).flatMap { id =>
      extraRowsById.get(id).toSeq.flatten
    }
    head ++ counts ++ moduleRow ++ chainRows.flatten ++ fieldRow.getOrElse(Array.emptyByteArray) ++ methodRow.getOrElse(Array.emptyByteArray) ++ extraRowData
  }

  private def bodyBlob: Array[Byte] = {
    if (methodBodyClaim <= 0) Array.emptyByteArray
    else {
      val flags = 0x13
      i2(flags) ++ i2(8) ++ i4(methodBodyClaim) ++ i4(0) ++ Array[Byte](0x2a.toByte)
    }
  }

  private def streams(): Array[Byte] = {
    val strings = Array[Byte](0, 'A'.toByte, 0) ++ extraStrings
    val blobParts = Array[Byte](1, 0) ++
      (if (deepFieldSigDepth > 0) fieldSigBlob else Array.emptyByteArray) ++
      (if (methodBodyClaim > 0) methodSigBlob else Array.emptyByteArray) ++
      extraBlobs.flatten
    val guid = Array[Byte](0) ++ Array.ofDim[Byte](16)
    val us = Array[Byte](0)
    val t = tableHeap()
    val blobClaim = if (blobHeapSize > 0) blobHeapSize else blobParts.length

    // Stream headers first (sizes are fixed by the names), then the data.
    // Stream offsets are relative to the metadata root start, so they
    // begin after the root prefix (32 bytes) and all five headers.
    val headers = Seq(
      pad4(Seq(i4(0), i4(0), "#~".getBytes("ASCII"), zero(1))),
      pad4(Seq(i4(0), i4(0), "#Strings".getBytes("ASCII"), zero(1))),
      pad4(Seq(i4(0), i4(0), "#Blob".getBytes("ASCII"), zero(1))),
      pad4(Seq(i4(0), i4(0), "#GUID".getBytes("ASCII"), zero(1))),
      pad4(Seq(i4(0), i4(0), "#US".getBytes("ASCII"), zero(1)))
    )
    val headerBytes = headers.flatten.toArray
    val rootPrefix = 32
    var off = rootPrefix + headerBytes.length
    def take(sz: Int): Int = { val o = off; off += sz; o }

    val toff = take(t.length)
    val soff = take(strings.length)
    val boff = take(blobParts.length)
    val goff = take(guid.length)
    val uoff = take(us.length)

    val filled = Seq(
      pad4(Seq(i4(toff), i4(t.length), "#~".getBytes("ASCII"), zero(1))),
      pad4(Seq(i4(soff), i4(strings.length), "#Strings".getBytes("ASCII"), zero(1))),
      pad4(Seq(i4(boff), i4(blobClaim), "#Blob".getBytes("ASCII"), zero(1))),
      pad4(Seq(i4(goff), i4(guid.length), "#GUID".getBytes("ASCII"), zero(1))),
      pad4(Seq(i4(uoff), i4(us.length), "#US".getBytes("ASCII"), zero(1)))
    )
    filled.flatten.toArray ++ t ++ strings ++ blobParts ++ guid ++ us
  }

  private def metadataRoot(): Array[Byte] = {
    val rawVersion = "v4.0.30319".getBytes("ASCII") ++ zero(1)
    val version = rawVersion ++ zero((4 - (rawVersion.length % 4)) % 4)
    val versionLength = versionLengthClaim.getOrElse(version.length)
    val streamData = streams()
    val prefix = i4(0x424a5342) ++ i2(1) ++ i2(1) ++ i4(0) ++ i4(versionLength) ++ version
    prefix ++ i2(0) ++ i2(5) ++ streamData
  }

  private def cliHeader(metadataRva: Int, metadataSize: Int): Array[Byte] = {
    val resourcesDir = if (managedResourceBlob.isDefined) {
      i4(0x2000 + managedResourceOffsetInSection) ++ i4(managedResourceBlob.get.length)
    } else {
      i8(0L)
    }
    i4(0x48) ++ i2(2) ++ i2(5) ++
      i4(metadataRva) ++ i4(metadataSize) ++
      i4(1) ++ i4(0) ++
      resourcesDir ++ i8(0L) ++ i8(0L) ++ i8(0L) ++ i8(0L) ++ i8(0L)
  }

  def build(): Array[Byte] = {
    val metadata = metadataRoot()
    val cli = cliHeader(0x2000 + 72, metadata.length)
    // The body sits at the section offset 0x400 (RVA 0x2400); pad the
    // section content so the file actually contains those bytes. The
    // certificate table and the win32 resource tree (RVA 0x2500) are
    // appended after the body.
    val bodyOffsetInSection = 0x400
    val resourceTreeOffsetInSection = 0x500
    val debugDirectoryOffsetInSection = 0x600
    val managedResourceOffsetInSection = 0x700
    val padBody = Math.max(0, bodyOffsetInSection - (cli.length + metadata.length))
    val certBytes = certificateTable.getOrElse(Array.emptyByteArray)
    val treeBytes = win32ResourceTree.getOrElse(Array.emptyByteArray)
    val debugBytes = debugDirectory.getOrElse(Array.emptyByteArray)
    val managedBytes = managedResourceBlob.getOrElse(Array.emptyByteArray)
    // The pads are cumulative: a large metadata pushes the following
    // blocks past their nominal offsets, so each pad is computed from the
    // running content length.
    val afterBody = cli.length + metadata.length + padBody + bodyBlob.length
    val padTree = Math.max(0, resourceTreeOffsetInSection - afterBody)
    val afterTree = afterBody + padTree + treeBytes.length
    val padDebug = Math.max(0, debugDirectoryOffsetInSection - afterTree)
    val afterDebug = afterTree + padDebug + debugBytes.length
    val padManaged = Math.max(0, managedResourceOffsetInSection - afterDebug)
    val textContent = cli ++ metadata ++ zero(padBody) ++ bodyBlob ++ zero(padTree) ++ treeBytes ++ zero(padDebug) ++ debugBytes ++ zero(padManaged) ++ managedBytes ++ certBytes

    val dosHeader = {
      val h = Array.ofDim[Byte](0x40)
      h(0) = 'M'.toByte
      h(1) = 'Z'.toByte
      h(0x3c) = 0x40.toByte
      h
    }
    val coff = i2(0x14c) ++ i2(sectionCount) ++ i4(0) ++ i4(0) ++ i4(0) ++ i2(0xe0) ++ i2(0x2102)

    val bodyOffset = 0x40 + 4 + 20 + 0xe0 + 40 * sectionCount

    val optional = {
      val o = Array.ofDim[Byte](0xe0)
      // magic 0x10b
      o(0) = 0x0b.toByte
      o(1) = 0x01.toByte
      // linker version
      o(2) = 8
      // subsystem 3 (console) at offset 68 (0x44)
      o(0x44) = 3
      // Security data directory (index 4) at offset 128: overlay
      // convention — the VA is the raw file offset of the table.
      if (certBytes.nonEmpty) {
        val certFileOffset = bodyOffset + textContent.length - certBytes.length
        val va = i4(certFileOffset)
        val sz = i4(certBytes.length)
        for i <- 0 until 4 do o(128 + i) = va(i)
        for i <- 0 until 4 do o(132 + i) = sz(i)
      }
      // Resource data directory (index 2) at offset 112: the tree's RVA.
      if (treeBytes.nonEmpty) {
        val va = i4(0x2000 + resourceTreeOffsetInSection)
        val sz = i4(treeBytes.length)
        for i <- 0 until 4 do o(112 + i) = va(i)
        for i <- 0 until 4 do o(116 + i) = sz(i)
      }
      // Debug data directory (index 6) at offset 144.
      if (debugBytes.nonEmpty) {
        val va = i4(0x2000 + debugDirectoryOffsetInSection)
        val sz = i4(debugBytes.length)
        for i <- 0 until 4 do o(144 + i) = va(i)
        for i <- 0 until 4 do o(148 + i) = sz(i)
      }
      // CLI data directory at offset 208
      if (cliHeaderPresent) {
        val cliRva = i4(0x2000)
        val cliSize = i4(cli.length)
        for i <- 0 until 4 do o(208 + i) = cliRva(i)
        for i <- 0 until 4 do o(212 + i) = cliSize(i)
      }
      o
    }

    val sectionHeaders = (0 until sectionCount).map { i =>
      val name = Array.ofDim[Byte](8)
      val nm = (".text" + i).getBytes("ASCII")
      Array.copy(nm, 0, name, 0, Math.min(nm.length, 8))
      name ++ i4(sectionVirtualSize) ++ i4(0x2000) ++ i4(sectionRawSize) ++ i4(bodyOffset) ++
        i4(0) ++ i4(0) ++ i2(0) ++ i2(0) ++ i4(0x60000020)
    }

    dosHeader ++ Array[Byte]('P'.toByte, 'E'.toByte, 0, 0) ++ coff ++ optional ++
      sectionHeaders.flatten.toArray ++ textContent
  }
}
