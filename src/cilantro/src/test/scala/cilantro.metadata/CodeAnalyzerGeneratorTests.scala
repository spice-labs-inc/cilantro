import io.spicelabs.cilantro.*
import java.nio.*
import java.nio.file.*
import java.io.FileInputStream
import io.spicelabs.cilantro.PE.BinaryStreamReader
import io.spicelabs.cilantro.AnyExtension.as

class CodeAnalyzerGeneratorTests extends munit.FunSuite {
    import CodeAnalyzerGeneratorTests.{codeGenPath, codeGenPathStr, v90}

    test("90-exists") {
        assert(Files.exists(codeGenPath(v90)))
    }


    test("reads-90") {
        val path = codeGenPathStr(v90)
        val assem = AssemblyDefinition.readAssembly(path)
        assertEquals(assem.name.name, "CodeAnalyzerGenerator")
        assertEquals(assem.name.version.toString(), "2.0.0.0")
    }
}

object CodeAnalyzerGeneratorTests {
    def v90 = "net9.0"
    def codeGenPath(variant: String) =
        val cwd = Paths.get(System.getProperty("user.dir"))
        val smokePath = cwd.resolve(s"../../test-files/codeanalyzergenerator.2.0.0/$variant/CodeAnalyzerGenerator.dll")
        smokePath
    
    def codeGenPathStr(variant: String) =
        codeGenPath(variant).toString()

}
