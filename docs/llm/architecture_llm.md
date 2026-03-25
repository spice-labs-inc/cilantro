# Cilantro Architecture for LLMs

## Core Design Pattern

**Pattern:** Factory + ResourceHandle abstraction for position-independent readers

**Key Insight:** Separates data source management (ResourceHandle) from reading operations (BinaryStreamReader), enabling both file-based and in-memory processing with uniform cleanup.

---

## Component Hierarchy

```
BinaryStreamReader (cilantro.PE)
├── buffer: NioByteBuffer           // Primary input mechanism
├── resourceHandle: Option[ResourceHandle]  // Cleanup management
├── read*(): Primitive types        // Little-endian reads
├── position operations             // Bounds-checked
└── align()                         // Power-of-2 validation

ResourceHandle (private sealed trait)
├── FileResourceHandle
│   ├── channel: FileChannel        // Memory-mapped
│   ├── createReader() -> new mapped view
│   └── close() -> closes channel + stream
└── BufferResourceHandle
    ├── buffer: NioByteBuffer       // Slice on createReader()
    └── close() -> no-op

ImageReader extends BinaryStreamReader
├── Parses PE headers
├── Validates security invariants
└── Creates Image with metadata

Image (parsed PE representation)
├── resourceHandle: Option[ResourceHandle]  // For getReaderAt()
├── sections: Array[Section]
├── *Heap metadata structures
└── getReaderAt(rva: Int) -> BinaryStreamReader
```

---

## Security Architecture

**Layered Validation:**

1. **Factory Layer**
   - Null checks
   - Size limits (capacity AND limit)
   - Defensive slicing

2. **Position Layer**
   - Bounds checking [0, limit]
   - Integer overflow prevention (Math.addExact)
   - Alignment validation (power-of-2)

3. **PE Parsing Layer**
   - Minimum file size (128 bytes)
   - e_lfanew range validation
   - Section count limit (96)
   - Metadata row non-negativity

**Test Mapping:**
- Factory: `T10-A1-A7`
- Position: `T10-B1-B3`, `T10-D1-D3`
- PE: `T9-SEC1-4`

---

## Data Flow Comparison

### File-Based
```
FileInputStream
  -> FileResourceHandle (manages channel)
    -> ImageReader (parses, extends BinaryStreamReader)
      -> Image (stores ResourceHandle for later)
        -> ModuleDefinition (high-level API)
```

### ByteBuffer-Based
```
ByteBuffer (any source)
  -> BufferResourceHandle (slices on demand)
    -> ImageReader (parses, extends BinaryStreamReader)
      -> Image (stores ResourceHandle)
        -> ModuleDefinition (high-level API)
```

**Key Difference:** Only FileResourceHandle requires explicit cleanup. BufferResourceHandle is GC-managed.

---

## Thread Safety Model

**Explicitly NOT Thread-Safe:**
- BinaryStreamReader: Single-threaded use only
- ResourceHandle: createReader() safe, close() requires coordination

**Safe Concurrent Patterns:**
1. Separate reader per thread via `handle.createReader()`
2. External synchronization around shared reader
3. Separate ModuleDefinition instances per thread

**Test Evidence:** `T10-RH1` (independent readers)

---

## ByteBuffer Handling

### Defensive Slicing
```scala
// All factory methods slice to isolate position
val slice = buffer.slice()
slice.order(ByteOrder.LITTLE_ENDIAN)
new BinaryStreamReader(slice)
```

**Effect:** Original buffer position unchanged; slice has independent position.

**Test Evidence:** `T10-A5`

### Heap vs Direct
- **Heap:** `ByteBuffer.wrap(array)` - shares backing array
- **Direct:** `ByteBuffer.allocateDirect()` - off-heap, no sharing

**Security:** Heap buffers risk concurrent array modification. Use `copy=true` for untrusted.

**Test Evidence:** `T10-A6`

---

## Key Design Decisions

### Why ByteBuffer as Primary Input?
- Enables in-memory processing (byte arrays, network)
- Native little-endian support
- Memory-mapped I/O for files
- Trade-off: 2GB limit acceptable for PE files (32-bit addresses)

### Why ResourceHandle Abstraction?
- Unified cleanup interface (close())
- Position-independent reader creation (createReader())
- Works with both file and buffer sources
- Image.getReaderAt() requires this for RVA resolution

### Why Defensive Slicing by Default?
- Prevents caller from manipulating reader state
- Small overhead (slice creation)
- Alternative (copy buffer) too expensive for large files

---

## Extension Points

### Adding New Factory Methods
```scala
def fromSource(source: CustomSource): BinaryStreamReader = {
    // 1. Validate
    // 2. Create ResourceHandle subclass
    // 3. Create ByteBuffer view
    // 4. new BinaryStreamReader(buffer, Some(handle))
}
```

### Custom ResourceHandle
```scala
private class CustomResourceHandle extends ResourceHandle {
    def createReader(): BinaryStreamReader = {
        val buffer = // create view
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        new BinaryStreamReader(buffer, None)  // Or Some(this) if cleanup needed
    }
    def length: Long = // calculate
    def close(): Unit = // cleanup
}
```

---

## Performance Considerations

| Operation | Cost | Mitigation |
|-----------|------|------------|
| Defensive slice | O(1) | Minimal, just metadata |
| Defensive copy | O(n) | Use `copy=false` for trusted |
| Memory mapping | OS-managed | Efficient for large files |
| Position check | O(1) | Always bounds check |

---

## Error Handling Strategy

**Fail Fast:** All validation at entry points
- Null checks in factories
- Size limits before allocation
- Bounds before position set
- PE structure during parse

**Exception Types:**
- `IllegalArgumentException`: Input validation
- `BufferUnderflowException`: Read beyond buffer
- `DataFormatException`: PE format violations
- `ArithmeticException`: Overflow in position math

---

## Related
- Usage: `README_llm.md`
- Security: `../security.md`
- Full docs: `../architecture.md`
- ADR: `../adr/0001-binarystreamreader-bytebuffer-refactor.md`
