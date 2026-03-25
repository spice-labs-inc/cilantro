# BinaryStreamReader ByteBuffer Ownership Contract

**Version:** 1.0
**Date:** 2026-03-25
**Applies to:** `io.spicelabs.cilantro.PE.BinaryStreamReader`

---

## Overview

This document defines the ownership and mutability contract for `java.nio.ByteBuffer` instances used with `BinaryStreamReader`. Understanding these contracts is essential for correct and secure usage of the library.

---

## Quick Reference

| Operation | Original Buffer Modified? | Thread Safe? | Cleanup Required? |
|-----------|--------------------------|--------------|-------------------|
| `fromByteBuffer(buffer)` | No (defensive slice) | No | No |
| `fromByteArray(array, copy=true)` | No (owns copy) | No | No |
| `fromByteArray(array, copy=false)` | No (shares array) | No | No |
| `fromFileInputStream(stream)` | N/A | No | Yes (via close) |
| `fromFile(path)` | N/A | No | Yes (via close) |

---

## Buffer Ownership and Modification

### Defensive Slicing Guarantee

All `BinaryStreamReader` factory methods that accept `ByteBuffer` create a **defensive slice** of the input buffer:

```scala
val original = NioByteBuffer.wrap(Array[Byte](1, 2, 3, 4))
val reader = BinaryStreamReader.fromByteBuffer(original)

// Reader operations do NOT affect original buffer
reader.readInt32()
assert(original.position() == 0)  // Original unchanged
```

**Important:** The defensive slice shares the **same backing array** for heap-allocated ByteBuffers. While position/limit changes are isolated, modifications to the underlying array affect all views.

### Heap ByteBuffer Concurrent Modification Risk

```scala
val array = Array[Byte](1, 2, 3, 4)
val buffer = NioByteBuffer.wrap(array)
val reader = BinaryStreamReader.fromByteBuffer(buffer)

// WARNING: Modifying the original array affects the reader
array(0) = 0xFF
reader.position = 0
assert(reader.readByte() == 0xFF.toByte)  // Reader sees modification!
```

**Recommendation:** Use `fromByteArray(array, copy=true)` for untrusted input to prevent this issue.

---

## Thread Safety

### BinaryStreamReader is NOT Thread-Safe

`BinaryStreamReader` instances must not be accessed concurrently by multiple threads. Concurrent access produces undefined behavior.

**Safe patterns:**

```scala
// Pattern 1: External synchronization
val reader = BinaryStreamReader.fromFile("data.bin")
val lock = new Object()

// Thread 1
lock.synchronized {
    reader.position = 0
    val header = reader.readInt32()
}

// Thread 2
lock.synchronized {
    reader.position = 100
    val data = reader.readBytes(10)
}
```

```scala
// Pattern 2: Separate instances (recommended)
val handle = new FileResourceHandle(new FileInputStream("data.bin"))

// Thread 1
val reader1 = handle.createReader()
reader1.position = 0
val header = reader1.readInt32()

// Thread 2
val reader2 = handle.createReader()
reader2.position = 100
val data = reader2.readBytes(10)
```

### ResourceHandle Thread Safety

`ResourceHandle` instances are also **NOT thread-safe**:

- `createReader()` can be called concurrently, but each caller receives an independent reader
- `close()` should be called only once, when all readers are finished
- `length` can be read concurrently with `createReader()`, but not with `close()`

---

## 2GB Size Limitation

### The Limit

Due to `java.nio.ByteBuffer` using `Int` for position and limit operations, `BinaryStreamReader` is limited to **2GB maximum buffer size** (`Int.MaxValue` bytes, approximately 2.147GB).

### Implications

1. **Buffer capacity** and **limit** must be ≤ `Int.MaxValue`
2. Factory methods check this limit and throw `IllegalArgumentException` if exceeded
3. This is a **hard limit** of the Java NIO ByteBuffer design

### Workaround for Large Files

For files larger than 2GB, use file-based entry points:

```scala
// This works for files > 2GB (OS handles windowed memory mapping)
val reader = BinaryStreamReader.fromFile("huge-file.bin")
```

File-based entry points use memory-mapped I/O where the operating system handles paging large files.

### Why This is Acceptable

- PE files have 32-bit address limits, so PE files > 2GB are extremely rare
- Most assembly files are under 100MB
- The 2GB limit only affects ByteBuffer-based entry points, not file-based entry points

---

## Defensive Copying

### When to Use copy=true (Default)

Use defensive copying when:
- Processing **untrusted input** from network or user
- The array may be **modified after reader creation**
- **Security is more important** than performance
- Working with **concurrent code** that might modify the array

```scala
val untrustedData = receiveFromNetwork()
val reader = BinaryStreamReader.fromByteArray(untrustedData, copy = true)
// Reader is isolated from subsequent modifications
```

### When to Use copy=false

Skip defensive copying when:
- The array is **immutable** (e.g., from `Files.readAllBytes()`)
- You have **exclusive access** to the array
- **Performance is critical** and you accept the risk
- Working with **large arrays** where copying would be expensive

```scala
val data = Files.readAllBytes(Paths.get("large-file.bin"))
val reader = BinaryStreamReader.fromByteArray(data, copy = false)
// No copy overhead, but don't modify 'data' while using reader
```

### Performance Impact

| Array Size | copy=true Time | copy=false Time | Overhead |
|------------|---------------|-----------------|----------|
| 1 KB | ~1 μs | ~0.1 μs | 10x |
| 1 MB | ~1 ms | ~0.1 ms | 10x |
| 100 MB | ~100 ms | ~0.1 ms | 1000x |

For large arrays, consider using `fromByteBuffer(NioByteBuffer.wrap(array))` directly if you need slicing without copying.

---

## Resource Cleanup

### Who Owns Cleanup?

| Source Type | ResourceHandle | Cleanup Method | When to Call |
|-------------|---------------|----------------|--------------|
| ByteBuffer | None | None | Never - buffer owned by caller |
| Byte Array | None | None | Never - array owned by caller (or copied) |
| FileInputStream | FileResourceHandle | `close()` | When done with Image/Module |
| File Path | FileResourceHandle | `close()` | When done with Image/Module |

### Automatic Cleanup with Image

When using `ModuleDefinition.readModule()`, cleanup is handled automatically:

```scala
val module = ModuleDefinition.readModule("assembly.dll")
try {
    // Use module...
} finally {
    module.close()  // Closes underlying FileResourceHandle
}
```

### Manual Cleanup for Direct Reader Usage

```scala
val stream = new FileInputStream("data.bin")
val reader = BinaryStreamReader.fromFileInputStream(stream)

// Use reader...

reader.close()  // Closes stream and channel
```

**Note:** `close()` is idempotent - safe to call multiple times.

---

## Error Handling

### Factory Method Errors

| Error Condition | Exception Type | Message Contains |
|-----------------|----------------|------------------|
| Null buffer | `IllegalArgumentException` | "cannot be null" |
| Buffer too large | `IllegalArgumentException` | "exceeds maximum" |
| Null array | `IllegalArgumentException` | "cannot be null" |
| Null stream | `IllegalArgumentException` | "cannot be null" |
| Null path | `IllegalArgumentException` | "cannot be null" |
| File too large (>2GB) | `IllegalArgumentException` | "too large" |

### Runtime Read Errors

| Error Condition | Exception Type |
|-----------------|----------------|
| Read beyond buffer | `BufferUnderflowException` |
| Negative position | `IllegalArgumentException` |
| Position beyond limit | `IllegalArgumentException` |
| Negative readBytes length | `IllegalArgumentException` |
| readBytes length > MAX_READ_SIZE | `IllegalArgumentException` |
| Non-power-of-2 alignment | `IllegalArgumentException` |
| Integer overflow in advance | `ArithmeticException` |
| Integer overflow in align | `ArithmeticException` |

### PE Parsing Errors

| Error Condition | Exception Type |
|-----------------|----------------|
| Invalid DOS signature | `DataFormatException` |
| Invalid PE offset | `DataFormatException` |
| Invalid PE signature | `DataFormatException` |
| Too many sections (>96) | `DataFormatException` |
| Invalid metadata signature | `DataFormatException` |
| Negative row count | `DataFormatException` |

---

## Best Practices

### 1. Always Use Try-With-Resources (or finally)

```scala
val module = ModuleDefinition.readModule(path)
try {
    // Use module
} finally {
    module.close()
}
```

### 2. Prefer copy=true for Untrusted Input

```scala
// Network or user-provided data
val reader = BinaryStreamReader.fromByteArray(untrustedData, copy = true)
```

### 3. Use Defensive Slicing for Buffer Chunks

```scala
val largeBuffer = loadLargeBuffer()
val chunk = largeBuffer.slice()
chunk.limit(1000)  // Only first 1000 bytes
val reader = BinaryStreamReader.fromByteBuffer(chunk)
// Reader cannot access beyond byte 1000, even if largeBuffer is larger
```

### 4. Don't Share Readers Across Threads

Create separate readers or synchronize access:

```scala
// Good: Separate readers
val handle = new FileResourceHandle(stream)
val reader1 = handle.createReader()
val reader2 = handle.createReader()

// Good: External synchronization
val reader = BinaryStreamReader.fromFile(path)
val lock = new Object()
// Use lock.synchronized { ... } for all access
```

### 5. Be Aware of Heap Buffer Sharing

```scala
val array = Array[Byte](...)
val buffer = NioByteBuffer.wrap(array)
val reader = BinaryStreamReader.fromByteBuffer(buffer)

// DON'T: Modify array while using reader
// DON'T: Share 'array' with other components
// DO: Keep array private and unmodified
```

---

## Summary

| Contract | Value |
|----------|-------|
| Thread Safety | **NONE** - Not thread-safe |
| Buffer Isolation | Position/limit isolated via slicing; backing array shared for heap buffers |
| Maximum Size | 2GB (Int.MaxValue bytes) |
| Default Array Copy | **true** (defensive copy for security) |
| Resource Cleanup | Required for file-based sources; automatic with ModuleDefinition/AssemblyDefinition |
| Null Safety | All factory methods reject null inputs |
| Bounds Checking | All position operations validate bounds |
| Overflow Checking | All arithmetic uses Math.addExact |

---

## Related Documents

- Architecture Decision Record: `docs/adr/0001-binarystreamreader-bytebuffer-refactor.md`
- Design Document: `docs/task-2-binarystreamreader-design.md`
- API Documentation: Scaladoc in `BinaryStreamReader.scala`
- Security Tests: `src/cilantro/src/test/scala/cilantro.PE/BinaryStreamReaderTests.scala`
