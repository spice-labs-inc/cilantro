# Cilantro Documentation

**Cilantro** is a Scala library for reading and analyzing Microsoft .NET PE (Portable Executable) files and their associated metadata.

---

## Quick Start

### Installation

Add the dependency to your `build.sbt`:

```scala
libraryDependencies += "io.spicelabs" %% "cilantro" % "0.0.1-SNAPSHOT"
```

### Basic Usage

#### Reading an Assembly from File

```scala
import io.spicelabs.cilantro.AssemblyDefinition

// Read an assembly from file path
val assembly = AssemblyDefinition.readAssembly("path/to/assembly.dll")

// Access assembly information
println(assembly.name.name)           // Assembly name
println(assembly.name.version)        // Version (e.g., 1.0.0.0)
println(assembly.mainModule.mvid)     // Module GUID
```

#### Reading from ByteBuffer (In-Memory)

**Test Reference:** `T3: readModule from ByteBuffer basic`, `T5: readAssembly from ByteBuffer basic`

```scala
import io.spicelabs.cilantro.{AssemblyDefinition, ModuleDefinition}
import io.spicelabs.cilantro.ReaderParameters
import java.nio.{ByteBuffer => NioByteBuffer}
import java.nio.file.{Files, Paths}

// Read file bytes into memory
val bytes = Files.readAllBytes(Paths.get("assembly.dll"))
val buffer = NioByteBuffer.wrap(bytes)

// Read assembly from ByteBuffer
val assembly = AssemblyDefinition.readAssembly(buffer)

// Or read as module
val module = ModuleDefinition.readModule(buffer, "assembly.dll", ReaderParameters())
```

**Security Note:** By default, `fromByteArray` creates a defensive copy. For untrusted input, use `copy=true` (default). For trusted input where performance is critical, use `copy=false`.

**Test Reference:** `T10-A4: fromByteArray with copy=true is immune to concurrent modification`

---

## Entry Points

### File-Based Entry Points

```scala
// From file path
val assembly = AssemblyDefinition.readAssembly("assembly.dll")
val module = ModuleDefinition.readModule("assembly.dll")

// From FileInputStream
val stream = new FileInputStream("assembly.dll")
try {
    val assembly = AssemblyDefinition.readAssembly(stream)
} finally {
    stream.close()
}
```

### ByteBuffer Entry Points

**Test Reference:** `T3: readModule from ByteBuffer with file name and parameters`, `T5: readAssembly from ByteBuffer with parameters`

```scala
// Basic ByteBuffer reading
val buffer = NioByteBuffer.wrap(byteArray)
val assembly = AssemblyDefinition.readAssembly(buffer)

// With custom parameters
val params = ReaderParameters(ReadingMode.deferred)
params.readSymbols = true
val module = ModuleDefinition.readModule(buffer, "assembly.dll", params)
```

### Byte Array Entry Points

**Test Reference:** `T10-A3: fromByteArray creates reader from byte array`

```scala
import io.spicelabs.cilantro.PE.BinaryStreamReader

// With defensive copy (default, secure)
val reader = BinaryStreamReader.fromByteArray(byteArray, copy = true)

// Without copy (faster, but caller must ensure immutability)
val reader = BinaryStreamReader.fromByteArray(byteArray, copy = false)
```

---

## Reading Modes

**Test Reference:** `T3: readModule from ByteBuffer with parameters`, `T5: readAssembly from ByteBuffer with parameters`

```scala
import io.spicelabs.cilantro.ReadingMode

// Deferred reading (lazy loading)
val params = ReaderParameters(ReadingMode.deferred)
val module = ModuleDefinition.readModule(buffer, params)

// Immediate reading (load all metadata upfront)
val params = ReaderParameters(ReadingMode.immediate)
val module = ModuleDefinition.readModule(buffer, params)
```

---

## Symbol Reading (PDB Support)

**Test Reference:** `T9-SYM1: symbolBuffer parameter is accepted`

```scala
// Read both assembly and PDB from memory
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

## Security Considerations

**IMPORTANT:** When processing untrusted input, always use defensive copying.

```scala
// Secure: Defensive copy prevents TOCTOU attacks
val reader = BinaryStreamReader.fromByteArray(untrustedData, copy = true)

// Size limits are enforced (default 100MB max)
// This will throw IllegalArgumentException if buffer exceeds limit:
val hugeBuffer = NioByteBuffer.allocate(200 * 1024 * 1024)  // 200MB
BinaryStreamReader.fromByteBuffer(hugeBuffer, maxSize = 100 * 1024 * 1024)
```

**Test Reference:** `T10-A2: fromByteBuffer rejects buffer with capacity exceeding max size`, `T10-C7: readBytes rejects excessive length before allocation`

See [security.md](security.md) for complete security documentation.

---

## Resource Cleanup

**Test Reference:** `T10-RH1: FileResourceHandle.close closes channel and stream`

When using file-based entry points, always clean up resources:

```scala
// Using try-finally
val module = ModuleDefinition.readModule("assembly.dll")
try {
    // Use module...
} finally {
    module.close()
}

// Using try-with-resources pattern (Scala 3)
val module = ModuleDefinition.readModule("assembly.dll")
module.close()  // Safe to call multiple times
```

---

## Thread Safety

**BinaryStreamReader is NOT thread-safe.** Concurrent access produces undefined behavior.

**Test Reference:** `T10-RH1: FileResourceHandle.createReader produces independent readers`

```scala
// Good: Create separate readers for concurrent access
val handle = new FileResourceHandle(new FileInputStream("data.bin"))
val reader1 = handle.createReader()  // Thread 1
val reader2 = handle.createReader()  // Thread 2

// Or use external synchronization
val reader = BinaryStreamReader.fromFile("data.bin")
val lock = new Object()
lock.synchronized {
    reader.position = 100
    val data = reader.readBytes(10)
}
```

---

## 2GB Limitation

Due to `java.nio.ByteBuffer` using `Int` for position/limit, `BinaryStreamReader` is limited to **2GB maximum buffer size**.

For files larger than 2GB, use file-based entry points which use memory-mapped I/O with windowed access managed by the operating system.

**Test Reference:** `T10-A2: fromByteBuffer accepts buffer at exactly max size`

---

## Next Steps

- [Architecture](architecture.md) - Understanding the design
- [Operations Guide](operations.md) - Common operations
- [Troubleshooting](troubleshooting.md) - Common issues
- [Security](security.md) - Security considerations
- [ByteBuffer Contract](binarystreamreader-contract.md) - Ownership and mutability contract

---

## License

MIT License - See [LICENSE](LICENSE) for details.
