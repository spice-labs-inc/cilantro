# README (LLM copy)

Machine-oriented facts for working on or with this repository. Human
narrative: README.md.

## Project identity

- Scala 3 (3.7.1) port of Mono.Cecil 0.11.6. Package `io.spicelabs`,
  artifact `cilantro`, version `0.0.1-SNAPSHOT` (overridden by GitHub
  Actions). Build: sbt in `src/cilantro/` (plus a Maven wrapper
  `pom.xml` at the repo root for artifact builds).
- Purpose: read/parse .NET PE assemblies — metadata, model, CIL bodies
  — byte-compatible with the Cecil oracle. NOT a writer (only a
  test-local minimal re-encode encoder exists) and NOT a symbol (PDB)
  reader (explicitly cut, ADR-0008).

## Commands (from `src/cilantro/`)

- Whole suite: `sbt -batch test` — 318 tests, every test, no tag
  exclusions (the fast/slow split was removed 2026-09-04).
- Warning gate: `sbt -batch clean test` → 0 warnings, 0 errors.
- JVM for tests: forked, `-Xmx6g -Xss16m` (set in build.sbt).
- Corpus check: `scripts/ensure_corpus.sh` (from repo root). Maven
  binding: `process-test-resources`; skip with
  `-Dcilantro.skipCorpus=true`.

## Hard facts

- Corpus: 39 packages, 151 assemblies, ~183 MB cache (bin 72 MB,
  golden 65 MB, nupkg 46 MB), manifest schemaVersion 1, sha256-pinned.
  Tags with semantics: `mixedMode` (skip tier2), `corrupt` (load
  failure expected).
- Oracle: `scripts/GoldenDumper` — C# helper using Mono.Cecil 0.11.6,
  `ReadSymbols = false`, NullResolver, never executes assembly code.
  Docker image `cilantro-corpus-fetch:1` (pinned base digest).
- Parity: tier1 + tier2 JSON dumps byte-identical to the goldens for
  all 151 assemblies (`ParityHarnessTests`, full-corpus).
- Caps (ImageReader): sections ≤ 96, section sizes ≤ 512 MB, table
  rows ≤ 10M, named heaps ≤ 256 MB; bodies ≤ 64 MB, switch ≤ 65536,
  type/signature/type-string recursion ≤ 128 (cycles memoized by early
  TypeDef caching; SignatureReader and TypeParser carry their own
  depth guards).
- Style gates: `-no-indent`, `-Yexplicit-nulls`, zero warnings, no
  null literals, no `throw` in tests (enforced by StyleRulesSuite and
  compiler flags). No `return` anywhere.

## File map (main)

- `src/main/scala/cilantro.PE/` — ImageReader (headers, caps),
  ByteBuffer, BinaryStreamReader, Image/Section/DataDirectory.
- `src/main/scala/cilantro.metadata/` — TableHeap, heaps
  (String/Blob/Guid/UserString), CodedIndex, Table, ElementType.
- `src/main/scala/cilantro/` — AssemblyReader (metadata reader,
  model wiring), MetadataSystem, model types
  (TypeDefinition/MethodDefinition/MethodBody/...), TypeParser,
  LogSanitizer, ModuleDefinition/AssemblyDefinition.
- `src/main/scala/cilantro/Cil/` — OpCodes/OpCode/Instruction/Operand,
  CodeReader (decoder), MethodBodyReader (RVA→body), EhReader,
  ExceptionHandler, ParityDumper (the golden-compatible serializer).
- `src/test/scala/cilantro/cil/` — the C2/C3/C4 suites, BodyBuilder,
  EhBuilder, MinimalPeBuilder, ReencodeEncoder (test-local).
- `src/test/scala/cilantro.metadata/` — the C0/C1 suites,
  CorpusHelpers (../../corpus paths), corpus/manifest/golden tests.

## Where decisions live

`/data/workspace/2026_08_26_cilantro/docs/adr/` — ADR-0001 null-free
style, 0002 null-ref constant/resolve, 0003 corpus, 0004 switch
alignment, 0005 EH offset bases, 0006 parity reader semantics, 0007
mixed-mode, 0008 PDB cut. Workspace plan/status/claims docs live
alongside (00–10, phase-N-claims.md).

## What breaks when

- Changing any reader semantic pinned by the goldens (ADR-0006) breaks
  `ParityHarnessTests` (part of the default suite).
- Changing the caps breaks `CapTests`.
- Regenerating goldens requires the deliberate script run
  (OPERATIONS.md); never automatic.
- Adding a warning (unused import, non-local return, deprecation)
  breaks the clean-build gate. json4s Manifest synthesis is the one
  documented `-Wconf` exemption.


Tests shared with Surveyor's integration suite: see `AGENTS.md` (ids, `test-fixtures.json`, provenance).
