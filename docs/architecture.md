# Cilantro Architecture

## Overview

Cilantro is a Scala 3 library for reading Microsoft .NET PE files and their CLI metadata. It is derived from the Mono.Cecil library but rewritten in Scala with additional security invariants and support for in-memory processing.

---

## Core Components

### BinaryStreamReader

**Location:** `cilantro.PE.BinaryStreamReader`

**Test Reference:** `T10-A1: fromByteBuffer basic functionality with heap ByteBuffer`

The foundational class for reading binary data. Uses `java.nio.ByteBuffer` as its primary input mechanism.

```scala
class BinaryStreamReader private[PE] (
    private[PE] val buffer: NioByteBuffer,
    private val resourceHandle: Option[ResourceHandle] = None
)
```

**Key Design Decisions:**
1. **ByteBuffer as Primary Input:** Unlike the original FileInputStream-based design, ByteBuffer enables in-memory processing from any source (files, network, byte arrays).

2. **Little-Endian Byte Order:** All reads use LITTLE_ENDIAN, matching PE file format.

3. **Defensive Slicing:** Factory methods create slices to isolate position changes from the original buffer.
   **Test Reference:** `T10-A5: fromByteBuffer does not modify original buffer position`

4. **ResourceHandle Abstraction:** Manages cleanup for file-based sources while allowing ByteBuffer sources to be garbage-collected normally.

---

### ResourceHandle

**Location:** `cilantro.PE.ResourceHandle` (private sealed trait)

**Test Reference:** `T10-RH1: FileResourceHandle.createReader produces independent readers`, `T10-RH2: BufferResourceHandle.createReader produces independent readers`

Manages the underlying data source for BinaryStreamReader:

```scala
private sealed trait ResourceHandle {
    def createReader(): BinaryStreamReader  // Position-independent
    def length: Long
    def close(): Unit
}
```

**Implementations:**

| Class | Purpose | Cleanup |
|-------|---------|---------|
| `FileResourceHandle` | Memory-mapped file access | Closes FileChannel and FileInputStream |
| `BufferResourceHandle` | ByteBuffer source (heap or direct) | No-op (GC handles cleanup) |

**Design Rationale:** Allows `Image.getReaderAt()` to create position-independent readers from the same source without re-parsing the file.

---

### Image

**Location:** `cilantro.PE.Image`

**Test Reference:** `T9-EQV1: Image.getReaderAt works with ByteBuffer-backed Image`

Represents a parsed PE/CLI image with all metadata:

```scala
sealed class Image extends AutoCloseable {
    var resourceHandle: Option[ResourceHandle] = None
    var sections: Array[Section] = null
    var stringHeap: StringHeap = null
    var blobHeap: BlobHeap = null
    // ... other heaps and metadata
}
```

**Key Methods:**
- `getReaderAt(rva: Int): BinaryStreamReader` - Creates a new reader at the specified RVA
- `resolveVirtualAddress(rva: Int): Int` - Converts RVA to file offset

**Security Note:** RVAs are validated to be non-negative.
**Test Reference:** `cilantro.PE/Image.scala:104 - RVA validation`

---

### ImageReader

**Location:** `cilantro.PE.ImageReader`

**Test Reference:** `T9-SEC1: rejects PE with invalid e_lfanew offset`, `T9-SEC2: rejects PE with section count > 96`

Parses PE files and extracts CLI metadata:

```scala
class ImageReader private (
    private val resourceHandle: ResourceHandle,
    private val file_name: String,
    buffer: NioByteBuffer
) extends BinaryStreamReader(buffer, Some(resourceHandle))
```

**Security Validations:**
1. **Minimum File Size:** 128 bytes
2. **e_lfanew Range:** 0x40 <= offset <= fileLength - 4
3. **Section Count:** Maximum 96 sections (MAX_PE_SECTIONS)
4. **Metadata Row Limits:** Per-table maximums enforced

**Test References:**
- File size: `T9-SEC4: rejects file too small`
- DOS signature: `T9-SEC3b: rejects PE with invalid DOS signature`
- PE signature: `T9-SEC3c: rejects PE with invalid PE signature`

---

### ModuleDefinition

**Location:** `cilantro.ModuleDefinition`

**Test Reference:** `T3: readModule from ByteBuffer basic`, `T3: ByteBuffer entry point produces same results as file path`

Main entry point for reading .NET modules:

```scala
def readModule(fileName: String): ModuleDefinition
def readModule(buffer: NioByteBuffer): ModuleDefinition
def readModule(buffer: NioByteBuffer, parameters: ReaderParameters): ModuleDefinition
def readModule(buffer: NioByteBuffer, fileName: String, parameters: ReaderParameters): ModuleDefinition
```

**Reading Modes:**
- **Deferred:** Lazy loading of metadata (default)
- **Immediate:** All metadata loaded upfront

**Test Reference:** `T3: readModule from ByteBuffer with parameters`

---

### AssemblyDefinition

**Location:** `cilantro.AssemblyDefinition`

**Test Reference:** `T5: readAssembly from ByteBuffer basic`, `T5: readAssembly ByteBuffer produces same results as file path`

Entry point for reading assemblies (modules with manifest):

```scala
def readAssembly(fileName: String): AssemblyDefinition
def readAssembly(buffer: NioByteBuffer): AssemblyDefinition
def readAssembly(buffer: NioByteBuffer, parameters: ReaderParameters): AssemblyDefinition
```

---

### ReaderParameters

**Location:** `cilantro.ReaderParameters`

**Test Reference:** `T9-SYM2: ReaderParameters symbolBuffer property works`

Configuration for reading operations:

```scala
sealed class ReaderParameters(private var _readingMode: ReadingMode) {
    def symbolBuffer: NioByteBuffer           // For in-memory PDB
    def readSymbols: Boolean                  // Enable symbol reading
    def applyWindowsRuntimeProjections: Boolean
    // ... other options
}
```

**Symbol Buffer Support:**
```scala
val params = ReaderParameters()
params.symbolBuffer = NioByteBuffer.wrap(pdbBytes)
```

**Test Reference:** `T9-SYM1: symbolBuffer parameter is accepted`

---

## Data Flow

### File-Based Reading

```
FileInputStream
    ↓
FileResourceHandle
    ↓
ImageReader (parses PE headers)
    ↓
Image (stores parsed data + ResourceHandle)
    ↓
ModuleDefinition (provides high-level API)
```

### ByteBuffer-Based Reading

```
ByteBuffer (from byte array, network, etc.)
    ↓
BufferResourceHandle
    ↓
ImageReader (parses PE headers)
    ↓
Image (stores parsed data + ResourceHandle)
    ↓
ModuleDefinition (provides high-level API)
```

**Test Reference:** `T9-EQV2: ByteBuffer entry point reads same metadata as file path`

---

## Security Architecture

### Size Limits

**Test Reference:** `T10-A2: fromByteBuffer rejects buffer with capacity exceeding max size`

| Limit | Default | Enforcement |
|-------|---------|-------------|
| Max buffer size | 100MB | Factory methods check capacity AND limit |
| Max readBytes | 100MB | Pre-allocation check in readBytes() |
| Max PE sections | 96 | ImageReader.readSections() |

### Bounds Checking

**Test Reference:** `T10-B1: position setter rejects negative value`

All position operations validate bounds:
```scala
def position_=(value: Int): Unit = {
    if (value < 0 || value > buffer.limit()) {
        throw new IllegalArgumentException(s"Position $value out of bounds")
    }
    buffer.position(value)
}
```

### Integer Overflow Prevention

**Test Reference:** `T10-B2: advance moves position forward` (overflow comment), `T10-D3: align throws on overflow`

```scala
def advance(bytes: Int): Unit = {
    val newPos = Math.addExact(buffer.position(), bytes)  // Throws on overflow
    position = newPos
}
```

### Alignment Validation

**Test Reference:** `T10-D2: align rejects non-power-of-2 values`

```scala
def align(alignment: Int): Unit = {
    if ((alignment & (alignment - 1)) != 0) {
        throw new IllegalArgumentException("Alignment must be power of 2")
    }
}
```

---

## Thread Safety Model

**BinaryStreamReader is NOT thread-safe.**

**Test Reference:** Documentation in `BinaryStreamReader.scala:80-86`

For concurrent access:
1. **Separate instances:** Use `ResourceHandle.createReader()` to get independent readers
2. **External synchronization:** Use locks around reader access

**Test Reference:** `T10-RH1: FileResourceHandle.createReader produces independent readers`

---

## 2GB Limitation

Due to `java.nio.ByteBuffer` using `Int` for position/limit:
- Maximum buffer size: `Int.MaxValue` bytes (~2.147GB)
- PE files have 32-bit address limits, so this is not a practical constraint
- File-based entry points handle larger files via windowed memory mapping

**Test Reference:** `T10-A2: fromByteBuffer accepts buffer at exactly max size`

---

## Design Decisions

### Why ByteBuffer?

| Alternative | Pros | Cons | Decision |
|-------------|------|------|----------|
| ByteBuffer (chosen) | Native NIO, memory mapping, little-endian | 2GB limit | ✅ Selected |
| Array[Byte] | Simple, no limit | No memory mapping, manual endianness | ❌ Rejected |
| RandomAccessFile | Large file support | Not suitable for in-memory | ❌ Rejected |

**Test Reference:** ADR-0001 Trade-Off Analysis section

### Why Defensive Slicing?

| Option | Pros | Cons | Decision |
|--------|------|------|----------|
| Defensive slice (chosen) | Isolates position changes | Small overhead | ✅ Selected |
| Direct use | Zero overhead | Caller modifications affect reader | ❌ Rejected |
| Copy buffer | Complete isolation | Memory overhead | ❌ Rejected |

**Test Reference:** `T10-A5: fromByteBuffer does not modify original buffer position`

---

## Related Documents

- [README](README.md) - Getting started
- [Operations Guide](operations.md) - Common operations
- [Troubleshooting](troubleshooting.md) - Common issues
- [Security](security.md) - Security considerations
- [ByteBuffer Contract](binarystreamreader-contract.md) - Ownership contract
- [ADR-0001](adr/0001-binarystreamreader-bytebuffer-refactor.md) - Architectural Decision Record
