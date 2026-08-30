import io.spicelabs.cilantro.*
import java.nio.file.*
import java.io.FileInputStream
import io.spicelabs.cilantro.PE.BinaryStreamReader
import io.spicelabs.cilantro.AnyExtension.as

class SmokeTests extends munit.FunSuite {
    import SmokeTests.smokePath
    import SmokeTests.smokePathStr

    test("smoke exists") {
        assert(Files.exists(smokePath()))
    }

    test("opens smoke file") {
        val strPath = smokePathStr()
        val assem = AssemblyDefinition.readAssembly(strPath)
        assert(assem.isSuccess)
    }

    test("open-seek-read") {
        val strPath = smokePathStr()

        var filestream = new FileInputStream(strPath)
        filestream.readNBytes(60)
        filestream.getChannel().position(540)
        val origBytes = filestream.readNBytes(512)
        filestream.close()


        filestream = new FileInputStream(strPath)

        val binstmreader = BinaryStreamReader(filestream)
        binstmreader.readBytes(60)
        binstmreader.position = 540
        val newBytes = binstmreader.readBytes(512)

        for i <- 0 until 512 do {
            assertEquals(origBytes(i), newBytes(i), clue = s"diff at index $i")
        }
    }

    test ("name etc") {
        val strPath = smokePathStr()
        val assem = AssemblyDefinition.readAssembly(strPath).get
        assertEquals(assem.name.get.name, "Smoke")
        assertEquals(assem.name.get.version.toString(), "1.0.0.0")
        assertEquals(assem.name.get.hashAlgorithm, AssemblyHashAlgorithm.sha1)
        assertEquals(assem.mainModule.get.kind, ModuleKind.dll)
        assertEquals(assem.mainModule.get.mvid.toString(), "3d2fd839-bce1-4920-8f4c-43be15435c20")
    }

    test ("check-refs") {
        val strPath = smokePathStr()
        val assem = AssemblyDefinition.readAssembly(strPath).get
        val modules = assem.modules
        assertEquals(modules.length, 1)
        val module = modules(0)
        val modrefs = module.moduleReferences
        assertEquals(modrefs.length, 0)
        val asrefs = module.assemblyReferences
        assertEquals(asrefs.length, 1)


    }

    test ("check-assembly-info") {
        val strPath = smokePathStr()
        val assem = AssemblyDefinition.readAssembly(strPath).get
        assertEquals(assem.customAttributes.length, 10)
        val attr = assem.customAttributes(4)
        val hasCtorArgs = attr.hasConstructorArguments
        assert(hasCtorArgs)
        val args = attr.constructorArguments
        assertEquals(args.length, 1)
        val arg = args(0)
        assertEquals(arg.`type`.fullName, "System.String")
        val value = arg.value.as[String]
        assertEquals(value, Some("Smoke"))
    }

    test("complete-assembly-info") {
        val strPath = smokePathStr()
        val assem = AssemblyDefinition.readAssembly(strPath).get
        assertEquals(assem.customAttributes.length, 10)
        val names = assem.customAttributes.map((attr) => attr.attributeType.map(_.name).getOrElse("")).toArray
        val expected = Array("CompilationRelaxationsAttribute", "RuntimeCompatibilityAttribute",
            "DebuggableAttribute", "TargetFrameworkAttribute",
            "AssemblyCompanyAttribute", "AssemblyConfigurationAttribute",
            "AssemblyFileVersionAttribute", "AssemblyInformationalVersionAttribute",
            "AssemblyProductAttribute", "AssemblyTitleAttribute")
        if (!names.sameElements(expected)) {
            val differing = (names zip expected).indexWhere((x, y) => x != y)
            val ex = expected(differing)
            val act = names(differing)
            assert(false, s"Lists differ at index $differing\nExpected $ex but got $act")
        }
    }

    test("has-properties") {
        val strPath = smokePathStr()
        val assem = AssemblyDefinition.readAssembly(strPath).get
        val allWithProps = assem.customAttributes.map((attr) => if attr.hasProperties then 1 else 0).sum
        assertEquals(allWithProps, 2, "should have properties")
    }
}

object SmokeTests {
    def smokePath() = {
        val cwd = Paths.get(System.getProperty("user.dir"))
        val smokePath = cwd.resolve("../../test-files/smoke/Smoke.dll")
        smokePath
    }

    def smokePathStr() = {
        smokePath().toString()
    }

}
