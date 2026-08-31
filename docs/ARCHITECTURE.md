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
`TRACEABILITY.md`. Fast vs Slow: anything that needs the corpus is
`Slow`-tagged; the fast suite is the default gate. The provisioning
tests (P1-xx) run in the default suite with synthetic corpora — no
docker, no network.

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
