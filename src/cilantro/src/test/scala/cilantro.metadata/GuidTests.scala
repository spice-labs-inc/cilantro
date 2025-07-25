import io.spicelabs.dotnet_support.*



class GuidTests extends munit.FunSuite {
    test("output test") {
        val guid = Guid(0x01020304, 0x0506, 0x0708, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10)
        val output = guid.toString()
        assertEquals(output, "01020304-0506-0708-090A-0B0C0D0E0F10")
        val emptyGuid = Guid.empty
        assertEquals(emptyGuid.toString(), "00000000-0000-0000-0000-000000000000")
    }
}
