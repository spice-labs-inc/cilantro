# BinaryStreamReader ByteBuffer Ownership Contract (LLM-Optimized)

**Purpose:** Quick reference for LLMs working with BinaryStreamReader code. For complete details, see `docs/binarystreamreader-contract.md`.

---

## TL;DR for LLMs

```scala
// ✅ PREFERRED: File-based (auto-cleanup)
val module = ModuleDefinition.readModule("path.dll")

// ✅ SAFE: Byte array with defensive copy
val reader = BinaryStreamReader.fromByteArray(bytes, copy = true)

// ⚠️  CAUTION: Byte array shared (performance, not security)
val reader = BinaryStreamReader.fromByteArray(bytes, copy = false)

// ⚠️  CAUTION: Heap buffer shares backing array
val reader = BinaryStreamReader.fromByteBuffer(NioByteBuffer.wrap(array))

// ❌ NEVER: Concurrent access without synchronization
// BinaryStreamReader is NOT thread-safe
```

---

## Key Facts

| Property | Value |
|----------|-------|
| **Thread Safe** | No - external synchronization required |
| **Max Buffer Size** | 2GB (ByteBuffer Int limit) |
| **Default Max Buffer** | 100MB (configurable) |
| **Default Array Copy** | true (security) |
| **Position Isolation** | Yes (defensive slice) |
| **Backing Array Sharing** | Yes for heap ByteBuffers |
| **Cleanup Required** | Only for file-based sources |

---

## Factory Methods Quick Reference

### fromByteBuffer(buffer, maxSize = 100MB)
```scala
// Creates defensive slice - position/limit changes isolated
// Heap buffer: shares backing array (concurrent modification risk)
// Direct buffer: no sharing
val reader = BinaryStreamReader.fromByteBuffer(buffer)
```

**Security:** Checks buffer.capacity() AND buffer.limit() against maxSize.
**Invariant:** Original buffer position unchanged after reader creation.

### fromByteArray(array, copy = true)
```scala
// copy=true (default): Creates defensive copy via array.clone()
// copy=false: Wraps array directly (TOCTOU risk)
val reader = BinaryStreamReader.fromByteArray(bytes)
```

**Security:** copy=true prevents TOCTOU attacks. Use for untrusted input.
**Performance:** copy=false avoids allocation. Use for large trusted arrays.

### fromFileInputStream(stream) / fromFile(path)
```scala
// Memory-mapped I/O
// Creates FileResourceHandle (requires cleanup)
val reader = BinaryStreamReader.fromFile("path.bin")
```

**Cleanup:** Call `reader.close()` or use try-finally.
**Large Files:** Supports files >2GB via OS memory mapping.

---

## Thread Safety Patterns

### Pattern 1: Synchronized Access (Single Reader)
```scala
val reader = BinaryStreamReader.fromFile("data.bin")
val lock = new Object()

// All access wrapped in synchronized
lock.synchronized {
    reader.position = offset
    val data = reader.readBytes(length)
}
```

### Pattern 2: Independent Readers (Recommended)
```scala
val handle = new FileResourceHandle(new FileInputStream("data.bin"))

// Each thread gets its own reader
val reader1 = handle.createReader()  // Thread 1
val reader2 = handle.createReader()  // Thread 2

// No synchronization needed - independent instances
```

### Pattern 3: ModuleDefinition (Auto-Synchronized)
```scala
// ModuleDefinition.read() methods are internally synchronized
val module = ModuleDefinition.readModule("assembly.dll")
module.types  // Safe - internal synchronization
```

---

## Heap Buffer Concurrent Modification Risk

```scala
val array = Array[Byte](1, 2, 3, 4)
val buffer = NioByteBuffer.wrap(array)
val reader = BinaryStreamReader.fromByteBuffer(buffer)

// ⚠️ DANGER: Modifying array affects reader
array(0) = 0xFF.toByte
reader.position = 0
val b = reader.readByte()  // Returns 0xFF, not 0x01!

// ✅ SAFE: Use defensive copy
val reader2 = BinaryStreamReader.fromByteArray(array, copy = true)
array(0) = 0xAA.toByte
reader2.position = 0
val b2 = reader2.readByte()  // Returns 0x01 (isolated copy)
```

**Rule:** For untrusted/external arrays, always use `copy=true` or `fromByteArray()`.

---

## Resource Cleanup Decision Tree

```
Source Type
├── ByteBuffer or Byte Array
│   └── No cleanup needed (caller owns buffer)
├── FileInputStream or File Path
│   ├── ModuleDefinition.readModule()
│   │   └── Call module.close() when done
│   └── BinaryStreamReader.fromFile()
│       └── Call reader.close() when done
```

### Code Example
```scala
// File-based: cleanup required
val reader = BinaryStreamReader.fromFile("data.bin")
try {
    process(reader)
} finally {
    reader.close()  // Releases file handle
}

// Buffer-based: no cleanup
val reader = BinaryStreamReader.fromByteArray(data)
process(reader)
// No close needed
```

---

## Error Handling Quick Reference

### Factory Errors (IllegalArgumentException)
- `null` buffer/array/stream/path
- Buffer capacity or limit > maxSize (default 100MB)
- File size > Int.MaxValue (2GB) for ByteBuffer path

### Runtime Errors
| Operation | Underflow | Overflow | Invalid Value |
|-----------|-----------|----------|---------------|
| `readByte()` | `BufferUnderflowException` | - | - |
| `readBytes(n)` | `BufferUnderflowException` | - | `IllegalArgumentException` if n < 0 or n > 100MB |
| `position = p` | - | - | `IllegalArgumentException` if p < 0 or p > limit |
| `advance(n)` | - | `ArithmeticException` | `IllegalArgumentException` if result out of bounds |
| `align(n)` | - | `ArithmeticException` | `IllegalArgumentException` if n <= 0 or not power of 2 |

---

## Security Invariant Summary

| Invariant | Enforcement | Test |
|-----------|-------------|------|
| Buffer size ≤ 100MB default | Factory methods | T10-A2 |
| Read size ≤ 100MB | readBytes() | T10-C7 |
| Position bounds [0, limit] | position_= | T10-B1 |
| Integer overflow prevention | Math.addExact | T10-B2, T10-D3 |
| Alignment power of 2 | align() | T10-D2 |
| Defensive slicing | buffer.slice() | T10-A5 |
| Defensive copying default | array.clone() | T10-A4 |

---

## 2GB Limit Deep Dive

### Why 2GB?
```java
// java.nio.ByteBuffer uses int for position/limit
public final int position()  // Returns int
public final Buffer position(int newPosition)  // Takes int

// Max value is Int.MaxValue = 2,147,483,647 bytes ≈ 2GB
```

### What Happens at 2GB?
```scala
val huge = NioByteBuffer.allocate(Int.MaxValue)  // May throw OOM
val reader = BinaryStreamReader.fromByteBuffer(huge)  // OK if allocation succeeds

// Or file > 2GB
val bigFile = Paths.get("huge-file.bin")  // 3GB file
val bytes = Files.readAllBytes(bigFile)  // OutOfMemoryError (array size limited by int)
val buffer = NioByteBuffer.wrap(bytes)   // Cannot create, bytes > Int.MaxValue
```

### Workaround
```scala
// Use file-based entry point - OS handles paging
val module = ModuleDefinition.readModule("huge-assembly.dll")  // Works for >2GB
```

**Reason PE files rarely affected:** PE format uses 32-bit addresses, so PE files > 2GB are extremely rare in practice.

---

## Common Code Patterns

### Pattern: Process Assembly from Network
```scala
def processAssembly(data: Array[Byte]): AssemblyDefinition = {
    // Defensive copy for security (untrusted network data)
    val buffer = NioByteBuffer.wrap(data.clone())
    val module = ModuleDefinition.readModule(buffer)
    module.assembly
}
```

### Pattern: Read PE Header from Byte Array
```scala
def readPEHeader(bytes: Array[Byte]): DOSHeader = {
    // No copy needed if we trust the caller
    val reader = BinaryStreamReader.fromByteArray(bytes, copy = false)
    val dosSignature = reader.readUInt16()
    reader.advance(58)
    val peOffset = reader.readInt32()
    // ... parse header
}
```

### Pattern: Multi-threaded Processing
```scala
val handle = new FileResourceHandle(new FileInputStream("data.bin"))

// Parallel processing
val futures = (0 until numThreads).map { i =>
    Future {
        val reader = handle.createReader()
        reader.position = i * chunkSize
        processChunk(reader)
    }
}

// Cleanup when all done
Future.sequence(futures).onComplete { _ =>
    handle.close()
}
```

### Pattern: Safe Resource Handling
```scala
def withAssembly[T](path: String)(f: AssemblyDefinition => T): T = {
    val assembly = AssemblyDefinition.readAssembly(path)
    try {
        f(assembly)
    } finally {
        assembly.close()
    }
}

// Usage
withAssembly("app.dll") { assembly =>
    println(assembly.name.fullName)
}
```

---

## When to Use What

### Source is File
```scala
// Use file-based entry points
ModuleDefinition.readModule("path.dll")
AssemblyDefinition.readAssembly("path.dll")
```

### Source is Byte Array (Trusted, Large)
```scala
// Performance: avoid copy
val reader = BinaryStreamReader.fromByteArray(bytes, copy = false)
```

### Source is Byte Array (Untrusted, Security Critical)
```scala
// Security: defensive copy
val reader = BinaryStreamReader.fromByteArray(bytes, copy = true)
// or
val buffer = NioByteBuffer.wrap(bytes.clone())
val reader = BinaryStreamReader.fromByteBuffer(buffer)
```

### Source is Existing ByteBuffer
```scala
// Slicing isolates position changes
val reader = BinaryStreamReader.fromByteBuffer(existingBuffer)
```

### Need Multiple Readers
```scala
// Use ResourceHandle for position-independent creation
val handle: ResourceHandle = // FileResourceHandle or BufferResourceHandle
val reader1 = handle.createReader()
val reader2 = handle.createReader()
```

---

## Testing Guidance

### Unit Test with Inline Bytes
```scala
test("parse DOS header") {
    val dosHeader = Array[Byte](0x4D, 0x5A, /* ... */)
    val reader = BinaryStreamReader.fromByteArray(dosHeader)
    assert(reader.readUInt16() == 0x5A4D)
}
```

### Property-Based Test
```scala
property("position after read equals initial + bytes read") {
    forAll { (data: Array[Byte], pos: Int) =>
        val buffer = NioByteBuffer.wrap(data)
        val reader = BinaryStreamReader.fromByteBuffer(buffer)
        val initial = reader.position
        val toRead = math.min(10, data.length - initial)
        if (toRead > 0) {
            reader.readBytes(toRead)
            reader.position == initial + toRead
        } else true
    }
}
```

### Security Test
```scala
test("rejects oversized buffer") {
    val huge = NioByteBuffer.allocate(200 * 1024 * 1024)  // 200MB
    intercept[IllegalArgumentException] {
        BinaryStreamReader.fromByteBuffer(huge, maxSize = 100 * 1024 * 1024)
    }
}
```

---

## Migration from FileInputStream

### Before (Old API)
```scala
val stream = new FileInputStream("data.bin")
val reader = new BinaryStreamReader(stream)  // Constructor
```

### After (New API)
```scala
val stream = new FileInputStream("data.bin")
val reader = BinaryStreamReader.fromFileInputStream(stream)  // Factory

// Or more commonly:
val module = ModuleDefinition.readModule("data.bin")  // Handles everything
```

---

## Summary for Code Generation

When generating code using BinaryStreamReader:

1. **Prefer factory methods** over direct instantiation
2. **Use copy=true** for untrusted input
3. **Assume NOT thread-safe** - add synchronization if shared
4. **Call close()** for file-based sources
5. **Check bounds** - expect IllegalArgumentException for bad inputs
6. **Handle BufferUnderflowException** for insufficient data
7. **Respect 2GB limit** - use file paths for large files

**Test Reference:** All security claims tested in `BinaryStreamReaderTests.scala` (T10-* test names).
