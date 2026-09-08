# Cilantro

A Scala 3 port of [Mono.Cecil](https://github.com/jbevain/cecil) for
reading and inspecting .NET assemblies (ECMA-335 PE files): metadata,
the type/member model, and CIL method bodies with exception handlers.

Cilantro is null-free (Option/Try only, enforced by `-Yexplicit-nulls`),
test-driven (every feature lands with a named test, red to green), and
parity-proven: its dumps of 151 real-world NuGet assemblies are
byte-identical to the dumps of the pinned Mono.Cecil 0.11.6 oracle.

## Building and testing

Requires the JVM (no other local tooling — the corpus and the golden
oracle run in Docker).

```
cd src/cilantro
sbt -batch test          # the whole suite: 318 tests, no exclusions
```

`sbt test` runs EVERY test — no fast/slow split, no tag exclusions
(user decision 2026-09-04): model accessors, the opcode table, the
CIL decoder, exception handlers, body reading, the security caps,
the re-encode round trip, the log sanitizer, and the full-corpus
parity + corpus-property runs all execute in the default gate.

A clean build is warning-free (`sbt -batch clean test` must print zero
warnings — this is a project gate).

### The test corpus

The suite runs against a pinned NuGet corpus (39 packages, 151
assemblies, schema/manifest with sha256 pinning). It is gitignored and
fetched/verified by `scripts/ensure_corpus.sh` (Docker, no network on a
warm cache). Everything about operating the corpus — fetching,
verifying, regenerating goldens, troubleshooting — is in
[docs/OPERATIONS.md](docs/OPERATIONS.md).

Maven builds bind the same check (`mvn` runs `ensure_corpus.sh` at
`process-test-resources`; artifact-only builds skip it with
`-Dcilantro.skipCorpus=true`).

## Reading the code

Start with [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the reader
pipeline (PE → metadata → model → CIL bodies), the parity harness, the
security caps and the style rules. Decision records live in the
workspace `docs/adr/` (linked from the architecture doc).

## Documentation layout

- `README.md` / `README_llm.md` — this file, for humans / for LLMs.
- `docs/ARCHITECTURE.md` / `docs/ARCHITECTURE_llm.md` — how the pieces
  fit, the invariants, the pinned semantics.
- `docs/OPERATIONS.md` / `docs/OPERATIONS_llm.md` — getting started,
  corpus operations, golden regeneration, troubleshooting, and how to
  interpret parity results.

## License

MIT, same as Cecil (see LICENSE). This is a port; the original is
copyright Jb Evain / Novell, this work Spice Labs.
