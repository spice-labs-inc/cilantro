import io.spicelabs.cilantro.*
import java.nio.*
import java.nio.file.*
import java.io.FileInputStream
import io.spicelabs.cilantro.PE.BinaryStreamReader
import io.spicelabs.cilantro.AnyExtension.as

class DndGenTests extends munit.FunSuite {
    import DndGenTests.{dndGenPath, dndGenPathStr, v80}

    test("80-exists") {
        assert(Files.exists(dndGenPath(DndGenTests.v80)))
    }


    test("reads-80") {
        val path = dndGenPathStr(v80)
        val assem = AssemblyDefinition.readAssembly(path)
        assertEquals(assem.name.name, "DnDGen.CharacterGen")
        assertEquals(assem.name.version.toString(), "15.0.0.0")
    }
}

object DndGenTests {
    def v80 = "net8.0"
    def dndGenPath(variant: String) =
        val cwd = Paths.get(System.getProperty("user.dir"))
        val smokePath = cwd.resolve(s"../../test-files/dndgen.charactergen.15.0.0/$variant/DnDGen.CharacterGen.dll")
        smokePath
    
    def dndGenPathStr(variant: String) =
        dndGenPath(variant).toString()

}
