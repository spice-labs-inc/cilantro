import io.spicelabs.cilantro.*
import java.nio.*
import java.nio.file.*

class SmokeTests extends munit.FunSuite {
    test("smoke exists") {
        var cwd = Paths.get(System.getProperty("user.dir"))
        var smokePath = cwd.resolve("../../test-files/smoke/Smoke.dll")
        assert(Files.exists(smokePath))
    }

    test("opens smoke file") {
        intercept[IllegalArgumentException] {
            var cwd = Paths.get(System.getProperty("user.dir"))
            var smokePath = cwd.resolve("../../test-files/smoke/Smoke.dll")
            var strPath = smokePath.toString()
            var assem = AssemblyDefinition.readAssembly(strPath)
        }
    }
}
