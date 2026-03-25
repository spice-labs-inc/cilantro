# Cilantro: Quick Reference for LLMs

**Purpose:** Scala library for reading .NET PE files and CLI metadata.

**Key Files:**
- Entry points: `cilantro.ModuleDefinition`, `cilantro.AssemblyDefinition`
- Core reader: `cilantro.PE.BinaryStreamReader`
- PE parsing: `cilantro.PE.ImageReader`

---

## Critical Invariants

1. **NOT thread-safe** - `BinaryStreamReader` requires external synchronization
2. **2GB limit** - `ByteBuffer` max size is `Int.MaxValue` bytes
3. **Little-endian** - All PE reads use LITTLE_ENDIAN byte order
4. **Security limits** - 100MB default max buffer, 96 max PE sections

**Test Evidence:** `T10-B1` (bounds), `T10-A2` (size limits), `T9-SEC2` (section limit)

---

## Primary Entry Points

### From File
```scala
AssemblyDefinition.readAssembly("path.dll")
ModuleDefinition.readModule("path.dll")
```

### From ByteBuffer
```scala
// Basic
val buffer = NioByteBuffer.wrap(byteArray)
val assembly = AssemblyDefinition.readAssembly(buffer)

// With parameters
val params = ReaderParameters(ReadingMode.deferred)
val module = ModuleDefinition.readModule(buffer, "name.dll", params)
```

**Test Evidence:** `T3: readModule from ByteBuffer basic`, `T5: readAssembly from ByteBuffer basic`

---

## BinaryStreamReader API

### Factory Methods
```scala
// ByteBuffer (defensive slice)
BinaryStreamReader.fromByteBuffer(buffer, maxSize = 100MB)

// Byte array (defensive copy by default)
BinaryStreamReader.fromByteArray(array, copy = true)

// File-based (memory-mapped)
BinaryStreamReader.fromFile("path")
BinaryStreamReader.fromFileInputStream(stream)
```

**Test Evidence:** `T10-A1` (ByteBuffer), `T10-A3` (array), `T10-A4` (copy), `T10-A5` (slicing)

### Read Operations
```scala
reader.readByte(): Byte
reader.readInt16(): Short
reader.readUInt16(): Char
reader.readInt32(): Int
reader.readInt64(): Long
reader.readBoolean(): Boolean
reader.readBytes(length: Int): Array[Byte]  // Max 100MB
reader.readDataDirectory(): DataDirectory
```

**Test Evidence:** `T10-C1` through `T10-C7`

### Position Operations
```scala
reader.position: Int                    // Getter
reader.position = value                 // Bounds-checked setter
reader.advance(bytes: Int)              // Relative, overflow-checked
reader.moveTo(position: Int)            // Absolute
reader.align(alignment: Int)            // Power-of-2 only
reader.length: Int                      // Buffer limit
```

**Test Evidence:** `T10-B1` (bounds), `T10-B2` (advance), `T10-D1` (align), `T10-D2` (validation)

---

## Security Model

| Threat | Mitigation | Test |
|--------|------------|------|
| OOM (large buffer) | 100MB default limit | `T10-A2` |
| OOM (large read) | MAX_READ_SIZE = 100MB | `T10-C7` |
| OOB access | Position bounds checking | `T10-B1` |
| Integer overflow | `Math.addExact` | `T10-B2`, `T10-D3` |
| TOCTOU | Defensive copy default | `T10-A4` |
| Malformed PE | Multiple validations | `T9-SEC1-4` |

---

## ResourceHandle Abstraction

```scala
// Internal trait for managing data sources
sealed trait ResourceHandle {
    def createReader(): BinaryStreamReader  // Position-independent
    def length: Long
    def close(): Unit
}

// Implementations:
// - FileResourceHandle: memory-mapped files, needs close()
// - BufferResourceHandle: ByteBuffer sources, no-op close()
```

**Test Evidence:** `T10-RH1` (FileResourceHandle), `T10-RH2` (BufferResourceHandle)

---

## Common Patterns

### Safe Resource Usage
```scala
val module = ModuleDefinition.readModule("file.dll")
try { /* use */ } finally { module.close() }
```

### Concurrent Access
```scala
// Create separate readers
val handle = new FileResourceHandle(stream)
val r1 = handle.createReader()  // Thread 1
val r2 = handle.createReader()  // Thread 2
```

### Symbol Loading
```scala
val params = ReaderParameters()
params.symbolBuffer = NioByteBuffer.wrap(pdbBytes)
params.readSymbols = true
val module = ModuleDefinition.readModule(buffer, "file.dll", params)
```

**Test Evidence:** `T9-SYM1`, `T9-SYM2`

---

## Error Handling

| Exception | Cause |
|-----------|-------|
| `IllegalArgumentException` | Null input, size limit exceeded, invalid alignment |
| `BufferUnderflowException` | Read beyond buffer |
| `DataFormatException` | Invalid PE structure |
| `ArithmeticException` | Integer overflow in position |

---

## ByteBuffer Ownership

- **Defensive slice:** Original buffer position unaffected (`T10-A5`)
- **Backing array:** Shared for heap ByteBuffers (concurrent modification risk)
- **Recommendation:** Use `fromByteArray(data, copy=true)` for untrusted input

---

## Related
- Architecture: `architecture_llm.md`
- Security: `security.md`
- Contract: `../binarystreamreader-contract.md`
- ADR: `../adr/0001-binarystreamreader-bytebuffer-refactor.md`
