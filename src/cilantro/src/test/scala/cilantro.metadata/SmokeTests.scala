import io.spicelabs.cilantro.*
import java.nio.*
import java.nio.file.*
import java.io.FileInputStream
import io.spicelabs.cilantro.PE.BinaryStreamReader

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
    
        var binstmreader = BinaryStreamReader(filestream)
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
}

object SmokeTests {
    def smokePath() =
        val cwd = Paths.get(System.getProperty("user.dir"))
        val smokePath = cwd.resolve("../../test-files/smoke/Smoke.dll")
        smokePath
    
    def smokePathStr() =
        smokePath().toString()


}
