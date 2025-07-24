
import io.spicelabs.cilantro.*

class MetadataTokenTests extends munit.FunSuite {
    test("toString") {
        var token = MetadataToken(type_ = TokenType.module)
        var output = token.toString()
        assertEquals(output, "[module:0x0000]")
        token = MetadataToken(type_ = TokenType.methodSpec, rid = 0xbeef)
        output = token.toString()
        assertEquals(output, "[methodSpec:0xbeef]")
        token = MetadataToken(type_ = TokenType.document, rid = 0xdead)
        output = token.toString()
        assertEquals(output, "[document:0xdead]")
    }
}
