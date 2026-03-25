# Cilantro Troubleshooting Guide

## Common Issues and Solutions

### "Buffer size exceeds maximum" Error

**Error:**
```
java.lang.IllegalArgumentException: Buffer size 209715200/209715200 exceeds maximum 104857600
```

**Cause:** The ByteBuffer exceeds the default 100MB size limit.

**Solution:**

**Test Reference:** `T10-A2: fromByteBuffer accepts buffer at exactly max size`

```scala
// Option 1: Increase the limit for this specific call
val hugeBuffer = NioByteBuffer.allocate(200 * 1024 * 1024)  // 200MB
val reader = BinaryStreamReader.fromByteBuffer(
    hugeBuffer,
    maxSize = 200 * 1024 * 1024  // Allow 200MB
)

// Option 2: Use file-based entry point (no ByteBuffer limit)
val module = ModuleDefinition.readModule("large-assembly.dll")
```

**Note:** File-based entry points can handle files larger than 2GB via memory-mapped I/O.

---

### "Position X out of bounds" Error

**Error:**
```
java.lang.IllegalArgumentException: Position 100 out of bounds [0, 64)
```

**Cause:** Attempting to set position beyond the buffer limit.

**Solution:**

**Test Reference:** `T10-B1: position setter rejects value beyond limit`

```scala
val reader = BinaryStreamReader.fromByteArray(data)

// Check bounds before setting position
if (targetPosition <= reader.length) {
    reader.position = targetPosition
} else {
    // Handle error or expand buffer
}
```

---

### BufferUnderflowException on Read

**Error:**
```
java.nio.BufferUnderflowException
```

**Cause:** Attempting to read beyond the buffer's remaining bytes.

**Solution:**

**Test Reference:** `T10-C7: readBytes throws BufferUnderflowException if insufficient bytes`

```scala
val reader = BinaryStreamReader.fromByteArray(data)

// Check remaining bytes before read
val bytesToRead = 100
if (reader.length - reader.position >= bytesToRead) {
    val bytes = reader.readBytes(bytesToRead)
} else {
    // Handle insufficient data
}
```

---

### "Invalid PE offset" or DataFormatException

**Error:**
```
java.util.zip.DataFormatException: Invalid PE offset: 2147483647
```

**Cause:** The file is not a valid PE file or has been corrupted.

**Solution:**

**Test Reference:** `T9-SEC1: rejects PE with invalid e_lfanew offset`, `T9-SEC3: rejects non-PE file`

```scala
// Validate file before reading
def isValidPE(file: Path): Boolean = {
    try {
        val bytes = Files.readAllBytes(file)
        if (bytes.length < 64) return false

        // Check DOS signature (MZ)
        if (bytes(0) != 0x4D || bytes(1) != 0x5A) return false

        // Check e_lfanew points to valid location
        val e_lfanew = (bytes(0x3C) & 0xFF) |
                       ((bytes(0x3D) & 0xFF) << 8) |
                       ((bytes(0x3E) & 0xFF) << 16) |
                       ((bytes(0x3F) & 0xFF) << 24)
        e_lfanew >= 0x40 && e_lfanew < bytes.length - 4
    } catch {
        case _: Exception => false
    }
}
```

---

### "Section count X exceeds maximum" Error

**Error:**
```
java.util.zip.DataFormatException: Section count 97 exceeds maximum 96
```

**Cause:** The PE file reports more than 96 sections (security limit).

**Solution:**

**Test Reference:** `T9-SEC2: rejects PE with section count > 96`

This is a malformed or malicious PE file. The 96-section limit protects against:
- Denial of service attacks
- Memory exhaustion
- Parser vulnerabilities

If you have a legitimate file with >96 sections, consider:
1. Using a different parsing library
2. Pre-processing the file to reduce sections

---

### Concurrent Modification Issues

**Issue:** Reader sees different data than expected when using `fromByteArray(data, copy=false)`.

**Cause:** The underlying byte array was modified after creating the reader.

**Solution:**

**Test Reference:** `T10-A4: fromByteArray with copy=true is immune to concurrent modification`

```scala
// Option 1: Use defensive copy (default)
val reader = BinaryStreamReader.fromByteArray(data, copy = true)

// Option 2: Ensure exclusive access to the array
val data = Files.readAllBytes(path)
val reader = BinaryStreamReader.fromByteArray(data, copy = false)
// Don't modify 'data' while using reader
```

---

### Thread Safety Issues

**Issue:** Data corruption or exceptions in multi-threaded code.

**Cause:** `BinaryStreamReader` is not thread-safe.

**Solution:**

**Test Reference:** `T10-RH1: FileResourceHandle.createReader produces independent readers`

```scala
// Option 1: Create separate readers per thread
val handle = new FileResourceHandle(new FileInputStream("data.bin"))

// Thread 1
val reader1 = handle.createReader()
// Use reader1...

// Thread 2
val reader2 = handle.createReader()
// Use reader2...

// Option 2: External synchronization
val reader = BinaryStreamReader.fromFile("data.bin")
val lock = new Object()

lock.synchronized {
    reader.position = 100
    val data = reader.readBytes(10)
}
```

---

### Memory Leaks with File Handles

**Issue:** Too many open file handles or memory not being released.

**Cause:** Not closing `ModuleDefinition` or `AssemblyDefinition` instances.

**Solution:**

**Test Reference:** `T10-RH1: FileResourceHandle.close closes channel and stream`

```scala
// Always close resources
val module = ModuleDefinition.readModule("assembly.dll")
try {
    // Use module...
} finally {
    module.close()
}

// Or use in a managed way
Using.resource(ModuleDefinition.readModule("assembly.dll")) { module =>
    // Use module...
}
```

---

### "Cannot read field/property/method" Issues

**Issue:** Metadata appears incomplete or null.

**Cause:** Reading mode (deferred vs immediate) or lazy loading behavior.

**Solution:**

```scala
// For immediate access to all metadata
val params = ReaderParameters(ReadingMode.immediate)
val module = ModuleDefinition.readModule("assembly.dll", params)

// Force immediate read on existing module
module.immediateRead()
```

---

### Symbol (PDB) Loading Fails

**Issue:** Debug symbols not available after loading.

**Cause:** PDB file not found or doesn't match assembly.

**Solution:**

**Test Reference:** `T9-SYM1: symbolBuffer parameter is accepted`

```scala
// Option 1: Specify PDB file path
val module = ModuleDefinition.readModule("assembly.dll")
module.readSymbols("path/to/assembly.pdb")

// Option 2: Load from ByteBuffer
val pdbBytes = Files.readAllBytes(Paths.get("assembly.pdb"))
val params = ReaderParameters()
params.symbolBuffer = NioByteBuffer.wrap(pdbBytes)
params.readSymbols = true

val module = ModuleDefinition.readModule(
    NioByteBuffer.wrap(assemblyBytes),
    "assembly.dll",
    params
)
```

---

### Byte Order Issues

**Issue:** Reading wrong values (e.g., expecting 0x1234 but getting 0x3412).

**Cause:** ByteBuffer was created with wrong byte order.

**Solution:**

**Test Reference:** `T10-A1: fromByteBuffer sets order to LITTLE_ENDIAN`

`BinaryStreamReader` automatically sets LITTLE_ENDIAN order. If using ByteBuffer directly:

```scala
val buffer = NioByteBuffer.wrap(data)
buffer.order(ByteOrder.LITTLE_ENDIAN)  // PE files are little-endian
```

---

## Debugging Tips

### Enable Verbose Logging

```scala
// Check reader state
val reader = BinaryStreamReader.fromByteArray(data)
println(s"Position: ${reader.position}")
println(s"Length: ${reader.length}")
println(s"Remaining: ${reader.length - reader.position}")
```

### Validate PE Structure

```scala
def dumpPEInfo(bytes: Array[Byte]): Unit = {
    // DOS header
    val dosSignature = (bytes(0) & 0xFF) | ((bytes(1) & 0xFF) << 8)
    println(f"DOS Signature: 0x$dosSignature%04X (expected: 0x5A4D)")

    // PE offset
    val e_lfanew = (bytes(0x3C) & 0xFF) |
                   ((bytes(0x3D) & 0xFF) << 8) |
                   ((bytes(0x3E) & 0xFF) << 16) |
                   ((bytes(0x3F) & 0xFF) << 24)
    println(f"PE Offset (e_lfanew): 0x$e_lfanew%08X")

    // PE signature
    if (e_lfanew + 4 <= bytes.length) {
        val peSig = (bytes(e_lfanew) & 0xFF) |
                    ((bytes(e_lfanew + 1) & 0xFF) << 8) |
                    ((bytes(e_lfanew + 2) & 0xFF) << 16) |
                    ((bytes(e_lfanew + 3) & 0xFF) << 24)
        println(f"PE Signature: 0x$peSig%08X (expected: 0x00004550)")
    }

    // Section count
    val numSections = (bytes(e_lfanew + 4 + 2) & 0xFF) |
                      ((bytes(e_lfanew + 4 + 3) & 0xFF) << 8)
    println(f"Number of Sections: $numSections")
}
```

---

## Getting Help

1. **Check the test suite** for examples of correct usage:
   - `src/test/scala/cilantro.PE/BinaryStreamReaderTests.scala`
   - `src/test/scala/cilantro.metadata/SmokeTests.scala`

2. **Review the API documentation**:
   - [README](README.md) - Getting started
   - [Operations Guide](operations.md) - Common operations
   - [Architecture](architecture.md) - Design details

3. **File an issue** with:
   - Minimal reproduction code
   - Expected vs actual behavior
   - Full stack trace
   - Version information

---

## Related Documents

- [README](README.md) - Getting started
- [Operations Guide](operations.md) - Common operations
- [Architecture](architecture.md) - Design overview
- [Security](security.md) - Security considerations
- [ByteBuffer Contract](binarystreamreader-contract.md) - Ownership details
