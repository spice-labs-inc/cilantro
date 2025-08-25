import io.spicelabs.cilantro.*
import java.nio.*
import java.nio.file.*
import java.io.FileInputStream
import io.spicelabs.cilantro.PE.BinaryStreamReader
import io.spicelabs.cilantro.AnyExtension.as

class HalibutTests extends munit.FunSuite {
    import HalibutTests.{halibutPath, halibutPathStr, v80, v48}

    test("48-exists") {
        assert(Files.exists(halibutPath(HalibutTests.v48)))
    }

    test("80-exists") {
        assert(Files.exists(halibutPath(HalibutTests.v80)))
    }

    test("reads-48") {
        val path = halibutPathStr(v48)
        val assem = AssemblyDefinition.readAssembly(path)
        assertEquals(assem.name.name, "Halibut")
        assertEquals(assem.name.version.toString(), "8.1.1485.0")
    }

    test("reads-80") {
        val path = halibutPathStr(v80)
        val assem = AssemblyDefinition.readAssembly(path)
        assertEquals(assem.name.name, "Halibut")
        assertEquals(assem.name.version.toString(), "8.1.1485.0")
    }
}

object HalibutTests {
    def v80 = "net8.0"
    def v48 = "net48"
    def halibutPath(variant: String) =
        val cwd = Paths.get(System.getProperty("user.dir"))
        val smokePath = cwd.resolve(s"../../test-files/halibut.8.1.1485/$variant/Halibut.dll")
        smokePath
    
    def halibutPathStr(variant: String) =
        halibutPath(variant).toString()

}
