# Cilantro Security Guide

## Overview

Cilantro is designed with security as a primary concern. This document describes the security invariants, potential attack vectors, and recommended practices for secure usage.

---

## Security Invariants

### 1. Buffer Size Limits

**Invariant:** Buffer capacity AND limit must not exceed the maximum allowed size.

**Default:** 100MB (`DEFAULT_MAX_BUFFER_SIZE`)

**Enforcement:**

**Test Reference:** `T10-A2: fromByteBuffer rejects buffer with capacity exceeding max size`, `T10-A2: fromByteBuffer rejects buffer with limit exceeding max size`

```scala
def fromByteBuffer(buffer: NioByteBuffer, maxSize: Long = DEFAULT_MAX_BUFFER_SIZE): BinaryStreamReader = {
    if (buffer.capacity() > maxSize || buffer.limit() > maxSize) {
        throw new IllegalArgumentException(s"Buffer size exceeds maximum $maxSize")
    }
    // ...
}
```

**Attack Mitigation:** Prevents memory exhaustion from oversized buffers.

---

### 2. Read Operation Size Limits

**Invariant:** Single `readBytes()` operation cannot exceed 100MB.

**Default:** 100MB (`MAX_READ_SIZE`)

**Enforcement:**

**Test Reference:** `T10-C7: readBytes rejects excessive length before allocation`

```scala
def readBytes(length: Int): Array[Byte] = {
    if (length > MAX_READ_SIZE) {
        throw new IllegalArgumentException(s"Read size $length exceeds maximum $MAX_READ_SIZE")
    }
    // ...
}
```

**Attack Mitigation:** Prevents OutOfMemoryError from maliciously large allocation requests.

---

### 3. Position Bounds Checking

**Invariant:** Position must always be within [0, buffer.limit()].

**Enforcement:**

**Test Reference:** `T10-B1: position setter rejects negative value`, `T10-B1: position setter rejects value beyond limit`

```scala
def position_=(value: Int): Unit = {
    if (value < 0 || value > buffer.limit()) {
        throw new IllegalArgumentException(s"Position $value out of bounds")
    }
    buffer.position(value)
}
```

**Attack Mitigation:** Prevents arbitrary memory access through out-of-bounds positions.

---

### 4. Integer Overflow Prevention

**Invariant:** All position arithmetic must detect and prevent integer overflow.

**Enforcement:**

**Test Reference:** `T10-B2: advance throws when result exceeds limit`, `T10-D3: align throws on overflow`

```scala
def advance(bytes: Int): Unit = {
    val newPos = Math.addExact(buffer.position(), bytes)  // Throws on overflow
    position = newPos
}

def align(alignment: Int): Unit = {
    val newPos = Math.addExact(pos, aa) & ~aa  // Overflow checked before mask
    position = newPos
}
```

**Attack Mitigation:** Prevents wrap-around attacks (e.g., `advance(Int.MaxValue)` wrapping to negative).

---

### 5. Alignment Validation

**Invariant:** Alignment values must be positive powers of 2.

**Enforcement:**

**Test Reference:** `T10-D2: align rejects non-power-of-2 values`

```scala
def align(alignment: Int): Unit = {
    if (alignment <= 0) {
        throw new IllegalArgumentException("Alignment must be positive")
    }
    if ((alignment & (alignment - 1)) != 0) {
        throw new IllegalArgumentException("Alignment must be power of 2")
    }
}
```

**Attack Mitigation:** Prevents undefined behavior from invalid alignment values.

---

### 6. Defensive Copying (TOCTOU Protection)

**Invariant:** Byte array input is defensively copied by default to prevent Time-of-Check to Time-of-Use attacks.

**Enforcement:**

**Test Reference:** `T10-A4: fromByteArray with copy=true is immune to concurrent modification`

```scala
def fromByteArray(array: Array[Byte], copy: Boolean = true): BinaryStreamReader = {
    val data = if (copy) array.clone() else array
    fromByteBuffer(NioByteBuffer.wrap(data))
}
```

**Attack Mitigation:** Prevents attacker from modifying array after validation but before use.

---

### 7. Defensive Slicing

**Invariant:** Factory methods create defensive slices to isolate position changes.

**Enforcement:**

**Test Reference:** `T10-A5: fromByteBuffer does not modify original buffer position`

```scala
def fromByteBuffer(buffer: NioByteBuffer, maxSize: Long): BinaryStreamReader = {
    // ... validation ...
    val slice = buffer.slice()  // Isolate position changes
    slice.order(ByteOrder.LITTLE_ENDIAN)
    new BinaryStreamReader(slice)
}
```

**Attack Mitigation:** Prevents caller from manipulating reader position through original buffer.

---

### 8. PE Header Validation

**Invariant:** PE files must have valid structure and not exceed security limits.

**Enforcement:**

**Test Reference:** `T9-SEC1: rejects PE with invalid e_lfanew offset`, `T9-SEC2: rejects PE with section count > 96`

| Check | Limit | Test |
|-------|-------|------|
| Minimum file size | 128 bytes | `T9-SEC4: rejects file too small` |
| DOS signature | Must be 0x5A4D (MZ) | `T9-SEC3b: rejects PE with invalid DOS signature` |
| PE offset (e_lfanew) | 0x40 <= offset <= fileLength - 4 | `T9-SEC1: rejects PE with invalid e_lfanew offset` |
| PE signature | Must be 0x00004550 (PE\0\0) | `T9-SEC3c: rejects PE with invalid PE signature` |
| Number of sections | Maximum 96 | `T9-SEC2: rejects PE with section count > 96` |
| Metadata row counts | Non-negative | `ImageReader.scala:449-450` |

**Attack Mitigation:** Prevents parser exploitation through malformed PE files.

---

### 9. Null Input Rejection

**Invariant:** Factory methods reject null inputs.

**Enforcement:**

**Test Reference:** `T10-A2: fromByteBuffer rejects null buffer`

```scala
def fromByteBuffer(buffer: NioByteBuffer, maxSize: Long): BinaryStreamReader = {
    if (buffer == null) {
        throw new IllegalArgumentException("Buffer cannot be null")
    }
    // ...
}
```

---

### 10. RVA Validation

**Invariant:** Relative Virtual Addresses must be non-negative.

**Enforcement:**

```scala
def getReaderAt(rva: Int): BinaryStreamReader = {
    if (rva < 0) throw new IllegalArgumentException("RVA cannot be negative")
    // ...
}
```

**Location:** `cilantro.PE/Image.scala:104`

---

## Threat Model

### Attacker Capabilities

1. **Network attacker:** Can provide crafted PE files or byte arrays
2. **Local attacker:** Can modify shared memory or files
3. **Malicious input:** PE files designed to crash or exploit the parser

### Attack Scenarios

#### DoS via Memory Exhaustion

**Threat:** Attacker provides extremely large buffer to cause OOM.

**Mitigation:** 100MB default size limit on buffer capacity AND limit.

**Test Reference:** `T10-A2: fromByteBuffer rejects buffer with capacity exceeding max size`

#### DoS via Allocation

**Threat:** Attacker requests huge byte array allocation.

**Mitigation:** MAX_READ_SIZE (100MB) limit checked before allocation.

**Test Reference:** `T10-C7: readBytes rejects excessive length before allocation`

#### Integer Overflow

**Threat:** Attacker manipulates position calculations to cause overflow.

**Mitigation:** `Math.addExact()` used in all arithmetic operations.

**Test Reference:** `T10-B2: advance throws when result exceeds limit`

#### TOCTOU Attack

**Threat:** Attacker modifies byte array after reader creation.

**Mitigation:** Defensive copy by default (`copy=true`).

**Test Reference:** `T10-A4: fromByteArray with copy=true is immune to concurrent modification`

#### Malformed PE Parsing

**Threat:** Invalid PE structure causes parser crashes or infinite loops.

**Mitigation:** Comprehensive validation of all PE structures.

**Test Reference:** `T9-SEC1: rejects PE with invalid e_lfanew offset`, `T9-SEC2: rejects PE with section count > 96`

---

## Security Best Practices

### For Untrusted Input

```scala
// 1. Always use defensive copy for untrusted input
val untrustedData = receiveFromNetwork()
val reader = BinaryStreamReader.fromByteArray(untrustedData, copy = true)

// 2. Use appropriate size limits
val reader = BinaryStreamReader.fromByteBuffer(
    buffer,
    maxSize = 50 * 1024 * 1024  // 50MB limit for untrusted
)

// 3. Handle exceptions appropriately
try {
    val module = ModuleDefinition.readModule(buffer)
} catch {
    case e: IllegalArgumentException =>
        // Invalid input (too large, null, etc.)
        logSecurityEvent("Invalid input rejected", e)
    case e: DataFormatException =>
        // Malformed PE file
        logSecurityEvent("Malformed PE rejected", e)
}
```

### For Trusted Input

```scala
// Can skip defensive copy for better performance
val data = Files.readAllBytes(Paths.get("known-good.dll"))
val reader = BinaryStreamReader.fromByteArray(data, copy = false)
```

### Resource Cleanup

```scala
// Always close resources to prevent file handle leaks
val module = ModuleDefinition.readModule("assembly.dll")
try {
    // Process module...
} finally {
    module.close()
}
```

**Test Reference:** `T10-RH1: FileResourceHandle.close closes channel and stream`

---

## Security Testing

### Running Security Tests

```bash
# Run all tests
cd src/cilantro && sbt test

# Security-focused tests are prefixed with T9-SEC and T10-
```

### Security Test Coverage

| Category | Tests | Purpose |
|----------|-------|---------|
| Size limits | T10-A2 | Buffer size enforcement |
| Allocation limits | T10-C7 | readBytes protection |
| Bounds checking | T10-B1 | Position validation |
| Overflow | T10-B2, T10-D3 | Integer overflow prevention |
| Alignment | T10-D2 | Alignment validation |
| TOCTOU | T10-A4 | Defensive copying |
| Defensive slicing | T10-A5 | Buffer isolation |
| PE validation | T9-SEC1-4 | PE structure validation |

---

## Known Limitations

### 2GB Size Limit

Due to `java.nio.ByteBuffer` using `Int` for position/limit, `BinaryStreamReader` is limited to **2GB maximum buffer size**.

**Impact:** PE files > 2GB cannot be processed via ByteBuffer entry points.

**Mitigation:** Use file-based entry points for large files.

**Test Reference:** `T10-A2: fromByteBuffer accepts buffer at exactly max size`

### Thread Safety

`BinaryStreamReader` is **NOT thread-safe**. Concurrent access produces undefined behavior.

**Mitigation:** Use external synchronization or separate reader instances.

**Test Reference:** `T10-RH1: FileResourceHandle.createReader produces independent readers`

---

## Reporting Security Issues

If you discover a security vulnerability:

1. **DO NOT** open a public issue
2. Email security concerns to the maintainers
3. Include reproduction steps and impact assessment

---

## Related Documents

- [README](README.md) - Getting started
- [Architecture](architecture.md) - Design overview
- [Operations Guide](operations.md) - Common operations
- [Troubleshooting](troubleshooting.md) - Common issues
- [ByteBuffer Contract](binarystreamreader-contract.md) - Ownership details
- [ADR-0001](adr/0001-binarystreamreader-bytebuffer-refactor.md) - Security design decisions
