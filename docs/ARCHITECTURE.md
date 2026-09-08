# Cilantro Architecture

How cilantro reads a .NET assembly and why the pieces look the way they
do. Companion LLM copy: ARCHITECTURE_llm.md. Decisions behind the
semantics: the workspace `docs/adr/` records (linked inline).

## The pipeline

```
PE file
  └─ ImageReader (cilantro.PE)            header walk + resource caps
       └─ Image/Section/DataDirectory     RVA → file-offset mapping
            └─ TableHeap + heaps          #~, #Strings, #Blob, #GUID, #US
                 └─ AssemblyReader        metadata tables → the model
                      ├─ TypeParser       CA blob type strings → refs
                      ├─ MethodBodyReader RVA → method body
                      │    ├─ EhReader    small/fat exception sections
                      │    └─ CodeReader  instruction decode
                      └─ ParityDumper     golden-compatible JSON (tests)
```

1. **ImageReader** parses DOS/PE/COFF/optional headers, the CLI header
   and the metadata root. This is also where the hostile-input caps
   live: more than 96 sections, a section size claim above 512 MB, a
   table row count above 10M or a named heap above 256 MB fails with a
   clean `DataFormatException` before anything is allocated or read
   (`CapTests` pins each limit and limit+1).
2. **Image** maps RVAs to raw file offsets via the section table;
   every later read (metadata tables, method bodies) goes through this
   mapping (`RvaMappingTests`).
3. **The heaps** (`TableHeap`, `StringHeap`, `BlobHeap`, `GuidHeap`,
   `UserStringHeap`) wrap the metadata streams. The blob index *is* the
   heap offset, which is why the signature reader positions directly
   on the blob heap.
4. **AssemblyReader** is the port of Cecil's `AssemblyReader` +
   `MetadataSystem`: table walks (TypeDef, Field, Method, Param,
   PropertyMap, EventMap, NestedClass, InterfaceImpl, GenericParam,
   GenericParamConstraint, MemberRef, TypeSpec, MethodSpec,
   CustomAttribute, Constant, ...), the lazy model wiring
   (`initializeTypeDefinitions`, ranges per type), and the token
   lookup with the context handling that makes generic instantiations
   resolve to the right parameter names.
5. **TypeParser** turns the assembly-qualified type strings inside
   custom-attribute blobs back into `TypeReference`s (nested `+`/`/`
   handling, generic arity, specs, assembly-name suffix).
6. **MethodBodyReader** maps a method RVA to its body bytes (64 MB
   cap), parses the tiny/fat header, hands the code stream to
   `CodeReader`, walks the EH sections via `EhReader`, and resolves
   locals through the StandAloneSig.
7. **CodeReader** decodes the instruction stream with boundary checks
   on every operand; branch and switch targets are validated to land on
   instruction starts; a switch over 65536 targets is rejected.
8. **ParityDumper** (test path only) serializes tier1 (the model) and
   tier2 (instructions with raw operand bytes) in the exact shape of
   the golden helper, including System.Text.Json's escape set and C#'s
   shortest round-trip float formatting.

## The model

The model mirrors Cecil's shape: `TypeDefinition`/`MethodDefinition`/
`FieldDefinition`/`PropertyDefinition`/`EventDefinition`,
`MethodBody`/`Instruction`/`ExceptionHandler`, generic
parameters/constraints, custom attributes, constants. Everything that
can be absent is `Option`; every entry point that can fail is `Try`
(ADR-0001, ADR-0002). Definitions are lazily loaded: reading the type
list does not read the bodies; `MethodDefinition.readBody` is explicit.

## Pinned reader semantics (ADR-0006)

The corpus loop forced these exact behaviors; each is golden-pinned by
`ParityHarnessTests`:

- Member references containing generic parameters are not cached; they
  re-resolve per use context.
- Custom-attribute enum arguments resolve only inside the loading
  module (Cecil + the golden helper's NullResolver); an unresolvable
  external enum clears the attribute's arguments.
- Type definitions are registered in the metadata cache *before* their
  base type is read, so cyclic base chains resolve once; a depth cap of
  128 aborts hostile deep chains; the signature reader and the type
  parser carry the same 128 bound for nested type signatures and
  generic type strings (`CapTests` pins all three).
- Constants follow the blob's own element type (u4/u8 unsigned, etc.).
- The JSON escape set and float formatting match the oracle, not
  "correct" JSON.

## Security posture

- All caps are pre-checks, tested at limit and limit+1 (`CapTests`).
- The corpus cache is sha256-pinned; the fetch refuses to re-record a
  changed package (ADR-0003, ADR-0009).
- The golden helper never loads or invokes assembly code
  (`GoldenHelperBannedApiScan`); extraction is entry-safe
  (`ExtractionSafetyTests`, P1-26).
- Attacker-controlled names never reach log output raw
  (`LogSanitizer`, `LogSanitizationTests`).
- The provisioning gate rejects symlinked cache paths, refuses to
  follow them, and never writes the committed trees (P1-15, P1-16,
  ADR-0009).

## Corpus provisioning (ADR-0009)

Committed ground truth (`corpus/fixtures/`, `corpus/golden/fixtures/`,
`corpus/golden-index.json`, `manifest.json`, `packages.json` — ~60KB)
vs regenerable cache (`corpus/nupkg/`, `corpus/bin/`,
`corpus/golden/bin/`). `CorpusProvisioner.ensureCorpus()` is the single
gate every corpus test passes through:

```
ensureCorpus()
  ├─ fast path (no docker, no network; memoized)
  │    manifest sha256s + golden-index pins + golden/bin presence
  └─ slow path (cross-process lock outside the repo)
       ├─ JVM fetch: pinned nupkgs → entry-safe extract → corrupt
       │  fixtures (CorpusFetcher + CorpusExtractor)
       ├─ docker regen of golden/bin when absent (pinned dumper,
       │  read-only repo mount, --user uid:gid)
       └─ post-verify: fast path re-run + committed snapshot compare
```

A full-cache machine never touches docker or the network for the gate
(P1-01); a cold cache is populated in-test and tests wait (P1-12,
P1-13); a failed population retries once, then fails with an
actionable message (P1-08). The completeness definition, the lock
protocol and the docker footprint are documented in OPERATIONS.md.

## Testing strategy

Red → green everywhere. Suites by phase: C0 style gates, C1 corpus and
goldens, C2 decoder, C3 EH and integration, C4 parity/caps/properties,
P1 provisioning-gate unit tests. The full traceability table (every
Cx-xx test and the claim it pins) lives in the workspace
`TRACEABILITY.md`. There is no fast/slow split (removed 2026-09-04):
`sbt test` runs every test, including the full-corpus parity and
corpus-property suites. The provisioning tests (P1-xx) run in the
default suite with synthetic corpora — no docker, no network.

## Style

Brace format (`-no-indent`), Option/Try only (`-Yexplicit-nulls`), no
`return`, zero warnings on a clean build, tests never `throw`
(ADR-0001, StyleRulesSuite).

## Decisions

See `docs/adr/` (plus the workspace `docs/adr/` records):
- ADR-0001 null-free style, ADR-0002 null-ref constant and resolve
- ADR-0003 NuGet corpus, ADR-0004 CIL switch alignment
- ADR-0005 EH offset bases, ADR-0006 parity reader semantics
- ADR-0007 mixed-mode packages, ADR-0008 PDB cut
- ADR-0009 corpus ground truth committed; cache provisioned on demand

## Streaming payload exposure (plan 2026_09_02, ADR-0014)

The handoff makes .NET assemblies behave like every other container:
probe, walk, wrap. Three mechanisms back it (claims → tests):

1. **Probe** — `DotnetAssemblyProbe` classifies from PE headers + CLI
   header + BSJB magic within one bounded region read, never parses
   heaps, never throws (`DotnetProbeTests` D1-01..D1-14; ADR-0012).
2. **Slice payloads** — the whole-blob payload accessors were
   replaced by streaming views (D-3): `CertificateEntry`,
   `Win32Resource`, `DebugEntryData`, and managed-resource payloads
   are `PayloadSource`s over bounded, zero-copy `ByteBuffer` slices of
   the memory-mapped file. A declared length past the file's extent
   refuses with `DataFormatException` before any allocation; there
   are **no byte-budget constants** on payload exposure (D-10) — only
   object guards (certificate/debug/win32-per-directory/source
   counts at 1,000,000, win32 total leaves 1,000,000, class
   canonical JSON ≤ 1 MiB). Claim: "payload slices never materialize
   whole payloads on the heap — a 272 MiB leaf streams under a 32 MiB
   heap" → `SliceViewTests.CP-3d`; "refusals precede allocation" →
   `SliceViewTests.CP-3b/CP-3c`. The debug-directory header walk
   validates declarations structurally and never copies payload data
   (D-9): in-file blobs of any size are legal, past-EOF declarations
   refuse at open (`DebugHeaderCapTests` H6-01..H6-05,
   `DebugEntryTests` C5-05c).
3. **The walk** — byte-faithful (2026_09_04 amendment B-1..B-11):
   `AssemblyWalker.withinAssemblyStream(artifact)(f)` hands the
   consumer one complete `Vector[AssemblyEntry]` or `None`
   (all-or-nothing, D-1/D-2) and takes NO spool directory. Every
   entry carries `name`, `kind`, `mimeHint`, and `length` — the exact
   byte count of the payload it delivers (in-file for file-backed
   payloads; the canonical-JSON byte count is the single documented
   exception for Class). The walk contains ZERO payload logic: no
   decompression, no envelope checks, no magic sniffing (B-9) — a
   hostile PDB never affects the walk (B-10). Claims: "entries only
   deliver raw bytes; the type-17 payload is one raw-MPDB DebugBlob
   with length == its in-file size" → `AssemblyWalkerTests` CP-2a;
   "length == stream bytes for every file-backed kind" → CP-2a
   byte-faithfulness; "the vector is either populated or not; f runs
   exactly once" → CP-2c/CP-2e; "cycle-safe class enumeration"
   → CP-2c/CP-2d; "seeded flips/truncations over the debug directory
   and type-17 payload never throw" → CP-2d; "names hardened at the
   source" → `NameHardeningTests` CP-6a..d; "after the walk,
   `length` and `processStream` refuse for every kind while
   name/kind/hint stay live" → CP-2e refusal matrix. The canonical
   per-class bytes remain the golden `"cilantro-type"` v1 JSON
   (unchanged; `CanonicalJsonTests`).
4. **Embedded portable PDBs** — the MPDB root is decompressed into a
   scratch file the CALLER's spool directory provides (D-6), mapped,
   and its tables walked from the map with Long extent arithmetic;
   declared sizes ≥ 2^31 refuse before any write (D-12); embedded
   sources stream lazily inside `processStream` (D-13). Claim: "the
   root spools into the caller's directory and cilantro deletes only
   files it created" → `PdbSpoolTests` CP-5a; "declared mismatch
   refuses cleanly and output never exceeds declared" → CP-5d;
   "a partial read performs only partial work" → CP-5f.

The payload model lives in `PayloadSource.scala` (+ the internal
`PayloadBytes` exact-length seam — deliberately NOT on the trait),
`cilantro.PE/MappedSliceSource.scala` (+ `BufferSliceInputStream`),
`cilantro.PE/RawInflate.scala` (push `pump` + pull
`RawInflateInputStream`), `AssemblyEntry.scala` (length + the
DebugBlob type-17 hint override), `AssemblyWalker.scala`,
`DotnetNameSanitizer.scala`, `PortablePdbSpool.scala` (PDB
root/tables parser), `PortablePdbFile.scala` (withPdb) and
`DebugEntryData.scala` (PDBView/EmbeddedSourceFile).
