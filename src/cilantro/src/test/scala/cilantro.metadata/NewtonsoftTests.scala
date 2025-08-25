import io.spicelabs.cilantro.*
import java.nio.*
import java.nio.file.*
import java.io.FileInputStream
import io.spicelabs.cilantro.PE.BinaryStreamReader
import io.spicelabs.cilantro.AnyExtension.as

class NewtonsoftTests extends munit.FunSuite {
    import NewtonsoftTests.{newtonPath, newtonPathStr, v60, v20}

    test("20-exists") {
        assert(Files.exists(newtonPath(NewtonsoftTests.v20)))
    }

    test("60-exists") {
        assert(Files.exists(newtonPath(NewtonsoftTests.v60)))
    }

    test("reads-20") {
        val path = newtonPathStr(v20)
        val assem = AssemblyDefinition.readAssembly(path)
        assertEquals(assem.name.name, "Newtonsoft.Json")
    }

    test("reads-60") {
        val path = newtonPathStr(v60)
        val assem = AssemblyDefinition.readAssembly(path)
        assertEquals(assem.name.name, "Newtonsoft.Json")
    }
}

object NewtonsoftTests {
    def v60 = "net6.0"
    def v20 = "net20"
    def newtonPath(variant: String) =
        val cwd = Paths.get(System.getProperty("user.dir"))
        val smokePath = cwd.resolve(s"../../test-files/Newtonsoft.Json/$variant/Newtonsoft.Json.dll")
        smokePath
    
    def newtonPathStr(variant: String) =
        newtonPath(variant).toString()

}
