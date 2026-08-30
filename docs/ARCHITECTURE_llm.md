# Architecture (LLM copy)

Machine-oriented architecture facts. Human narrative: ARCHITECTURE.md.

## Module responsibilities

- `cilantro.PE.ImageReader` — header walk only (DOS→PE→COFF→optional→
  sections→CLI→metadata root→streams→table heap). Owns the caps:
  `MaxSections=96`, `MaxSectionSize=512MB`, `MaxTableRows=10_000_000`,
  `MaxHeapSize=256MB` (named heaps only; #~ exempt). Cap violation =
  `DataFormatException` → clean `Try` Failure.
- `cilantro.PE.Image` — sections, RVA resolution
  (`getSectionAtVirtualAddress` uses sizeOfRawData),
  `getTableLength`/index sizes, coded index sizes (memoized).
- `cilantro.metadata` — `TableHeap` (valid/sorted bits, row counts,
  `computeTableInformations` row offsets), heaps. Blob index = heap
  offset. UserStringHeap: unsigned length, flag bit, low-byte sign fix.
- `cilantro.AssemblyReader` — the Cecil `AssemblyReader`+`MetadataSystem`
  port. Key behaviors: early `metadata.addTypeDefinition` before the
  base-type read (cycle memoization), `readTypeDefinition` depth cap
  128, `SignatureReader.readTypeSignature` depth cap 128, `TypeParser`
  depth cap 128, `getMemberReference` skips the cache when
  `containsGenericParameter`, `readGenericConstraints` sets the context
  to the owner, `readCustomAttributeEnum` resolves only within the
  loading module (else ResolutionException → attribute cleared).
- `cilantro.Cil.CodeReader` — instruction decode; boundary checks per
  operand; branch/switch targets validated on instruction starts;
  switch cap 65536. Two-byte opcodes (0xFE prefix); switch table
  unaligned (ADR-0004).
- `cilantro.Cil.MethodBodyReader` — RVA→body, 64MB cap, tiny/fat
  header, EH section walk, locals via StandAloneSig.
- `cilantro.Cil.EhReader` — small/fat clauses; IL-relative offsets
  (ADR-0005); mono-ilasm quirks (4-clause small sections).
- `cilantro.Cil.ParityDumper` — golden-shape serializer. JSON escape:
  `"` `\` `\b\f\n\r\t` escapes, `<>&'+`\u0060 → \uXXXX, non-ASCII →
  \uXXXX uppercase, lone surrogates → \uFFFD, valid pairs kept.
  Numbers: C# "R" shortest round-trip for float/double (BigDecimal
  digit-shortening, integral branch <1e15, -0 preserved, exponent
  E±dd). Attribute values: CilNullConstant → "null", typeof(...),
  u1/u2/u4/u8 unsigned, i1/i2 signed, Char/Boolean casing.
- `cilantro.TypeParser` — CA type strings; `parsePart` stops at
  `+ , [ ] * &`; nested `+` chain; assembly suffix; same-module
  definition lookup first.
- `cilantro.LogSanitizer` — replaces <0x20 (except \n\t), ESC, DEL,
  bidi overrides/isolates with U+FFFD.

## Lazy loading graph

`readModule` → ImageReader (eager headers only). Model = lazy:
types→(fields/methods/properties/events/nested/generics), methods→
parameters/body on demand. `initializeTypeDefinitions` walks the
TypeDef table; per-type ranges come from Field/Method/Param/PropertyMap/
EventMap/NestedClass/InterfaceImpl/GenericParam tables.

## Test harness wiring

- `ParityHarnessTests` fast gate: ilasm_fixture, x64_fixture, net20
  Newtonsoft.Json (tier1+tier2 byte-diff vs `corpus/golden/...`).
  Slow: every manifest assembly; `mixedMode` skips tier2; `corrupt`
  tolerates load failure. munit timeout raised to 120 min.
- `CorpusPropertyTests` (Slow): C4-04 parse-all accounting, C4-05 token
  round-trip, C4-07 fuzz (4 mutations × every assembly) + helper
  verdict comparison via `docker run ... GoldenDumper.dll probe`
  (mounts the temp dir + `scripts/GoldenDumper`).
- `CapTests` uses `MinimalPeBuilder` (synthetic PE: DOS/PE/COFF/PE32
  optional/1..n sections/CLI/metadata root/#~ + 4 heaps).
- `ReencodeIdempotenceTests` — test-local `ReencodeEncoder`
  (short→long branch promotion, switch tables, InlineVar/Arg = 2-byte).
- `CorpusHelpers` — `corpusRoot = ../../corpus`, `requireCorpus`,
  `readGzipJson` (goldens are gzip streams named *.json).

## What the goldens pin (do not "fix")

- stelem/ldelem named `stelem.any`/`ldelem.any`.
- TypeParser's `Substring(start,length)` ≡ Scala `substring(start,end)`
  (the port bug is now intentional-correct).
- MethodReference.containsGenericParameter = declaring type OR return
  type OR params OR own generic params.
- getEnumUnderlyingType = first NON-static field; module.getType /
  getNestedType implement Cecil's search (implemented in Phase 4).
- ArrayDimension.toString = `lower...upper` with empty sides, no
  spaces, no Some(...).
- readInt64 masks the low word (0xffffffffL) before the high-word OR.
- ByteBuffer(array) ctor sets `length = buffer.length`; the (Int) ctor
  keeps 0 (write mode); readSingle/readDouble little-endian.
- CA row read: the hasCustomAttribute coded-index size is computed per
  image (the RabbitMQ specimen uses 4 bytes; matches Cecil's
  coded_index_sizes).
- The #US heap length: unsigned compressed, flag bit masked, chars =
  (len-1)/2.

## Invariants that are compile-enforced

- `-no-indent`, `-Yexplicit-nulls` (both), zero warnings on clean
  build (json4s Manifest synthesis = the only `-Wconf` exemption),
  `-Wunused:imports`, `-deprecation`, `-unchecked`, `-feature`.
- No null literals anywhere; no `throw` in tests; no `return`.
- Test counts (fast): 165 passed + 1 ignored (Slow-tagged). Slow:
  7 tests.

## References

- Decision records: workspace `docs/adr/adr-0001..0008`.
- Traceability: workspace `TRACEABILITY.md`.
- Operations: `docs/OPERATIONS.md` / `OPERATIONS_llm.md`.
