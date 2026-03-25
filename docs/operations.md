# Cilantro Operations Guide

## Common Operations

### Reading Assemblies

#### From File Path

```scala
import io.spicelabs.cilantro.AssemblyDefinition

val assembly = AssemblyDefinition.readAssembly("path/to/assembly.dll")
println(s"Name: ${assembly.name.name}")
println(s"Version: ${assembly.name.version}")
```

#### From ByteBuffer (In-Memory)

**Test Reference:** `T5: readAssembly from ByteBuffer basic`

```scala
import java.nio.{ByteBuffer => NioByteBuffer}
import java.nio.file.{Files, Paths}

// Load bytes from any source (file, network, etc.)
val bytes = Files.readAllBytes(Paths.get("assembly.dll"))
val buffer = NioByteBuffer.wrap(bytes)

// Read assembly
val assembly = AssemblyDefinition.readAssembly(buffer)
```

#### With Custom Parameters

**Test Reference:** `T5: readAssembly from ByteBuffer with parameters`

```scala
import io.spicelabs.cilantro.ReaderParameters
import io.spicelabs.cilantro.ReadingMode

val params = ReaderParameters(ReadingMode.deferred)
params.readSymbols = true

val assembly = AssemblyDefinition.readAssembly(buffer, params)
```

---

### Reading Modules

#### Basic Module Reading

**Test Reference:** `T3: readModule from ByteBuffer basic`

```scala
import io.spicelabs.cilantro.ModuleDefinition

// From file
val module = ModuleDefinition.readModule("assembly.dll")

// From ByteBuffer
val buffer = NioByteBuffer.wrap(byteArray)
val module = ModuleDefinition.readModule(buffer)
```

#### With File Name for Error Reporting

**Test Reference:** `T3: readModule from ByteBuffer with file name and parameters`

```scala
val buffer = NioByteBuffer.wrap(byteArray)
val params = ReaderParameters()
val module = ModuleDefinition.readModule(buffer, "MyAssembly.dll", params)
```

---

### Working with BinaryStreamReader

#### Creating from ByteBuffer

**Test Reference:** `T10-A1: fromByteBuffer basic functionality with heap ByteBuffer`

```scala
import io.spicelabs.cilantro.PE.BinaryStreamReader
import java.nio.{ByteBuffer => NioByteBuffer, ByteOrder}

// From heap ByteBuffer
val heapBuffer = NioByteBuffer.wrap(Array[Byte](1, 2, 3, 4))
val reader = BinaryStreamReader.fromByteBuffer(heapBuffer)

// From direct ByteBuffer
val directBuffer = NioByteBuffer.allocateDirect(1024)
directBuffer.put(Array[Byte](1, 2, 3, 4)).flip()
val reader = BinaryStreamReader.fromByteBuffer(directBuffer)
```

**Test Reference:** `T10-A1: fromByteBuffer works with direct ByteBuffer`

#### Creating from Byte Array

**Test Reference:** `T10-A3: fromByteArray creates reader from byte array`

```scala
val data = Array[Byte](0x4D, 0x5A, 0x90, 0x00)  // DOS header start

// With defensive copy (default, secure)
val reader = BinaryStreamReader.fromByteArray(data, copy = true)

// Without copy (faster, shares array)
val reader = BinaryStreamReader.fromByteArray(data, copy = false)
```

#### Reading Data Types

**Test Reference:** `T10-C1-C6: Data type read tests`

```scala
val reader = BinaryStreamReader.fromByteArray(data)

// Little-endian reads
val byte: Byte = reader.readByte()         // 1 byte, signed
val int16: Short = reader.readInt16()      // 2 bytes
val uint16: Char = reader.readUInt16()     // 2 bytes, unsigned
val int32: Int = reader.readInt32()        // 4 bytes
val int64: Long = reader.readInt64()       // 8 bytes
val bool: Boolean = reader.readBoolean()   // 1 byte, non-zero = true
```

#### Position Operations

**Test Reference:** `T10-B1: Position setter tests`

```scala
val reader = BinaryStreamReader.fromByteArray(data)

// Get/set position
val pos = reader.position
reader.position = 100

// Move relative
reader.advance(10)   // Forward 10 bytes
reader.advance(-5)   // Back 5 bytes

// Move absolute
reader.moveTo(0)     // Back to start
```

**Bounds Checking:**
- Setting position beyond limit throws `IllegalArgumentException`
- Negative position throws `IllegalArgumentException`
- Position at limit is valid (but reads will fail)

**Test Reference:** `T10-B1: position setter rejects value beyond limit`

#### Reading Byte Arrays

**Test Reference:** `T10-C7: readBytes tests`

```scala
val reader = BinaryStreamReader.fromByteArray(data)

// Read 100 bytes
val bytes = reader.readBytes(100)

// Size limit enforced (MAX_READ_SIZE = 100MB)
// This throws IllegalArgumentException:
reader.readBytes(200 * 1024 * 1024)  // > 100MB
```

#### Alignment

**Test Reference:** `T10-D1: Alignment tests`

```scala
reader.position = 5
reader.align(4)    // Moves to position 8
reader.align(2)    // Already aligned, stays at 8

// Must be power of 2
reader.align(3)    // Throws IllegalArgumentException
```

**Test Reference:** `T10-D2: align rejects non-power-of-2 values`

---

### Resource Management

#### Cleaning Up Resources

**Test Reference:** `T10-RH1: FileResourceHandle.close closes channel and stream`

```scala
val module = ModuleDefinition.readModule("assembly.dll")
try {
    // Use module...
} finally {
    module.close()  // Closes underlying FileResourceHandle
}
```

**Note:** Safe to call `close()` multiple times.

#### Creating Independent Readers

**Test Reference:** `T10-RH1: FileResourceHandle.createReader produces independent readers`

```scala
import io.spicelabs.cilantro.PE.FileResourceHandle
import java.io.FileInputStream

val stream = new FileInputStream("data.bin")
val handle = new FileResourceHandle(stream)

// Create independent readers
val reader1 = handle.createReader()
val reader2 = handle.createReader()

reader1.position = 100
reader2.position = 200  // Independent positions

handle.close()
```

---

### Symbol Reading (PDB)

#### From File

```scala
val module = ModuleDefinition.readModule("assembly.dll")
module.readSymbols()  // Looks for assembly.pdb
```

#### From ByteBuffer

**Test Reference:** `T9-SYM1: symbolBuffer parameter is accepted`

```scala
val assemblyBytes = Files.readAllBytes(Paths.get("assembly.dll"))
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

### Working with Images

#### Getting a Reader at Specific RVA

**Test Reference:** `T9-EQV1: Image.getReaderAt works with ByteBuffer-backed Image`

```scala
val module = ModuleDefinition.readModule(buffer)
val image = module.image

// Get reader at section RVA
val section = image.sections(0)
val reader = image.getReaderAt(section.virtualAddress)
```

#### Resolving Virtual Addresses

```scala
val rva = 0x2000  // Example RVA
val fileOffset = image.resolveVirtualAddress(rva)
```

#### Finding Sections

```scala
// By name
val textSection = image.getSection(".text")

// By RVA
val section = image.getSectionAtVirtualAddress(rva)
```

---

## Error Handling

### Common Exceptions

| Exception | Cause | Solution |
|-----------|-------|----------|
| `IllegalArgumentException` | Null buffer, size limit exceeded | Check buffer before passing |
| `BufferUnderflowException` | Read beyond buffer | Check `reader.length` and `reader.position` |
| `DataFormatException` | Invalid PE file | Validate file before reading |
| `ArithmeticException` | Integer overflow in position | Use smaller advance values |

**Test References:**
- `T10-G1: readByte at limit throws BufferUnderflowException`
- `T9-SEC3: rejects non-PE file`

### Validation Example

```scala
def safeReadAssembly(buffer: NioByteBuffer): Option[AssemblyDefinition] = {
    if (buffer == null || buffer.limit() < 128) {
        return None
    }
    try {
        Some(AssemblyDefinition.readAssembly(buffer))
    } catch {
        case _: DataFormatException => None
        case _: IllegalArgumentException => None
    }
}
```

---

## Performance Considerations

### Defensive Copying

**Test Reference:** `T10-A4: Defensive copying tests`

| Copy Mode | Use Case | Performance |
|-----------|----------|-------------|
| `copy=true` (default) | Untrusted input, concurrent | Slower (array clone) |
| `copy=false` | Trusted, immutable input | Faster (shares array) |

### Reading Modes

- **Deferred:** Faster startup, slower access (lazy loading)
- **Immediate:** Slower startup, faster access (all loaded)

### ByteBuffer Types

- **Heap ByteBuffer:** Standard, GC-managed
- **Direct ByteBuffer:** Off-heap, better for large files, avoids copying

**Test Reference:** `T10-A6: Direct vs heap ByteBuffer tests`

---

## Related Documents

- [README](README.md) - Getting started
- [Architecture](architecture.md) - Design overview
- [Troubleshooting](troubleshooting.md) - Common issues
- [Security](security.md) - Security considerations
- [ByteBuffer Contract](binarystreamreader-contract.md) - Ownership details
