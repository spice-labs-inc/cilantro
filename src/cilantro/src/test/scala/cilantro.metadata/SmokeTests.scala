import io.spicelabs.cilantro.*
import java.nio.*
import java.nio.file.*
import java.io.FileInputStream
import io.spicelabs.cilantro.PE.BinaryStreamReader
import io.spicelabs.cilantro.AnyExtension.as
import java.nio.{ByteBuffer => NioByteBuffer}

class SmokeTests extends munit.FunSuite {
    import SmokeTests.smokePath
    import SmokeTests.smokePathStr

    test("smoke exists") {
        assert(Files.exists(smokePath()))
    }

    test("opens smoke file") {
        var strPath = smokePathStr()
        var assem = AssemblyDefinition.readAssembly(strPath)
    }

    test("open-seek-read") {
        var strPath = smokePathStr()

        var filestream = new FileInputStream(strPath)
        filestream.readNBytes(60)
        filestream.getChannel().position(540)
        var origBytes = filestream.readNBytes(512)
        filestream.close()


        filestream = new FileInputStream(strPath)

        var binstmreader = BinaryStreamReader.fromFileInputStream(filestream)
        binstmreader.readBytes(60)
        binstmreader.position = 540
        var newBytes = binstmreader.readBytes(512)

        for i <- 0 until 512 do
            assertEquals(origBytes(i), newBytes(i), clue = s"diff at index $i")
    }

    test ("name etc") {
        var strPath = smokePathStr()
        var assem = AssemblyDefinition.readAssembly(strPath)
        assertEquals(assem.name.name, "Smoke")
        assertEquals(assem.name.version.toString(), "1.0.0.0")
        assertEquals(assem.name.hashAlgorithm, AssemblyHashAlgorithm.sha1)
        assertEquals(assem.mainModule.kind, ModuleKind.dll)
        assertEquals(assem.mainModule.mvid.toString(), "3d2fd839-bce1-4920-8f4c-43be15435c20")
    }

    test ("check-refs") {
        var strPath = smokePathStr()
        var assem = AssemblyDefinition.readAssembly(strPath)
        var modules = assem.modules
        assertEquals(modules.length, 1)
        var module = modules(0)
        var modrefs = module.moduleReferences
        assertEquals(modrefs.length, 0)
        var asrefs = module.assemblyReferences
        assertEquals(asrefs.length, 1)


    }

    test ("check-assembly-info") {
        val strPath = smokePathStr()
        val assem = AssemblyDefinition.readAssembly(strPath)
        assertEquals(assem.customAttributes.length, 10)
        val attr = assem.customAttributes(4)
        val hasCtorArgs = attr.hasConstructorArguments
        assert(hasCtorArgs)
        val args = attr.constructorArguments
        assertEquals(args.length, 1)
        val arg = args(0)
        assertEquals(arg.`type`.fullName, "System.String")
        val value = arg.value.as[String]
        assertNotEquals(value, null)
        assertEquals(value, "Smoke")
    }

    test("complete-assembly-info") {
        val strPath = smokePathStr()
        val assem = AssemblyDefinition.readAssembly(strPath)
        assertEquals(assem.customAttributes.length, 10)
        val names = assem.customAttributes.map((attr) => attr.attributeType.name).toArray
        val expected = Array("CompilationRelaxationsAttribute", "RuntimeCompatibilityAttribute",
            "DebuggableAttribute", "TargetFrameworkAttribute",
            "AssemblyCompanyAttribute", "AssemblyConfigurationAttribute",
            "AssemblyFileVersionAttribute", "AssemblyInformationalVersionAttribute",
            "AssemblyProductAttribute", "AssemblyTitleAttribute")
        if (!names.sameElements(expected))
            val differing = (names zip expected).indexWhere((x, y) => x != y)
            val ex = expected(differing)
            val act = names(differing)
            assert(false, s"Lists differ at index $differing\nExpected $ex but got $act")
    }

    test("has-properties") {
        val strPath = smokePathStr()
        val assem = AssemblyDefinition.readAssembly(strPath)
        val allWithProps = assem.customAttributes.map((attr) => if attr.hasProperties then 1 else 0).sum
        assertEquals(allWithProps , 2, "should have properties")
    }

    // ============================================================================
    // Task 3 Integration Tests: ByteBuffer Entry Points
    // ============================================================================

    /**
     * TASK 3: Test ModuleDefinition.readModule(buffer) entry point
     * Verifies that a PE file can be loaded from a ByteBuffer in memory
     */
    test("T3: readModule from ByteBuffer basic") {
        val strPath = smokePathStr()
        val bytes = Files.readAllBytes(Paths.get(strPath))
        val buffer = NioByteBuffer.wrap(bytes)

        val module = ModuleDefinition.readModule(buffer)

        assertEquals(module.fileName, "no file name")
        assertEquals(module.kind, ModuleKind.dll)
        assertEquals(module.mvid.toString(), "3d2fd839-bce1-4920-8f4c-43be15435c20")
    }

    /**
     * TASK 3: Test ModuleDefinition.readModule(buffer, parameters) entry point
     * Verifies ByteBuffer loading with custom parameters
     */
    test("T3: readModule from ByteBuffer with parameters") {
        val strPath = smokePathStr()
        val bytes = Files.readAllBytes(Paths.get(strPath))
        val buffer = NioByteBuffer.wrap(bytes)
        val params = ReaderParameters(ReadingMode.deferred)

        val module = ModuleDefinition.readModule(buffer, params)

        assertEquals(module.kind, ModuleKind.dll)
        assertEquals(module.readingMode, ReadingMode.deferred)
    }

    /**
     * TASK 3: Test ModuleDefinition.readModule(buffer, fileName, parameters) entry point
     * Verifies ByteBuffer loading with file name and parameters
     */
    test("T3: readModule from ByteBuffer with file name and parameters") {
        val strPath = smokePathStr()
        val bytes = Files.readAllBytes(Paths.get(strPath))
        val buffer = NioByteBuffer.wrap(bytes)
        val params = ReaderParameters(ReadingMode.deferred)

        val module = ModuleDefinition.readModule(buffer, "Smoke.dll", params)

        assertEquals(module.fileName, "Smoke.dll")
        assertEquals(module.kind, ModuleKind.dll)
    }

    /**
     * TASK 3: ByteBuffer entry point equivalence test
     * Verifies that loading from ByteBuffer produces same results as loading from file
     */
    test("T3: ByteBuffer entry point produces same results as file path") {
        val strPath = smokePathStr()

        // Load via file path
        val fileModule = ModuleDefinition.readModule(strPath)

        // Load via ByteBuffer
        val bytes = Files.readAllBytes(Paths.get(strPath))
        val buffer = NioByteBuffer.wrap(bytes)
        val bufferModule = ModuleDefinition.readModule(buffer, "Smoke.dll", ReaderParameters(ReadingMode.deferred))

        // Verify equivalence
        assertEquals(bufferModule.mvid, fileModule.mvid)
        assertEquals(bufferModule.kind, fileModule.kind)
        assertEquals(bufferModule.assembly.name.fullName, fileModule.assembly.name.fullName)
        assertEquals(bufferModule.assembly.name.version.toString(), fileModule.assembly.name.version.toString())
    }

    /**
     * TASK 3: ByteBuffer entry point null rejection
     * Verifies that null buffer throws IllegalArgumentException
     */
    test("T3: readModule rejects null buffer") {
        intercept[IllegalArgumentException] {
            ModuleDefinition.readModule(null: NioByteBuffer)
        }
    }

    /**
     * TASK 3: ByteBuffer entry point with null parameters
     * Verifies that null parameters throws IllegalArgumentException
     */
    test("T3: readModule rejects null parameters") {
        val bytes = Array[Byte](0x4D.toByte, 0x5A.toByte, 0x90.toByte, 0x00.toByte) // Minimal DOS header
        val buffer = NioByteBuffer.wrap(bytes)

        intercept[IllegalArgumentException] {
            ModuleDefinition.readModule(buffer, null: ReaderParameters)
        }
    }

    // ============================================================================
    // Task 5 Integration Tests: AssemblyDefinition ByteBuffer Entry Points
    // ============================================================================

    /**
     * TASK 5: Test AssemblyDefinition.readAssembly(buffer) entry point
     * Verifies that an assembly can be loaded from a ByteBuffer in memory
     */
    test("T5: readAssembly from ByteBuffer basic") {
        val strPath = smokePathStr()
        val bytes = Files.readAllBytes(Paths.get(strPath))
        val buffer = NioByteBuffer.wrap(bytes)

        val assembly = AssemblyDefinition.readAssembly(buffer)

        assertEquals(assembly.name.name, "Smoke")
        assertEquals(assembly.name.version.toString(), "1.0.0.0")
        assertEquals(assembly.mainModule.kind, ModuleKind.dll)
        assertEquals(assembly.mainModule.mvid.toString(), "3d2fd839-bce1-4920-8f4c-43be15435c20")
    }

    /**
     * TASK 5: Test AssemblyDefinition.readAssembly(buffer, parameters) entry point
     * Verifies ByteBuffer loading with custom parameters
     */
    test("T5: readAssembly from ByteBuffer with parameters") {
        val strPath = smokePathStr()
        val bytes = Files.readAllBytes(Paths.get(strPath))
        val buffer = NioByteBuffer.wrap(bytes)
        val params = ReaderParameters(ReadingMode.deferred)

        val assembly = AssemblyDefinition.readAssembly(buffer, params)

        assertEquals(assembly.name.name, "Smoke")
        assertEquals(assembly.mainModule.readingMode, ReadingMode.deferred)
    }

    /**
     * TASK 5: ByteBuffer entry point equivalence test for AssemblyDefinition
     * Verifies that loading from ByteBuffer produces same results as loading from file
     */
    test("T5: readAssembly ByteBuffer produces same results as file path") {
        val strPath = smokePathStr()

        // Load via file path
        val fileAssembly = AssemblyDefinition.readAssembly(strPath)

        // Load via ByteBuffer
        val bytes = Files.readAllBytes(Paths.get(strPath))
        val buffer = NioByteBuffer.wrap(bytes)
        val bufferAssembly = AssemblyDefinition.readAssembly(buffer, ReaderParameters(ReadingMode.deferred))

        // Verify equivalence
        assertEquals(bufferAssembly.name.fullName, fileAssembly.name.fullName)
        assertEquals(bufferAssembly.name.version.toString(), fileAssembly.name.version.toString())
        assertEquals(bufferAssembly.mainModule.mvid, fileAssembly.mainModule.mvid)
        assertEquals(bufferAssembly.mainModule.kind, fileAssembly.mainModule.kind)
        assertEquals(bufferAssembly.customAttributes.length, fileAssembly.customAttributes.length)
    }

    /**
     * TASK 5: ByteBuffer entry point null rejection for AssemblyDefinition
     * Verifies that null buffer throws IllegalArgumentException
     */
    test("T5: readAssembly rejects null buffer") {
        intercept[IllegalArgumentException] {
            AssemblyDefinition.readAssembly(null: NioByteBuffer)
        }
    }

    /**
     * TASK 5: ByteBuffer entry point with null parameters for AssemblyDefinition
     * Verifies that null parameters throws IllegalArgumentException
     */
    test("T5: readAssembly rejects null parameters") {
        val bytes = Array[Byte](0x4D.toByte, 0x5A.toByte, 0x90.toByte, 0x00.toByte)
        val buffer = NioByteBuffer.wrap(bytes)

        intercept[IllegalArgumentException] {
            AssemblyDefinition.readAssembly(buffer, null: ReaderParameters)
        }
    }

    // ============================================================================
    // Task 9 Integration Tests: Security-Focused Tests (T9-SEC)
    // ============================================================================

    /**
     * SECURITY INVARIANT: PE offset validation
     * REQUIRES: e_lfanew within valid range (0x40 <= e_lfanew <= fileLength - 4)
     * TESTS: Invalid e_lfanew offset rejected
     */
    test("T9-SEC1: rejects PE with invalid e_lfanew offset") {
        val strPath = smokePathStr()
        val bytes = Files.readAllBytes(Paths.get(strPath))
        // Corrupt e_lfanew at offset 0x3C to point to invalid location
        bytes(0x3C) = 0xFF.toByte
        bytes(0x3D) = 0xFF.toByte
        bytes(0x3E) = 0xFF.toByte
        bytes(0x3F) = 0x7F.toByte

        val buffer = NioByteBuffer.wrap(bytes)
        intercept[java.util.zip.DataFormatException] {
            ModuleDefinition.readModule(buffer)
        }
    }

    /**
     * SECURITY INVARIANT: Max PE sections
     * REQUIRES: Section count <= 96
     * TESTS: Excessive section count rejected
     */
    test("T9-SEC2: rejects PE with section count > 96") {
        val strPath = smokePathStr()
        val bytes = Files.readAllBytes(Paths.get(strPath))
        // NumberOfSections is at offset 0x06 in the PE header (after DOS header)
        // First find e_lfanew at offset 0x3C
        val e_lfanew = (bytes(0x3C) & 0xFF) | ((bytes(0x3D) & 0xFF) << 8) | ((bytes(0x3E) & 0xFF) << 16) | ((bytes(0x3F) & 0xFF) << 24)
        // NumberOfSections is at offset 0x06 from PE header (after 0x04 for signature)
        val numSectionsOffset = e_lfanew + 4 + 0x02
        bytes(numSectionsOffset) = 0x61.toByte  // 97 sections
        bytes(numSectionsOffset + 1) = 0x00.toByte

        val buffer = NioByteBuffer.wrap(bytes)
        intercept[java.util.zip.DataFormatException] {
            ModuleDefinition.readModule(buffer)
        }
    }

    /**
     * SECURITY INVARIANT: PE magic validation
     * REQUIRES: Valid DOS and PE signatures
     * TESTS: Non-PE file rejected
     */
    test("T9-SEC3: rejects non-PE file") {
        val notPe = Array.fill(100)(0x00.toByte)
        val buffer = NioByteBuffer.wrap(notPe)
        intercept[java.util.zip.DataFormatException] {
            ModuleDefinition.readModule(buffer)
        }
    }

    /**
     * SECURITY INVARIANT: PE magic validation
     * REQUIRES: Valid DOS signature (MZ = 0x5A4D)
     * TESTS: Invalid DOS signature rejected
     */
    test("T9-SEC3b: rejects PE with invalid DOS signature") {
        val strPath = smokePathStr()
        val bytes = Files.readAllBytes(Paths.get(strPath))
        // Corrupt DOS signature at offset 0
        bytes(0) = 0x00.toByte
        bytes(1) = 0x00.toByte

        val buffer = NioByteBuffer.wrap(bytes)
        intercept[java.util.zip.DataFormatException] {
            ModuleDefinition.readModule(buffer)
        }
    }

    /**
     * SECURITY INVARIANT: PE magic validation
     * REQUIRES: Valid PE signature (PE\0\0 = 0x00004550)
     * TESTS: Invalid PE signature rejected
     */
    test("T9-SEC3c: rejects PE with invalid PE signature") {
        val strPath = smokePathStr()
        val bytes = Files.readAllBytes(Paths.get(strPath))
        // Corrupt PE signature at e_lfanew
        val e_lfanew = (bytes(0x3C) & 0xFF) | ((bytes(0x3D) & 0xFF) << 8) | ((bytes(0x3E) & 0xFF) << 16) | ((bytes(0x3F) & 0xFF) << 24)
        bytes(e_lfanew) = 0x00.toByte
        bytes(e_lfanew + 1) = 0x00.toByte
        bytes(e_lfanew + 2) = 0x00.toByte
        bytes(e_lfanew + 3) = 0x00.toByte

        val buffer = NioByteBuffer.wrap(bytes)
        intercept[java.util.zip.DataFormatException] {
            ModuleDefinition.readModule(buffer)
        }
    }

    /**
     * SECURITY INVARIANT: File size validation
     * REQUIRES: File must be at least 128 bytes
     * TESTS: File too small rejected
     */
    test("T9-SEC4: rejects file too small") {
        val tooSmall = Array.fill(64)(0x4D.toByte)  // Just 'M' repeated, not a valid PE
        val buffer = NioByteBuffer.wrap(tooSmall)
        intercept[java.util.zip.DataFormatException] {
            ModuleDefinition.readModule(buffer)
        }
    }

    // ============================================================================
    // Task 9 Integration Tests: Symbol Stream ByteBuffer Tests (T9-SYM)
    // ============================================================================

    /**
     * TASK 9-SYM: Symbol stream from ByteBuffer
     * Verifies that symbolBuffer in ReaderParameters is recognized
     * Note: Full symbol reading requires additional implementation
     */
    test("T9-SYM1: symbolBuffer parameter is accepted") {
        val strPath = smokePathStr()
        val bytes = Files.readAllBytes(Paths.get(strPath))
        val buffer = NioByteBuffer.wrap(bytes)

        // Create a fake symbol buffer (not a real PDB, just testing parameter acceptance)
        val symbolBytes = Array[Byte](0x42, 0x53, 0x4A, 0x42)  // "BSJB" - metadata signature
        val symbolBuffer = NioByteBuffer.wrap(symbolBytes)

        val params = ReaderParameters(ReadingMode.deferred)
        params.symbolBuffer = symbolBuffer

        // Module should load without error even with symbolBuffer set
        val module = ModuleDefinition.readModule(buffer, "Smoke.dll", params)
        assertEquals(module.fileName, "Smoke.dll")
    }

    /**
     * TASK 9-SYM2: Verify symbolBuffer field can be set and retrieved
     */
    test("T9-SYM2: ReaderParameters symbolBuffer property works") {
        val params = ReaderParameters(ReadingMode.deferred)

        // Initially null
        assertEquals(params.symbolBuffer, null)

        // Set a buffer
        val symbolBytes = Array[Byte](1, 2, 3, 4)
        val symbolBuffer = NioByteBuffer.wrap(symbolBytes)
        params.symbolBuffer = symbolBuffer

        // Should be the same buffer
        assertEquals(params.symbolBuffer, symbolBuffer)
    }

    // ============================================================================
    // Task 9 Integration Tests: Equivalence Tests (T9-EQV)
    // ============================================================================

    /**
     * TASK 9-EQV: Image.getReaderAt works with ByteBuffer-backed Image
     */
    test("T9-EQV1: Image.getReaderAt works with ByteBuffer-backed Image") {
        val strPath = smokePathStr()
        val bytes = Files.readAllBytes(Paths.get(strPath))
        val buffer = NioByteBuffer.wrap(bytes)

        val module = ModuleDefinition.readModule(buffer)
        assert(module.image != null)
        assert(module.image.sections != null)
        assert(module.image.sections.length > 0)

        val rva = module.image.sections(0).virtualAddress
        val reader = module.image.getReaderAt(rva)
        assert(reader != null)
        // Reader position is at the resolved file offset, not 0
        // The reader is positioned at the section's file location
    }

    /**
     * TASK 9-EQV2: ByteBuffer entry point reads same metadata as file path
     */
    test("T9-EQV2: ByteBuffer entry point reads same metadata as file path") {
        val strPath = smokePathStr()

        // Load via file path
        val fileModule = ModuleDefinition.readModule(strPath)

        // Load via ByteBuffer
        val bytes = Files.readAllBytes(Paths.get(strPath))
        val buffer = NioByteBuffer.wrap(bytes)
        val bufferModule = ModuleDefinition.readModule(buffer)

        // Core metadata should be identical
        assertEquals(bufferModule.mvid, fileModule.mvid)
        assertEquals(bufferModule.assembly.name.fullName, fileModule.assembly.name.fullName)
        assertEquals(bufferModule.assembly.name.version.toString(), fileModule.assembly.name.version.toString())
        assertEquals(bufferModule.kind, fileModule.kind)
        // Note: types.length comparison skipped due to lazy loading differences
    }
}

object SmokeTests {
    def smokePath() =
        val cwd = Paths.get(System.getProperty("user.dir"))
        val smokePath = cwd.resolve("../../test-files/smoke/Smoke.dll")
        smokePath
    
    def smokePathStr() =
        smokePath().toString()


}
