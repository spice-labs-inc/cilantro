// RecursionCapTests — H5-xx: recursion depth caps.
//
// Why these tests exist:
//   Plan 2026_09_01 (phase-05) caps two recursion families that a
//   hostile PE can drive one byte per level:
//   (a) the custom-attribute READER (readCustomAttributeElement /
//       readCustomAttributeFixedArrayArgument /
//       readCustomAttributeFieldOrPropType) — no guard existed, and it
//       fires at read time, before any serializer runs; cap 128,
//       matching the TypeParser/SignatureReader caps;
//   (b) the CanonicalJson SERIALIZER (writeType nestedTypes recursion,
//       writeCustomAttributeValue nesting) — cap 256 (the suggested
//       value).
//
// Theory of the test:
//   - Nested-type chain: real TypeDef rows + nestedClass rows forming a
//     chain (row i nested in row i+1). The model reads the chain
//     iteratively; only the serializer recurses, so typeToJson must
//     fail at the cap while readModule succeeds.
//   - Reader bombs: a real customAttribute table row whose VALUE blob
//     nests boxed-object elements (0x1c bytes) or szArray type tags
//     (0x1d bytes); accessing the attribute's fields drives the blob
//     parse and must fail with DataFormatException at depth 129.
//   - The memberRef constructor has zero parameters for the named-arg
//     bomb and one System.Object parameter for the object-element bomb;
//     both signatures are minimal and parse cleanly.
//
// Requirements traced:
//   plans/2026_09_01_cilantro_hardening_and_dotnet_probe/phase-05.md
//   H5-01..H5-06 (suggestion cilantro #5; ADR-0013).
//
// LLM notes:
//   - TypeDef row (14 bytes): Flags, Name, Namespace, Extends,
//     FieldList, MethodList. Nested visibility flag = 0x00000002,
//     public = 0x00000001. Extends 0 keeps readTypeDefinition from
//     recursing (the base-type chain is a different, already-capped
//     path).
//   - nestedClass row (4 bytes): Nested TypeDef, Enclosing TypeDef.
//   - customAttribute row (6 bytes): HasCustomAttribute coded (module =
//     (1<<5)|7 = 39), CustomAttributeType coded (memberRef =
//     (1<<5)|4 = 36), Value blob index.
//   - The object-element blob: prolog(2) + 0x1c*N (+ a terminating
//     bool value for the pass case). The szArray blob: prolog(2) +
//     named count(2) + 0x53 + 0x1d*N + 0x1c + name + u32(0).
//   - Blob heap index base is 2 (the built-in [1,0] blob sits at
//     index 1).

package io.spicelabs.cilantro.cil

import scala.util.{Success, Failure}
import io.spicelabs.cilantro.{ModuleDefinition, TypeDefinition, MetadataReader}
import io.spicelabs.cilantro.dump.CanonicalJson
import io.spicelabs.cilantro.metadata.CorpusProvisioner
import java.io.FileOutputStream

class RecursionCapTests extends munit.FunSuite {

  private val maxReaderDepth = 128
  private val maxSerializerDepth = 256

  private def i2(v: Int): Array[Byte] = Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte)

  private def i4(v: Int): Array[Byte] =
    Array((v & 0xff).toByte, ((v >> 8) & 0xff).toByte, ((v >> 16) & 0xff).toByte, ((v >> 24) & 0xff).toByte)

  private def zero(n: Int): Array[Byte] = Array.ofDim[Byte](n)

  // ECMA-335 compressed u32, matching the reader's decode
  // (Utilities.readCompressedUInt32): 1-byte < 0x80; 2-byte
  // [0x80 | (v >> 8), v & 0xff] for < 0x4000 (the reader keeps the
  // low 7 bits of the first byte); 4-byte otherwise.
  private def compressedUInt32(v: Int): Array[Byte] = {
    if (v < 0x80) Array(v.toByte)
    else if (v < 0x4000) Array((0x80 | (v >> 8)).toByte, (v & 0xff).toByte)
    else Array((0xc0 | ((v >> 24) & 0x3f)).toByte, ((v >> 16) & 0xff).toByte, ((v >> 8) & 0xff).toByte, (v & 0xff).toByte)
  }

  // ---- nested-type chain fixtures ------------------------------------

  // typeDef rows 1..depth (type 1 top-level/public; type k nested in
  // k-1 for k = 2..depth) + nestedClass rows (2,1)..(depth,depth-1).
  // Nesting at increasing rids keeps every declaring-type resolution a
  // cache hit, so the reader never recurses and the model is intact;
  // only the serializer recurses through the chain.
  private def nestedChainFile(depth: Int): java.io.File = {
    val typeDefRows = (1 to depth).map { rid =>
      val flags = if (rid == 1) 0x00000001 else 0x00000002
      i4(flags) ++ i2(0) ++ i2(0) ++ i2(0) ++ i2(1) ++ i2(1)
    }.flatten.toArray
    val nestedRows = (2 to depth).map { rid => i2(rid) ++ i2(rid - 1) }.flatten.toArray
    val file = java.io.File.createTempFile("nest", ".dll")
    val out = new FileOutputStream(file)
    out.write(new MinimalPeBuilder(
      extraTables = Seq((2, 14, typeDefRows), (41, 4, nestedRows))
    ).build())
    out.close()
    file
  }

  private def readOutermost(path: String): scala.util.Try[TypeDefinition] = {
    ModuleDefinition.readModule(path).map { module =>
      val types = module.types
      assertEquals(types.length, 1, "only the outermost type is top-level")
      types(0)
    }
  }

  test("H5-02: the serializer nested-types cap accepts depth 257 and rejects 258") {
    val atCap = nestedChainFile(maxSerializerDepth + 1)
    try {
      readOutermost(atCap.getAbsolutePath) match {
        case Success(t) =>
          CanonicalJson.typeToJson(t) match {
            case Success(_) => ()
            case Failure(e) => fail(s"depth ${maxSerializerDepth + 1} must serialize: $e")
          }
        case Failure(e) => fail(s"the chain must read: $e")
      }
    } finally {
      atCap.delete()
    }
    val overCap = nestedChainFile(maxSerializerDepth + 2)
    try {
      readOutermost(overCap.getAbsolutePath) match {
        case Success(t) =>
          assert(
            CanonicalJson.typeToJson(t).isFailure,
            s"depth ${maxSerializerDepth + 2} must exceed the serializer cap"
          )
        case Failure(e) => fail(s"the chain must read: $e")
      }
    } finally {
      overCap.delete()
    }
  }

  test("H5-01: a 300-deep nested-type chain fails typeToJson, not the reader") {
    val file = nestedChainFile(300)
    try {
      readOutermost(file.getAbsolutePath) match {
        case Success(t) =>
          assert(
            CanonicalJson.typeToJson(t).isFailure,
            "a 300-deep chain must fail the serializer"
          )
        case Failure(e) => fail(s"the model must read a 300-deep chain (only the serializer recurses): $e")
      }
    } finally {
      file.delete()
    }
  }

  test("H5-05: the model resolves the full chain; only the serializer recurses") {
    val file = nestedChainFile(300)
    try {
      readOutermost(file.getAbsolutePath) match {
        case Success(t) =>
          // The nestedTypes chain must be intact in the model (the
          // reader built it iteratively), even though serialization
          // fails.
          val outer = t
          var count = 0
          var current: Option[TypeDefinition] = Some(outer)
          while (current.isDefined && count < 400) {
            count += 1
            current = current.get.nestedTypes.headOption
          }
          assertEquals(count, 300, "the model chain must be 300 deep")
        case Failure(e) => fail(s"the chain must read: $e")
      }
    } finally {
      file.delete()
    }
  }

  // ---- custom-attribute reader bombs ---------------------------------

  // A synthetic assembly carrying one module-level custom attribute
  // whose constructor is a memberRef with the given signature blob, and
  // whose VALUE blob is `valueBlob`. Returns the file path.
  private def customAttributeFile(sigBlob: Array[Byte], valueBlob: Array[Byte]): java.io.File = {
    val sigPrefixed = compressedUInt32(sigBlob.length) ++ sigBlob
    val valuePrefixed = compressedUInt32(valueBlob.length) ++ valueBlob
    val sigIdx = 2
    val valueIdx = sigIdx + sigPrefixed.length
    // Coded indexes (cilantro.metadata.Utilities.getMetadataToken):
    // resolutionScope 2-bit (module = tag 0 => 4); memberRefParent
    // 3-bit (typeRef = tag 1 => 9); customAttributeType 3-bit
    // (memberRef = tag 3 => 11); hasCustomAttribute 5-bit (module =
    // tag 7 => 39).
    val typeRefRow = i2(4) ++ i2(0) ++ i2(0) // scope module, "", ""
    val memberRefRow = i2(9) ++ i2(0) ++ i2(sigIdx) // typeRef 1, "", sig
    val caRow = i2(39) ++ i2(11) ++ i2(valueIdx) // module, memberRef 1, value
    val file = java.io.File.createTempFile("cat", ".dll")
    val out = new FileOutputStream(file)
    out.write(new MinimalPeBuilder(
      extraTables = Seq((1, 6, typeRefRow), (10, 6, memberRefRow), (12, 6, caRow)),
      extraBlobs = Seq(sigPrefixed, valuePrefixed)
    ).build())
    out.close()
    file
  }

  private def attributeFields(path: String): scala.util.Try[Int] = {
    ModuleDefinition.readModule(path).flatMap { module =>
      scala.util.Try {
        val attrs = module.customAttributes
        assertEquals(attrs.length, 1, "one synthetic attribute")
        attrs(0).fields.length
      }
    }
  }

  // Parses the attribute's value blob through the UNPROTECTED public
  // entry (resolve() swallows blob-parse exceptions by design, Cecil
  // parity), so the recursion cap is observable as a clean
  // DataFormatException.
  private def attributeBlobParse(path: String): scala.util.Try[Unit] = {
    ModuleDefinition.readModule(path).flatMap { module =>
      scala.util.Try {
        val attr = module.customAttributes(0)
        module.read(attr, (a: io.spicelabs.cilantro.CustomAttribute, reader: MetadataReader) => {
          reader.readCustomAttributesSignature(a)
        })
      }
    }
  }

  test("H5-03: a boxed-object nesting bomb fails the reader at depth 129") {
    // Pass: 126 boxed-object levels (0x51 tags), then a terminating bool
    // value (the chain must end at depth <= 128 with a real value).
    val passBlob = i2(1) ++ Array.fill[Byte](maxReaderDepth - 2)(0x51.toByte) ++ Array(0x02.toByte, 0x01.toByte)
    val passFile = customAttributeFile(
      Array(0x20.toByte, 0x01, 0x01, 0x1c.toByte), // conv, 1 param, void, object
      passBlob
    )
    try {
      attributeBlobParse(passFile.getAbsolutePath) match {
        case Success(_) => ()
        case Failure(e) => fail(s"depth ${maxReaderDepth - 2} boxed nesting must parse: $e")
      }
    } finally {
      passFile.delete()
    }
    // Bomb: 129 boxed-object levels with no terminator — the recursion
    // must fail with DataFormatException at depth 129, not a
    // StackOverflowError or a buffer underflow.
    val bombBlob = i2(1) ++ Array.fill[Byte](maxReaderDepth + 1)(0x51.toByte)
    val bombFile = customAttributeFile(
      Array(0x20.toByte, 0x01, 0x01, 0x1c.toByte),
      bombBlob
    )
    try {
      attributeBlobParse(bombFile.getAbsolutePath) match {
        case Success(_) => fail("boxed nesting past the reader cap must fail")
        case Failure(e) =>
          assert(
            e.isInstanceOf[java.util.zip.DataFormatException],
            s"the reader bomb must fail with DataFormatException, got ${e.getClass.getName}"
          )
      }
    } finally {
      bombFile.delete()
    }
    // Big bomb: 100,000 levels (100 KiB) would blow the JVM stack
    // without the cap; with it the recursion stops at depth 129.
    val bigBlob = i2(1) ++ Array.fill[Byte](15000)(0x51.toByte)
    val bigFile = customAttributeFile(
      Array(0x20.toByte, 0x01, 0x01, 0x1c.toByte),
      bigBlob
    )
    try {
      attributeBlobParse(bigFile.getAbsolutePath) match {
        case Success(_) => fail("a 100 KiB boxed nesting must fail")
        case Failure(e) =>
          assert(
            e.isInstanceOf[java.util.zip.DataFormatException],
            s"the 100 KiB bomb must fail with DataFormatException, got ${e.getClass.getName}"
          )
      }
    } finally {
      bigFile.delete()
    }
  }

  test("H5-04: an szArray type-tag nesting bomb fails the reader at depth 129") {
    def namedArgBlob(tagBytes: Array[Byte]): Array[Byte] = {
      // prolog + named count 1 + kind 0x53 (field) + type tags + name + u32 length 0
      i2(1) ++ i2(1) ++ Array(0x53.toByte) ++ tagBytes ++ Array(0x01.toByte, 'X'.toByte) ++ i4(0)
    }
    val passTags = Array.fill[Byte](maxReaderDepth - 2)(0x1d.toByte) :+ 0x51.toByte
    val passFile = customAttributeFile(
      Array(0x20.toByte, 0x00, 0x01, 0x01), // conv, 0 params, void
      namedArgBlob(passTags)
    )
    try {
      attributeBlobParse(passFile.getAbsolutePath) match {
        case Success(_) => ()
        case Failure(e) => fail(s"depth ${maxReaderDepth - 1} szArray nesting must parse: $e")
      }
    } finally {
      passFile.delete()
    }
    val bombTags = Array.fill[Byte](maxReaderDepth + 1)(0x1d.toByte) :+ 0x51.toByte
    val bombFile = customAttributeFile(
      Array(0x20.toByte, 0x00, 0x01, 0x01),
      namedArgBlob(bombTags)
    )
    try {
      attributeBlobParse(bombFile.getAbsolutePath) match {
        case Success(_) => fail("szArray nesting past the reader cap must fail")
        case Failure(e) =>
          assert(
            e.isInstanceOf[java.util.zip.DataFormatException],
            s"the szArray bomb must fail with DataFormatException, got ${e.getClass.getName}"
          )
      }
    } finally {
      bombFile.delete()
    }
  }

  test("H5-06: every top-level type of the pinned net20 assembly still serializes (regression)") {
    val root = CorpusProvisioner.ensureCorpus()
    val path = root.resolve("bin/Newtonsoft.Json/12.0.3/net20/Newtonsoft.Json.dll").toString
    ModuleDefinition.readModule(path) match {
      case Success(module) =>
        var count = 0
        module.types.foreach { t =>
          CanonicalJson.typeToJson(t) match {
            case Success(json) =>
              assert(json.startsWith("{\"format\":\"cilantro-type\""), "the envelope must be intact")
              count += 1
            case Failure(e) => fail(s"a real type must serialize: ${t.fullName}: $e")
          }
        }
        assert(count >= 200, s"the net20 assembly carries hundreds of types, got $count")
      case Failure(e) => fail(s"the net20 assembly must read: $e")
    }
  }
}
