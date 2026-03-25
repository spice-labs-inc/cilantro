# Task 2: BinaryStreamReader Design Document

**Date:** 2026-03-25
**Status:** Complete
**Revision:** 1

---

## 1. Overview

This document provides the detailed design for refactoring `BinaryStreamReader` to use `java.nio.ByteBuffer` as its primary input mechanism, replacing the current `FileInputStream`-based design.

---

## 2. Design Goals

| Goal | Priority | Description |
|------|----------|-------------|
| In-Memory Support | Critical | Enable processing of assemblies from byte arrays without file I/O |
| Backward Compatibility | High | Existing file-based entry points must continue to work |
| Security | Critical | Bounds checking, overflow protection, size limits on all operations |
| Performance | Medium | Minimal overhead compared to current implementation |
| Thread Safety | Low | Document as NOT thread-safe; callers must synchronize |

## 2.1 Naming Collision Resolution

The codebase already contains a `cilantro.PE.ByteBuffer` class (custom byte array wrapper for metadata heaps). To avoid collision with `java.nio.ByteBuffer`, this design uses the following type alias:

```scala
import java.nio.{ByteBuffer => NioByteBuffer, ByteOrder}
```

All references to `ByteBuffer` in this document and the implementation refer to `java.nio.ByteBuffer` via the `NioByteBuffer` alias. The existing `cilantro.PE.ByteBuffer` remains unchanged for metadata heap operations.

---

## 3. Class Design

### 3.1 BinaryStreamReader (Refactored)

```scala
package io.spicelabs.cilantro.PE

import java.io.FileInputStream
import java.nio.{ByteBuffer => NioByteBuffer, ByteOrder}
import java.nio.channels.FileChannel.MapMode
import java.nio.file.{Path, Files}
import java.util.zip.DataFormatException

/**
 * BinaryStreamReader provides little-endian binary data reading from a ByteBuffer.
 *
 * SECURITY NOTICE: This class is NOT thread-safe. Concurrent access produces
 * undefined behavior. Callers must synchronize or use separate instances.
 *
 * LIMITATION: Due to java.nio.ByteBuffer using Int for position/limit,
 * BinaryStreamReader is limited to 2GB maximum buffer size (Int.MaxValue bytes).
 * This is acceptable for PE files (which have 32-bit address limits) but may
 * be a constraint for other use cases. For files larger than 2GB, use the
 * file-based entry points (fromFile, fromFileInputStream) which use
 * memory-mapped I/O with windowed access.
 */
class BinaryStreamReader protected (
    protected val buffer: NioByteBuffer,
    private val resourceHandle: Option[ResourceHandle] = None
) extends AutoCloseable {

    // ----------------------------------------------------------------------
    // Position Operations (with bounds checking)
    // ----------------------------------------------------------------------

    /**
     * Returns the current position in the buffer.
     */
    def position: Int = buffer.position()

    /**
     * Sets the position in the buffer.
     *
     * SECURITY INVARIANT: Position bounds
     * REQUIRES: 0 <= value <= buffer.limit()
     * TESTS: T10-B1
     *
     * @throws IllegalArgumentException if value is out of bounds
     */
    def position_=(value: Int): Unit = {
        if (value < 0 || value > buffer.limit()) {
            throw new IllegalArgumentException(
                s"Position $value out of bounds [0, ${buffer.limit()})"
            )
        }
        buffer.position(value)
    }

    /**
     * Returns the length (limit) of the buffer.
     */
    def length: Int = buffer.limit()

    /**
     * Advances the position by the specified number of bytes.
     *
     * SECURITY INVARIANT: Integer overflow
     * REQUIRES: Math.addExact prevents overflow
     * TESTS: T10-B2
     *
     * @param bytes number of bytes to advance (can be negative)
     * @throws ArithmeticException if overflow occurs
     * @throws IllegalArgumentException if result is out of bounds
     */
    def advance(bytes: Int): Unit = {
        val newPos = Math.addExact(buffer.position(), bytes)
        position = newPos
    }

    /**
     * Moves to an absolute position.
     *
     * @param position absolute position to move to
     * @throws IllegalArgumentException if position is out of bounds
     */
    def moveTo(position: Int): Unit = {
        this.position = position
    }

    /**
     * Aligns the position to the specified boundary.
     *
     * SECURITY INVARIANT: Alignment validation
     * REQUIRES: Alignment must be positive and power of 2
     * SECURITY INVARIANT: Integer overflow
     * REQUIRES: Math.addExact prevents overflow
     * TESTS: T10-D1, T10-D2, T10-D3
     *
     * @param align alignment boundary (must be power of 2, positive)
     * @throws IllegalArgumentException if align is not positive or not power of 2
     * @throws ArithmeticException if overflow occurs
     */
    def align(align: Int): Unit = {
        if (align <= 0) {
            throw new IllegalArgumentException("Alignment must be positive")
        }
        if ((align & (align - 1)) != 0) {
            throw new IllegalArgumentException("Alignment must be power of 2")
        }
        val aa = align - 1
        val pos = buffer.position()
        val newPos = (Math.addExact(pos, aa)) & ~aa
        position = newPos
    }

    // ----------------------------------------------------------------------
    // Data Type Reads
    // ----------------------------------------------------------------------

    /**
     * Reads a single signed byte.
     *
     * @throws BufferUnderflowException if no bytes remaining
     */
    def readByte(): Byte = buffer.get()

    /**
     * Reads a little-endian signed 16-bit integer.
     *
     * @throws BufferUnderflowException if insufficient bytes
     */
    def readInt16(): Short = buffer.getShort()

    /**
     * Reads a little-endian unsigned 16-bit integer as Char.
     *
     * @throws BufferUnderflowException if insufficient bytes
     */
    def readUInt16(): Char = buffer.getShort().toChar

    /**
     * Reads a little-endian signed 32-bit integer.
     *
     * @throws BufferUnderflowException if insufficient bytes
     */
    def readInt32(): Int = buffer.getInt()

    /**
     * Reads a little-endian signed 64-bit integer.
     *
     * @throws BufferUnderflowException if insufficient bytes
     */
    def readInt64(): Long = buffer.getLong()

    /**
     * Reads a boolean (single byte, non-zero is true).
     *
     * @throws BufferUnderflowException if no bytes remaining
     */
    def readBoolean(): Boolean = buffer.get() != 0

    /**
     * Reads a byte array of the specified length.
     *
     * SECURITY INVARIANT: Max read operation size
     * REQUIRES: length <= MAX_READ_SIZE (100MB)
     * TESTS: T10-C7
     *
     * @param length number of bytes to read
     * @return array containing read bytes
     * @throws IllegalArgumentException if length is negative or exceeds MAX_READ_SIZE
     * @throws BufferUnderflowException if insufficient bytes remaining
     */
    def readBytes(length: Int): Array[Byte] = {
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative")
        }
        if (length > BinaryStreamReader.MAX_READ_SIZE) {
            throw new IllegalArgumentException(
                s"Read size $length exceeds maximum ${BinaryStreamReader.MAX_READ_SIZE}"
            )
        }
        if (length > buffer.remaining()) {
            throw new BufferUnderflowException()
        }
        val bytes = Array.ofDim[Byte](length)
        buffer.get(bytes)
        bytes
    }

    /**
     * Reads a PE DataDirectory structure (8 bytes).
     *
     * @throws BufferUnderflowException if insufficient bytes
     */
    def readDataDirectory(): DataDirectory = {
        val virtualAddress = readInt32()
        val size = readInt32()
        DataDirectory(virtualAddress, size)
    }

    // ----------------------------------------------------------------------
    // Resource Management
    // ----------------------------------------------------------------------

    /**
     * Closes any associated resources (FileInputStream, etc.).
     * Safe to call multiple times.
     */
    override def close(): Unit = {
        resourceHandle.foreach(_.close())
    }
}

// ==============================================================================
// Factory Methods
// ==============================================================================

object BinaryStreamReader {

    /**
     * Default maximum buffer size: 100MB
     * SECURITY INVARIANT: Max buffer size
     * REQUIRES: Buffer capacity AND limit <= DEFAULT_MAX_BUFFER_SIZE
     * TESTS: T10-A2
     */
    val DEFAULT_MAX_BUFFER_SIZE = 100 * 1024 * 1024L

    /**
     * Maximum size for a single readBytes operation: 100MB
     * SECURITY INVARIANT: Max read operation size
     * REQUIRES: readBytes length <= MAX_READ_SIZE
     * TESTS: T10-C7
     */
    val MAX_READ_SIZE = 100 * 1024 * 1024L

    /**
     * Maximum number of PE sections allowed.
     * SECURITY INVARIANT: Max PE sections
     * REQUIRES: Section count <= MAX_PE_SECTIONS
     * TESTS: T9-SEC2
     */
    val MAX_PE_SECTIONS = 96

    // --------------------------------------------------------------------------
    // Factory: ByteBuffer
    // --------------------------------------------------------------------------

    /**
     * Creates a BinaryStreamReader from a ByteBuffer.
     *
     * SECURITY INVARIANT: Defensive slicing
     * REQUIRES: Reader operations do not affect original buffer position/limit
     * SECURITY INVARIANT: Max buffer size
     * REQUIRES: Buffer capacity AND limit <= maxSize
     * TESTS: T10-A1, T10-A2, T10-A5
     *
     * The buffer is defensively sliced to isolate position/limit changes.
     * The slice is set to LITTLE_ENDIAN order.
     *
     * @param buffer the buffer to read from (must not be null)
     * @param maxSize maximum allowed buffer size (default 100MB)
     * @return new BinaryStreamReader instance
     * @throws IllegalArgumentException if buffer is null
     * @throws IllegalArgumentException if buffer capacity or limit exceeds maxSize
     */
    def fromByteBuffer(
        buffer: NioByteBuffer,
        maxSize: Long = DEFAULT_MAX_BUFFER_SIZE
    ): BinaryStreamReader = {
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer cannot be null")
        }
        // Check BOTH capacity and limit for security
        if (buffer.capacity() > maxSize) {
            throw new IllegalArgumentException(
                s"Buffer capacity ${buffer.capacity()} exceeds maximum $maxSize"
            )
        }
        if (buffer.limit() > maxSize) {
            throw new IllegalArgumentException(
                s"Buffer limit ${buffer.limit()} exceeds maximum $maxSize"
            )
        }
        // Create defensive slice to isolate position changes
        val slice = buffer.slice()
        slice.order(ByteOrder.LITTLE_ENDIAN)
        new BinaryStreamReader(slice, None)
    }

    // --------------------------------------------------------------------------
    // Factory: Byte Array
    // --------------------------------------------------------------------------

    /**
     * Creates a BinaryStreamReader from a byte array.
     *
     * SECURITY INVARIANT: Defensive copying
     * REQUIRES: copy=true creates defensive copy to prevent TOCTOU attacks
     * TESTS: T10-A3, T10-A4
     *
     * When copy=true (default), a defensive copy is made to prevent time-of-check
     * to time-of-use (TOCTOU) attacks where the caller modifies the array after
     * passing it to this method.
     *
     * @param array the byte array to read from (must not be null)
     * @param copy if true (default), creates defensive copy; if false, shares array
     * @return new BinaryStreamReader instance
     * @throws IllegalArgumentException if array is null
     */
    def fromByteArray(array: Array[Byte], copy: Boolean = true): BinaryStreamReader = {
        if (array == null) {
            throw new IllegalArgumentException("Array cannot be null")
        }
        val data = if (copy) array.clone() else array
        fromByteBuffer(NioByteBuffer.wrap(data))
    }

    // --------------------------------------------------------------------------
    // Factory: FileInputStream (Backward Compatible)
    // --------------------------------------------------------------------------

    /**
     * Creates a BinaryStreamReader from a FileInputStream.
     *
     * The file is memory-mapped for efficient access. The stream can be closed
     * after this method returns; the mapped buffer persists.
     *
     * @param stream the FileInputStream to read from (must not be null)
     * @return new BinaryStreamReader instance
     * @throws IllegalArgumentException if stream is null
     * @throws IOException if memory mapping fails
     */
    def fromFileInputStream(stream: FileInputStream): BinaryStreamReader = {
        if (stream == null) {
            throw new IllegalArgumentException("Stream cannot be null")
        }
        val channel = stream.getChannel()
        val size = channel.size()
        if (size > Int.MaxValue) {
            throw new IllegalArgumentException(
                s"File too large: $size bytes (max ${Int.MaxValue})"
            )
        }
        val mappedBuffer = channel.map(MapMode.READ_ONLY, 0, size)
        mappedBuffer.order(ByteOrder.LITTLE_ENDIAN)
        // ResourceHandle manages the original stream
        val handle = new FileResourceHandle(stream)
        new BinaryStreamReader(mappedBuffer, Some(handle))
    }

    // --------------------------------------------------------------------------
    // Factory: File Path
    // --------------------------------------------------------------------------

    /**
     * Creates a BinaryStreamReader from a file path (String).
     *
     * @param path the file path to read from (must not be null)
     * @return new BinaryStreamReader instance
     * @throws IllegalArgumentException if path is null
     * @throws IOException if file cannot be opened or mapped
     */
    def fromFile(path: String): BinaryStreamReader = {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null")
        }
        fromFile(Path.of(path))
    }

    /**
     * Creates a BinaryStreamReader from a file path (Path).
     *
     * @param path the file path to read from (must not be null)
     * @return new BinaryStreamReader instance
     * @throws IllegalArgumentException if path is null
     * @throws IOException if file cannot be opened or mapped
     */
    def fromFile(path: Path): BinaryStreamReader = {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null")
        }
        val stream = new FileInputStream(path.toFile)
        try {
            fromFileInputStream(stream)
        } catch {
            case e: Exception =>
                stream.close()
                throw e
        }
    }
}
```

### 3.2 ImageReader Integration

`ImageReader` extends `BinaryStreamReader` and must be updated to work with the new ByteBuffer-based constructor:

```scala
class ImageReader(
    stream: Disposable[FileInputStream],
    file_name: String
) extends BinaryStreamReader(
    // Parent constructor: ByteBuffer + ResourceHandle
    createMappedBuffer(stream.value),  // NioByteBuffer
    Some(new FileResourceHandle(stream.value))  // ResourceHandle
) {
    // ImageReader specific functionality unchanged
    // ... existing code ...
}

object ImageReader {
    private def createMappedBuffer(stream: FileInputStream): NioByteBuffer = {
        val channel = stream.getChannel()
        val size = channel.size()
        if (size > Int.MaxValue) {
            throw new IllegalArgumentException(s"File too large: $size bytes")
        }
        val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer
    }
}
```

### 3.3 ResourceHandle Abstraction

```scala
package io.spicelabs.cilantro.PE

import java.io.FileInputStream

/**
 * ResourceHandle manages the underlying data source for BinaryStreamReader.
 *
 * SECURITY NOTICE: NOT THREAD-SAFE. Callers must synchronize concurrent access.
 *
 * This abstraction allows BinaryStreamReader to work with both file-based
 * sources (which need cleanup) and memory-based sources (which don't).
 */
private sealed trait ResourceHandle {
    /**
     * Creates a new BinaryStreamReader positioned at the start of the data.
     * Each call returns an independent reader.
     */
    def createReader(): BinaryStreamReader

    /**
     * Returns the length of the data in bytes.
     */
    def length: Long

    /**
     * Closes any associated resources. Safe to call multiple times.
     */
    def close(): Unit
}

/**
 * FileResourceHandle manages a FileInputStream for memory-mapped access.
 *
 * @param file the FileInputStream to manage
 */
private class FileResourceHandle(file: FileInputStream) extends ResourceHandle {
    private val channel = file.getChannel()
    private val size = channel.size()
    private var closed = false

    override def length: Long = {
        if (closed) throw new IllegalStateException("ResourceHandle is closed")
        size
    }

    override def createReader(): BinaryStreamReader = {
        if (closed) throw new IllegalStateException("ResourceHandle is closed")
        if (size > Int.MaxValue) {
            throw new IllegalArgumentException(
                s"File too large: $size bytes (max ${Int.MaxValue})"
            )
        }
        val mappedBuffer = channel.map(
            java.nio.channels.FileChannel.MapMode.READ_ONLY,
            0,
            size
        )
        mappedBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        new BinaryStreamReader(mappedBuffer, None)
    }

    override def close(): Unit = {
        if (!closed) {
            closed = true
            try {
                channel.close()
            } catch {
                case _: Exception => // Ignore
            }
            try {
                file.close()
            } catch {
                case _: Exception => // Ignore
            }
        }
    }
}

/**
 * BufferResourceHandle manages a ByteBuffer for in-memory access.
 *
 * @param buffer the ByteBuffer to manage
 */
private class BufferResourceHandle(buffer: java.nio.ByteBuffer) extends ResourceHandle {
    override def length: Long = buffer.limit()

    override def createReader(): BinaryStreamReader = {
        val slice = buffer.slice()
        slice.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        new BinaryStreamReader(slice, None)
    }

    override def close(): Unit = {
        // Nothing to close for buffer-based resources
    }
}
```

---

## 4. Security Invariants

| Invariant | Value | Location | Enforcement | Test |
|-----------|-------|----------|-------------|------|
| Max buffer size | 100MB default | Factory methods | capacity AND limit check | T10-A2 |
| Max read size | 100MB | readBytes() | Pre-allocation check | T10-C7 |
| Max PE sections | 96 | ImageReader.readSections | count validation | T9-SEC2 |
| Position bounds | [0, limit] | position_= | Bounds check | T10-B1 |
| Integer overflow | N/A | advance(), align() | Math.addExact | T10-B2, T10-D3 |
| Alignment | Power of 2 | align() | Bit mask validation | T10-D2 |
| Defensive slicing | Always | fromByteBuffer() | buffer.slice() | T10-A5 |
| Defensive copying | Default true | fromByteArray() | array.clone() | T10-A4 |
| Null rejection | N/A | All factories | null check | T10-A1-A7 |

---

## 5. Known Limitations

### 5.1 2GB Maximum Buffer Size

Due to `java.nio.ByteBuffer` using `Int` for position/limit, `BinaryStreamReader` is limited to **2GB maximum buffer size** (`Int.MaxValue` bytes, approximately 2.147GB).

**Impact:**
- PE files are limited to 32-bit addresses, so this is not a practical limitation for PE processing
- Most assembly files rarely exceed 100MB
- Files larger than 2GB cannot be processed via ByteBuffer-based entry points

**Workaround:**
For files larger than 2GB, use the file-based entry points (`fromFile`, `fromFileInputStream`) which use memory-mapped I/O. The operating system can handle larger files via memory mapping even though individual ByteBuffer views are limited.

### 5.2 Thread Safety

`BinaryStreamReader` and `ResourceHandle` are **NOT thread-safe**.

Concurrent access produces undefined behavior. Callers must:
- Use external synchronization for shared instances
- Create separate reader instances for concurrent access
- Use `ResourceHandle.createReader()` to get independent readers

### 5.3 Concurrent Modification Risk for Heap Buffers

When using `fromByteBuffer()` with a heap-backed ByteBuffer (created via `ByteBuffer.wrap()`), the defensive slice shares the same backing array. Concurrent modification of the backing array by another thread will affect the reader.

**Mitigation:**
- Use `fromByteArray(data, copy=true)` for untrusted input
- Ensure exclusive access to the backing array
- Use direct ByteBuffers for complete isolation

---

## 6. Migration Strategy

### 6.1 Phase 1: BinaryStreamReader Refactoring (Task 7)

1. Implement new `BinaryStreamReader` class with ByteBuffer primary constructor
2. Implement factory methods in companion object
3. Implement `ResourceHandle` abstraction
4. Ensure all existing methods maintain backward-compatible behavior

### 6.2 Phase 2: ImageReader Update (Task 8)

1. Modify `ImageReader` to pass ByteBuffer + ResourceHandle to parent
2. Add security validations:
   - e_lfanew bounds check
   - Section count validation (MAX_PE_SECTIONS)
   - Metadata row limits
3. Update `getReaderAt()` to use ResourceHandle

### 6.3 Phase 3: Entry Point Updates (Tasks 3, 4, 5)

1. Add ByteBuffer entry points to `ModuleDefinition`
2. Add ByteBuffer entry points to `AssemblyDefinition`
3. Update `PortablePdb` symbol reading for ByteBuffer support
4. Update `ReaderParameters` to support symbol buffer

### 6.4 Backward Compatibility

The following existing APIs remain unchanged:

```scala
// BinaryStreamReader (existing usage still works via factory)
BinaryStreamReader(stream: FileInputStream)  // Via factory

// ModuleDefinition
ModuleDefinition.readModule(fileName: String)
ModuleDefinition.readModule(stream: FileInputStream)

// AssemblyDefinition
AssemblyDefinition.readAssembly(fileName: String)
AssemblyDefinition.readAssembly(stream: FileInputStream)

// SymbolReaderProvider
getSymbolReader(module: ModuleDefinition, fileName: String)
getSymbolReader(module: ModuleDefinition, symbolStream: FileInputStream)
```

---

## 7. Testing Strategy

See Task 10 for comprehensive test specifications. Key test categories:

| Category | Tests | Purpose |
|----------|-------|---------|
| Factory Methods | T10-A1-A7 | Verify all factory methods work correctly |
| Position Operations | T10-B1-B3 | Bounds checking, overflow protection |
| Data Type Reads | T10-C1-C7 | Correctness and allocation protection |
| Alignment | T10-D1-D3 | Alignment validation and overflow |
| Data Directory | T10-E1 | PE data directory reading |
| Property-Based | T10-F1-F4 | Invariants via ScalaCheck |
| Error Handling | T10-G1-G2 | Empty buffers, exceptions |
| Factory Equivalence | T10-H1 | All factories produce identical results |
| ResourceHandle | T10-RH1-RH2 | Resource management correctness |

---

## 8. Documentation Requirements

Per Invariant 12, the following documentation must be created:

1. **README.md** - Getting started with ByteBuffer examples
2. **architecture.md** - Theory of operation
3. **operations.md** - Operations guide
4. **troubleshooting.md** - Common issues and solutions
5. **security.md** - Security considerations
6. **LLM versions** - Parallel docs in `docs/llm/` for LLM consumption

Each claim in documentation must reference a test by name.

---

## 9. Deliverables Checklist

- [x] Class signatures for BinaryStreamReader
- [x] Class signatures for ResourceHandle abstractions
- [x] Security invariants with test references
- [x] 2GB limit documentation
- [x] Migration strategy
- [x] Backward compatibility analysis
- [x] Thread safety documentation
- [x] Factory method specifications
