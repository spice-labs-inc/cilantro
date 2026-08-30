# Cilantro Operations

Running cilantro's tests and corpus tooling, and what to do when
something breaks. Companion LLM copy: OPERATIONS_llm.md.

## Getting started

Prerequisites: a JVM and Docker (Docker is only needed for the corpus
and the golden oracle; the fast suite runs without either).

```
cd src/cilantro
sbt -batch test
```

First run on a machine without the corpus cache: the Maven path
(`mvn test`) or the test helpers will fail with a clear message pointing
at `scripts/ensure_corpus.sh`. Run it once (it downloads ~46 MB of
pinned nupkgs and builds the fetch image on first use; warm runs verify
in seconds with no network):

```
scripts/ensure_corpus.sh
```

## The suites

| Suite | Command | What it proves |
|---|---|---|
| Fast (default) | `sbt -batch test` | 165 tests: style gates, model contract, decoder, EH, bodies, caps, re-encode, sanitizer, fixture parity |
| Slow parity | `sbt -batch 'set Test / testOptions := Seq.empty' "testOnly io.spicelabs.cilantro.cil.ParityHarnessTests"'` | every corpus assembly's tier1+tier2 dumps are byte-identical to the goldens |
| Slow properties | `... "testOnly io.spicelabs.cilantro.cil.CorpusPropertyTests"` | parse-all accounting, token round-trip, mutation fuzz (incl. oracle verdict comparison), cyclic-token resolution |
| Clean gate | `sbt -batch clean test` | 0 warnings, 0 errors — a project gate |

The `Slow` tag is excluded from the default run by `build.sbt`
(`--exclude-tags=Slow`); the `set Test / testOptions := Seq.empty`
incantation is required because the exclusion also applies to
`testOnly`.

## Corpus operations

Everything lives under `corpus/` (gitignored except
`corpus/manifest.json` and `corpus/packages.json`):

- `corpus/nupkg/` — pinned package archives (sha256-pinned).
- `corpus/bin/` — the extracted assemblies (151 files, ~72 MB).
- `corpus/golden/` — the pinned golden dumps (gzip streams named
  `*.tier1.json` / `*.tier2.json`, ~65 MB).

### Checking the cache

```
scripts/ensure_corpus.sh
```

Verifies every file against the manifest; re-fetches anything missing
or wrong. It refuses to re-record a changed package, so a tampered
cache can never re-bless itself (`CorpusManifestTests.C1-01`, the
GoldenDumper `verify` command).

### Fetching from scratch

`scripts/fetch_corpus.sh` rebuilds the pinned Docker image
(`cilantro-corpus-fetch:1`, base digest pinned, never `:latest`) and
runs the pinned `GoldenDumper fetch` + `dump` inside it, preserving
the invoking user's uid/gid on all mounts.

### Regenerating goldens (deliberate, versioned)

The goldens are the oracle. Regenerating them is a decision, not a
command: it overwrites the pinned tier1/tier2 files, and it must only
happen after a conscious review of what changed. (The manifest itself
is only re-recorded by the pinned `fetch` — which refuses to re-record
a changed package — never by `dump`; a full pipeline re-run is
`scripts/fetch_corpus.sh`.)

```
docker run --rm --user "$(id -u):$(id -g)" \
  -v "$PWD:/work" -w /work \
  cilantro-corpus-fetch:1 \
  bash -lc 'dotnet scripts/GoldenDumper/bin/Release/net8.0/GoldenDumper.dll dump \
              --manifest corpus/manifest.json --out corpus/golden'
```

(If the helper binary is missing, build it first inside the image:
`dotnet build scripts/GoldenDumper/GoldenDumper.csproj -c Release`.)

The helper never loads or invokes assembly code (`GoldenHelperBannedApiScan`
pins this), reads symbols off (`ReadSymbols = false`), uses a
NullResolver, and skips tier2 for `mixedMode` packages (ADR-0007).

## Interpreting parity failures

A `tier1 diff for ...` / `tier2 diff for ...` failure names the
assembly. The test prints the munit diff (expected/golden vs obtained).
Workflow:

1. Find the first divergent byte (the debug pattern used all through
   Phase 4: dump both strings and walk to the first mismatch).
2. Classify: serialization shape (escaping, number formatting, enum
   names), model wiring (a wrong full name, a wrong attribute), or
   reader semantics (a token resolved under the wrong context — see
   ADR-0006).
3. Fix against the golden, never "correct" the golden. If a golden is
   actually wrong (it should not be — it is the pinned Cecil output),
   that is a golden-regeneration decision, not a code change.

## Troubleshooting

- **"corpus missing at ../../corpus"** — run `scripts/ensure_corpus.sh`
  (or `mvn -Dcilantro.skipCorpus=true` only if you are not running the
  corpus-backed tests).
- **Slow tests "Ignored"** — the build.sbt exclusion applies to
  `testOnly` too; use `set Test / testOptions := Seq.empty` first.
- **Timeouts** — the corpus suites raise munit's timeout to 120 min;
  if a run times out, the corpus is being re-read cold (or Docker is
  slow), not necessarily a bug.
- **OutOfMemoryError** — the test JVM is forked with `-Xmx6g -Xss16m`;
  if you changed that, restore it (the deep reader recursion needs the
  stack).
- **Warnings in a clean build** — the gate is zero; the one documented
  exemption is json4s Manifest synthesis (build.sbt). Everything else
  must be fixed.
- **`DataFormatException` on a corpus file** — check the `corrupt` tag
  in the manifest first; those are intentionally broken and expected to
  fail cleanly.
- **Verdict mismatches in the fuzz comparison** — the helper probe runs
  in Docker; make sure the image exists (`docker image inspect
  cilantro-corpus-fetch:1`) and that `scripts/GoldenDumper` was rebuilt
  after any helper change.
