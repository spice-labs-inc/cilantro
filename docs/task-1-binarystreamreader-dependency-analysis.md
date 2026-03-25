# Task 1: BinaryStreamReader Dependency Analysis

**Date:** 2026-03-25
**Status:** Complete

---

## Executive Summary

This document analyzes all dependencies and usage patterns of `BinaryStreamReader` in the cilantro codebase to inform the refactoring to support `java.nio.ByteBuffer` as the primary input mechanism.

---

## 1. Current BinaryStreamReader Implementation

### File: `src/cilantro/src/main/scala/cilantro.PE/BinaryStreamReader.scala`

```scala
class BinaryStreamReader(protected val fileInputStream: FileInputStream) {
    private val channel = fileInputStream.getChannel()
    private val byteBuffer = {
        val bb = channel.map(MapMode.READ_ONLY, 0, channel.size)
        bb.order(ByteOrder.LITTLE_ENDIAN)
        bb
    }
    ...
}
```

### Current API Surface

| Method | Signature | Notes |
|--------|-----------|-------|
| Constructor | `BinaryStreamReader(FileInputStream)` | Requires FileInputStream for channel access |
| position | `def position: Int` | Gets current buffer position |
| position_= | `def position_=(value: Int)` | Sets buffer position (NO BOUNDS CHECKING) |
| length | `def length: Int` | Returns channel size as Int |
| readByte | `def readByte(): Byte` | Reads single byte |
| readInt16 | `def readInt16(): Short` | Reads little-endian short |
| readUInt16 | `def readUInt16(): Char` | Reads little-endian unsigned short |
| readInt32 | `def readInt32(): Int` | Reads little-endian int |
| readInt64 | `def readInt64(): Long` | Reads little-endian long |
| readBoolean | `def readBoolean(): Boolean` | Reads byte as boolean |
| readBytes | `def readBytes(length: Int): Array[Byte]` | Reads byte array (NO SIZE LIMITS) |
| advance | `def advance(bytes: Int)` | Advances position (NO OVERFLOW CHECK) |
| moveTo | `def moveTo(position: Int)` | Absolute position set |
| align | `def align(align: Int)` | Aligns to boundary (NO VALIDATION) |
| readDataDirectory | `def readDataDirectory(): DataDirectory` | Reads PE data directory |

### Critical Security Gaps in Current Implementation

1. **No bounds checking** on `position_=` - can set position beyond buffer limit
2. **No overflow checking** in `advance()` - integer overflow possible
3. **No alignment validation** in `align()` - non-power-of-2 values accepted
4. **No size limits** in `readBytes()` - can allocate arbitrarily large arrays
5. **FileInputStream coupling** - cannot work with in-memory byte arrays

---

## 2. Call Sites Analysis

### 2.1 ImageReader (Subclass)

**File:** `src/cilantro/src/main/scala/cilantro.PE/ImageReader.scala:38`

```scala
class ImageReader(stream: Disposable[FileInputStream], file_name: String)
    extends BinaryStreamReader(stream.value)
```

**Dependencies:**
- Takes `Disposable[FileInputStream]` as constructor parameter
- Passes underlying `FileInputStream` to parent `BinaryStreamReader`

**Security-Critical Parsing Paths in ImageReader:**

| Method | Line | Critical Operation |
|--------|------|-------------------|
| `readImage()` | 52-96 | PE header parsing, DOS header, PE signature |
| `readOptionalHeaders()` | 103-191 | PE optional headers, data directories |
| `readSections()` | 218-245 | Section table parsing (NO COUNT VALIDATION) |
| `readMetadata()` | 272-302 | CLI metadata header, stream headers |
| `readTableHeap()` | 367-408 | Metadata table heap, row counts |
| `readDebugHeader()` | 304-336 | Debug directory entries |

**Specific Security-Critical Operations:**

1. **PE Offset Validation (line 68)**:
   ```scala
   moveTo(readInt32())  // e_lfanew - NO VALIDATION
   ```
   - Reads PE header offset from DOS header without bounds checking
   - Could jump to arbitrary position in file

2. **Section Count (line 79)**:
   ```scala
   val sections = readUInt16()  // NO MAXIMUM CHECK
   ```
   - Reads section count without validation
   - Could allocate excessive memory in `readSections()`

3. **Metadata Row Counts (lines 397-402)**:
   ```scala
   heap.tables(i).length = readInt32()  // NO VALIDATION
   ```
   - Reads per-table row counts without limits
   - Could claim billions of rows

### 2.2 Image (getReaderAt)

**File:** `src/cilantro/src/main/scala/cilantro.PE/Image.scala:87-104`

```scala
def getReaderAt(rva: Int): BinaryStreamReader =
    val section = getSectionAtVirtualAddress(rva)
    section match
        case Some(section) => {
            val reader = BinaryStreamReader(stream.value)  // NEW INSTANCE
            reader.moveTo(resolveVirtualAddressInSection(rva, section))
            reader
        }
        case _ => null
```

**Dependencies:**
- Creates NEW `BinaryStreamReader` instances from stored `Disposable[FileInputStream]`
- Requires access to original stream to create new channel/reader
- Used for random access to different RVA positions

**Thread Safety Concern:**
- `getReaderAt` creates independent readers but uses shared underlying stream
- Position restoration in `getReaderAt[TItem, TRet]` (lines 98-104) uses channel position

### 2.3 Existing Test Usage

**SmokeTests.scala:33:**
```scala
var binstmreader = BinaryStreamReader(filestream)
```

**Test Pattern:**
- Direct instantiation with `FileInputStream`
- Used for seeking and reading bytes for comparison

---

## 3. Interface Contracts

### 3.1 Public API Contract (Current)

BinaryStreamReader is effectively a public API class. The following contracts must be preserved:

1. **Little-endian byte order** - All multi-byte reads are little-endian
2. **Position-based access** - Read operations advance position
3. **Array allocation** - `readBytes` returns new array each call

### 3.2 Resource Management Contract

**Current Pattern:**
```scala
// Image stores Disposable[FileInputStream]
var stream: Disposable[FileInputStream] = null

// ImageReader receives Disposable in constructor
class ImageReader(stream: Disposable[FileInputStream], ...)

// BinaryStreamReader extracts FileInputStream
class BinaryStreamReader(protected val fileInputStream: FileInputStream)
```

**Lifecycle:**
1. ModuleDefinition opens `FileInputStream`
2. Wraps in `Disposable.owned()`
3. Passed to `ImageReader.readImage()`
4. `ImageReader` passes underlying stream to parent `BinaryStreamReader`
5. Image stores `Disposable` for later `getReaderAt()` calls
6. `Image.close()` disposes the stream

### 3.3 Symbol Stream Usage

**ReaderParameters (ModuleDefinition.scala:51,77-78):**
```scala
private var _symbol_stream: FileInputStream = null
def symbolStream = _symbol_stream
def symbolStream_=(value: FileInputStream) = _symbol_stream = value
```

**SymbolReaderProvider (Symbols.scala:76-78):**
```scala
trait SymbolReaderProvider {
    def getSymbolReader(module: ModuleDefinition, fileName: String): SymbolReader
    def getSymbolReader(module: ModuleDefinition, symbolStream: FileInputStream): SymbolReader
}
```

**Current State:** The `getSymbolReader(module, symbolStream)` method is declared but largely unimplemented (commented out code in DefaultSymbolReaderProvider).

---

## 4. Position-Dependent Operations

### 4.1 Operations That Modify Position

| Operation | Method | Bounds Check | Overflow Check |
|-----------|--------|--------------|----------------|
| Absolute set | `position_=` | NO | N/A |
| Relative advance | `advance()` | NO | NO |
| Alignment | `align()` | NO | NO |
| Absolute move | `moveTo()` | NO | N/A |
| Read byte | `readByte()` | NO (throws BufferUnderflowException) | N/A |
| Read array | `readBytes()` | NO (pre-check missing) | YES (implicit) |

### 4.2 Thread Safety Requirements

**Current State:** `BinaryStreamReader` is NOT thread-safe.

**Concurrent Access Scenarios:**

1. **Single Image, Multiple getReaderAt calls:**
   - Each call creates new `BinaryStreamReader` with shared `FileInputStream`
   - Channel position is saved/restored in `getReaderAt[TItem, TRet]`
   - **Race condition:** If two threads call simultaneously, position may corrupt

2. **Single BinaryStreamReader, Multiple threads:**
   - Position is single shared state
   - Concurrent reads would interleave unpredictably

---

## 5. Security-Critical Paths Summary

### 5.1 PE Header Parsing (ImageReader.readImage)

**Entry Point:** `ImageReader.readImage()` line 52

**Attack Surface:**
- DOS header signature (0x5A4D) - checked
- PE offset (e_lfanew) - **NOT BOUNDS CHECKED**
- PE signature (0x00004550) - checked
- Section count - **NOT VALIDATED**

**Required Validations (per plan):**
```scala
// e_lfanew validation
if (peOffset < 0x40 || peOffset > fileLength - 4) {
    throw new DataFormatException(s"Invalid PE offset: $peOffset")
}

// Section count validation
if (count > MAX_PE_SECTIONS) {  // MAX_PE_SECTIONS = 96
    throw new DataFormatException(s"Section count $count exceeds maximum")
}
```

### 5.2 Metadata Parsing (ImageReader.readMetadata, readTableHeap)

**Entry Points:**
- `readMetadata()` line 272
- `readTableHeap()` line 367

**Attack Surface:**
- Metadata signature (0x424A5342) - checked
- Stream count - no validation
- Per-table row counts - **NOT VALIDATED**

**Required Validations (per plan):**
```scala
// Metadata row limits
val maxRows = MaxMetadataRows.getOrElse(table, 0xFFFFFF)
if (rowCount > maxRows) {
    throw new DataFormatException(s"Row count $rowCount exceeds maximum $maxRows")
}
```

### 5.3 Debug Directory Parsing (ImageReader.readDebugHeader)

**Entry Point:** `readDebugHeader()` line 304

**Attack Surface:**
- `sizeOfData` field used to allocate byte array - **NO SIZE LIMIT**
- `pointerToRawData` used to seek - **NO BOUNDS CHECK**

**Required Validations:**
- Maximum debug directory entry size
- Pointer validation against file bounds

---

## 6. Resource Cleanup Requirements

### 6.1 Current Cleanup Chain

```
ModuleDefinition.close()
  └── image.close()
      └── stream.dispose()  // Disposable[FileInputStream]
          └── if owned: fileInputStream.close()
```

### 6.2 New ResourceHandle Abstraction Requirements

**Per Plan Design:**
```scala
private sealed trait ResourceHandle {
    def createReader(): BinaryStreamReader  // Position-independent
    def length: Long
    def close(): Unit
}
```

**Responsibilities:**
1. **FileResourceHandle:** Owns `FileInputStream`, creates readers from memory-mapped buffer
2. **BufferResourceHandle:** Owns `NioByteBuffer` (or reference to it), creates readers from slice

**Cleanup Requirements:**
- File-based: Close FileInputStream and release mapped buffer
- Buffer-based: No-op or reference release (buffer owned by caller)

---

## 7. Naming Collision Analysis

### Existing ByteBuffer Class

**File:** `src/cilantro/src/main/scala/cilantro.PE/ByteBuffer.scala`

```scala
open class ByteBuffer(var buffer: Array[Byte]) {
    var length = 0
    var position = 0
    ...
}
```

**Usage:**
- Used in `ImageReader.readPdbHeap()` line 502
- Used in metadata heap implementations (StringHeap, BlobHeap, etc.)

**Resolution:**
Use type alias for `java.nio.ByteBuffer`:
```scala
import java.nio.{ByteBuffer => NioByteBuffer, ByteOrder}
```

---

## 8. Deliverables Checklist

- [x] All touch points documented
- [x] Interface contracts identified
- [x] Security-critical parsing paths mapped
- [x] Resource cleanup requirements specified
- [x] Position-dependent operations listed
- [x] Thread-safety concerns documented
- [x] Naming collision identified and resolution specified

---

## 9. References

1. Plan Document: `2026_03_25_support_in_memory.md`
2. BinaryStreamReader: `src/cilantro/src/main/scala/cilantro.PE/BinaryStreamReader.scala`
3. ImageReader: `src/cilantro/src/main/scala/cilantro.PE/ImageReader.scala`
4. Image: `src/cilantro/src/main/scala/cilantro.PE/Image.scala`
5. ModuleDefinition: `src/cilantro/src/main/scala/cilantro/ModuleDefinition.scala`
6. AssemblyDefinition: `src/cilantro/src/main/scala/cilantro/AssemblyDefinition.scala`
7. Symbols: `src/cilantro/src/main/scala/cilantro/Cil/Symbols.scala`
8. PortablePdb: `src/cilantro/src/main/scala/cilantro/Cil/PortablePdb.scala`
9. ByteBuffer (existing): `src/cilantro/src/main/scala/cilantro.PE/ByteBuffer.scala`
10. Disposable: `src/cilantro/src/main/scala/cilantro/Disposable.scala`
