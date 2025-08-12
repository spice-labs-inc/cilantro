import io.spicelabs.cilantro.*
import java.nio.*
import java.nio.file.*
import java.io.FileInputStream
import io.spicelabs.cilantro.PE.BinaryStreamReader

class SmokeTests extends munit.FunSuite {
    test("smoke exists") {
        var cwd = Paths.get(System.getProperty("user.dir"))
        var smokePath = cwd.resolve("../../test-files/smoke/Smoke.dll")
        assert(Files.exists(smokePath))
    }

    test("opens smoke file") {
        var cwd = Paths.get(System.getProperty("user.dir"))
        var smokePath = cwd.resolve("../../test-files/smoke/Smoke.dll")
        var strPath = smokePath.toString()
        var assem = AssemblyDefinition.readAssembly(strPath)
    }

    test("open-seek-read") {
        var cwd = Paths.get(System.getProperty("user.dir"))

        var smokePath = cwd.resolve("../../test-files/smoke/Smoke.dll")
        var strPath = smokePath.toString()

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
}
