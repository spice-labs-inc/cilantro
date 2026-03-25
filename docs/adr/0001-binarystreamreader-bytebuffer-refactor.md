# ADR-0001: BinaryStreamReader ByteBuffer Refactor

**Status:** Accepted

**Date:** 2026-03-25

**Deciders:** Development Team

---

## Context

`BinaryStreamReader` was originally designed around `FileInputStream` as its primary input mechanism. The class required access to `FileChannel` for memory-mapped I/O, which tied the entire codebase to file-based inputs only.

### Problem Statement

The current implementation:
```scala
class BinaryStreamReader(protected val fileInputStream: FileInputStream) {
    private val channel = fileInputStream.getChannel()
    private val byteBuffer = channel.map(MapMode.READ_ONLY, 0, channel.size)
    ...
}
```

This design has several limitations:
1. **Cannot process assemblies from byte arrays** - No support for in-memory processing
2. **Cannot read from network streams** - All inputs must be files
3. **Testing requires file system** - Cannot create lightweight unit tests with inline bytes
4. **Security-critical gaps** - No bounds checking, overflow protection, or size limits

### Security Gaps in Original Design

| Issue | Impact | Example Attack |
|-------|--------|----------------|
| No bounds checking on position | Arbitrary memory access | Set position beyond buffer to read unrelated data |
| No overflow checking in advance() | Integer overflow | `advance(Int.MaxValue)` wraps to negative |
| No alignment validation | Undefined behavior | `align(3)` on non-power-of-2 |
| No size limits in readBytes() | OOM DoS | `readBytes(Int.MaxValue)` attempts huge allocation |

---

## Decision

We will refactor `BinaryStreamReader` to use `java.nio.ByteBuffer` as its **primary** input mechanism.

### Key Design Decisions

#### 1. Type Alias for Naming Collision

The codebase already contains `cilantro.PE.ByteBuffer` (custom byte array wrapper for metadata heaps). To avoid collision:

```scala
import java.nio.{ByteBuffer => NioByteBuffer, ByteOrder}
```

All references to `ByteBuffer` in the new design refer to `java.nio.ByteBuffer` via `NioByteBuffer`.

#### 2. Primary Constructor Takes ByteBuffer

```scala
class BinaryStreamReader protected (
    protected val buffer: NioByteBuffer,
    private val resourceHandle: Option[ResourceHandle] = None
)
```

The `ResourceHandle` abstraction manages cleanup for file-based sources.

#### 3. Factory Methods in Companion Object

```scala
object BinaryStreamReader {
    def fromByteBuffer(buffer: NioByteBuffer, maxSize: Long = DEFAULT_MAX_BUFFER_SIZE): BinaryStreamReader
    def fromByteArray(array: Array[Byte], copy: Boolean = true): BinaryStreamReader
    def fromFileInputStream(stream: FileInputStream): BinaryStreamReader
    def fromFile(path: String): BinaryStreamReader
    def fromFile(path: Path): BinaryStreamReader
}
```

#### 4. Security Invariants

| Invariant | Value | Enforcement Location | Test Reference |
|-----------|-------|---------------------|----------------|
| Max buffer size | 100MB default | Factory methods | T10-A2 |
| Max read operation | 100MB | readBytes() | T10-C7 |
| Max PE sections | 96 | ImageReader.readSections | T9-SEC2 |
| Position bounds | 0 <= position <= buffer.limit() | position_= | T10-B1 |
| Integer overflow | N/A | Math.addExact | T10-B2, T10-D3 |
| Alignment | Power of 2 only | align() | T10-D2 |
| Defensive copying | Default for fromByteArray | Factory | T10-A4 |

#### 5. Defensive Slicing

All factory methods create defensive slices to isolate position/limit changes:

```scala
val slice = buffer.slice()
slice.order(ByteOrder.LITTLE_ENDIAN)
new BinaryStreamReader(slice)
```

This ensures the original buffer's position is not modified by reader operations.

---

## Consequences

### Positive Consequences

1. **In-memory processing enabled** - Can now read assemblies from byte arrays, network streams, or any ByteBuffer source
2. **Backward compatibility maintained** - File-based entry points continue to work via `fromFileInputStream`
3. **Security hardened** - Bounds checking, overflow protection, and size limits prevent common attacks
4. **Testability improved** - Unit tests can use inline byte arrays without file system dependencies
5. **Performance preserved** - File-based access still uses memory-mapped I/O

### Negative Consequences

1. **2GB size limitation** - Due to ByteBuffer's Int-based position/limit, maximum buffer size is 2GB
   - **Mitigation:** File-based entry points handle larger files via windowed memory mapping
   - **Acceptance:** PE files have 32-bit address limits anyway, so this is not a practical constraint

2. **FileInputStream is now "second-class"** - Direct construction is replaced by factory method
   - **Mitigation:** `fromFileInputStream()` factory provides same functionality

3. **Breaking change for direct instantiation** - Code that directly constructed `BinaryStreamReader(stream)` must use `BinaryStreamReader.fromFileInputStream(stream)`
   - **Mitigation:** All internal code updated; external API surface is factory methods

4. **Thread safety documented but not enforced** - Callers must synchronize or use separate instances
   - **Mitigation:** Clear documentation of thread-safety guarantees (NONE)

---

## Security Considerations

### New Attack Surface from Untrusted Buffers

When accepting ByteBuffers from untrusted sources (network, user input), new attack vectors emerge:

#### 1. Buffer Size Limits

**Threat:** Attacker provides reference to extremely large buffer (接近2GB) to cause memory pressure.

**Mitigation:**
- Default 100MB limit on buffer capacity AND limit
- Configurable maxSize parameter for specialized use cases
- Checked at factory method entry

```scala
if (buffer.capacity() > maxSize || buffer.limit() > maxSize) {
    throw new IllegalArgumentException(s"Buffer size exceeds maximum $maxSize")
}
```

**Test:** T10-A2

#### 2. TOCTOU (Time-of-Check to Time-of-Use) Attacks

**Threat:** Attacker modifies byte array after passing to reader but before reading.

**Mitigation:**
- `fromByteArray()` creates defensive copy by default (`copy=true`)
- Explicit `copy=false` option for performance when caller guarantees immutability

```scala
def fromByteArray(array: Array[Byte], copy: Boolean = true): BinaryStreamReader = {
    val data = if (copy) array.clone() else array
    fromByteBuffer(NioByteBuffer.wrap(data))
}
```

**Test:** T10-A4

#### 3. Integer Overflow Prevention

**Threat:** Attacker manipulates position calculations to cause integer overflow.

**Mitigation:**
- `Math.addExact()` used in all position arithmetic
- `advance()`, `align()` operations check overflow

```scala
def advance(bytes: Int): Unit = {
    val newPos = Math.addExact(buffer.position(), bytes)
    position = newPos
}
```

**Tests:** T10-B2, T10-D3

#### 4. PE Validation Requirements

**Threat:** Malformed PE files with invalid headers, excessive section counts, or corrupted metadata.

**Mitigation in ImageReader:**
- e_lfanew bounds validation (0x40 <= offset <= fileLength - 4)
- Section count limit (MAX_PE_SECTIONS = 96)
- Metadata row limits per table

```scala
// e_lfanew validation
if (peOffset < 0x40 || peOffset > fileLength - 4) {
    throw new DataFormatException(s"Invalid PE offset: $peOffset")
}

// Section count validation
if (sections > MAX_PE_SECTIONS) {
    throw new DataFormatException(s"Section count $sections exceeds maximum")
}
```

**Tests:** T9-SEC1, T9-SEC2, T9-SEC3

#### 5. Allocation Protection

**Threat:** Attacker requests huge byte array allocation via `readBytes()`.

**Mitigation:**
- MAX_READ_SIZE = 100MB limit on readBytes()
- Checked before allocation

```scala
def readBytes(length: Int): Array[Byte] = {
    if (length > MAX_READ_SIZE) {
        throw new IllegalArgumentException(s"Read size exceeds maximum $MAX_READ_SIZE")
    }
    ...
}
```

**Test:** T10-C7

---

## LLM-Friendly Section: Trade-Off Analysis

### Why ByteBuffer Over Alternatives?

| Alternative | Pros | Cons | Decision |
|-------------|------|------|----------|
| **ByteBuffer (chosen)** | Native Java NIO, memory-mapped I/O support, little-endian support, well-tested | 2GB limit | ✅ Selected |
| Array[Byte] with index | No 2GB limit, simple | No memory mapping, manual endianness handling | ❌ Rejected |
| RandomAccessFile | Supports large files | Not suitable for in-memory buffers, slower | ❌ Rejected |
| InputStream | Universal | No random access, no position control | ❌ Rejected |

### Why Defensive Slicing?

| Option | Pros | Cons | Decision |
|--------|------|------|----------|
| **Defensive slice (chosen)** | Isolates position changes, prevents caller from modifying reader state | Small overhead for slice creation | ✅ Selected |
| Direct buffer use | Zero overhead | Caller modifications affect reader, position conflicts | ❌ Rejected |
| Copy buffer | Complete isolation | Memory overhead for large buffers | ❌ Rejected (too expensive) |

### Why ResourceHandle Abstraction?

| Option | Pros | Cons | Decision |
|--------|------|------|----------|
| **ResourceHandle trait (chosen)** | Unified cleanup, position-independent reader creation | Additional abstraction layer | ✅ Selected |
| Store FileInputStream directly | Simple | Doesn't work for ByteBuffer sources | ❌ Rejected |
| AutoCloseable only | Standard Java pattern | No way to create new readers from same source | ❌ Rejected |

### Performance Trade-Offs

| Aspect | Before (FileInputStream) | After (ByteBuffer) | Impact |
|--------|-------------------------|-------------------|--------|
| File reading | Memory-mapped | Memory-mapped | ✅ No change |
| Byte array reading | Not supported | Wrapped as ByteBuffer | ✅ New capability |
| Position changes | Channel position | Buffer position | ⚡ Slightly faster |
| Bounds checking | None | Always | ⚡ Small overhead, major security gain |
| Memory footprint | One mapped buffer | Sliced buffers share backing storage | ✅ Efficient |

### Thread Safety Trade-Off

**Decision:** Document as NOT thread-safe rather than add synchronization.

**Rationale:**
- Synchronization adds overhead to every operation
- Most use cases are single-threaded
- `ResourceHandle.createReader()` allows callers to get independent readers for concurrent access
- Callers can add external synchronization if needed

---

## References

1. Plan Document: `2026_03_25_support_in_memory.md`
2. Design Document: `docs/task-2-binarystreamreader-design.md`
3. Ownership Contract: `docs/binarystreamreader-contract.md`
4. Security Tests: `src/cilantro/src/test/scala/cilantro.PE/BinaryStreamReaderTests.scala`
5. Original Cecil Implementation: https://github.com/jbevain/cecil
