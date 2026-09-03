# Cilantro Operations

Running cilantro's tests and corpus tooling, and what to do when
something breaks. Companion LLM copy: OPERATIONS_llm.md.

## Getting started

Prerequisites: a JVM (Docker is needed only when the corpus cache is
cold AND the golden dumps must be regenerated; a machine with a warm
cache runs the suite with no docker and no network).

```
cd src/cilantro
sbt -batch test
```

The corpus cache (corpus/nupkg, corpus/bin, corpus/golden/bin) is
populated ON DEMAND by the test-side provisioner
(`CorpusProvisioner.ensureCorpus()`, ADR-0009): the first corpus test
on a cold machine downloads the pinned nupkgs (~46 MB), extracts the
assemblies entry-safely, and regenerates the golden dumps via the
pinned dumper in docker (first use also builds the fetch image).
Tests wait for population; only a failed population fails the run, with
an actionable message. No manual step is required.

The committed ground truth — `corpus/fixtures/`, `corpus/golden/fixtures/`,
`corpus/golden-index.json`, `corpus/manifest.json`,
`corpus/packages.json` — comes from the checkout; it is never written
by the provisioner (ADR-0009).

## The suites

| Suite | Command | What it proves |
|---|---|---|
| Default | `sbt -batch test` | 291 tests: style gates, corpus pins, provisioning gate, model contract, decoder, EH, bodies, caps, re-encode, sanitizer, slice views, walk, names, PDB spool, probe, fixture + corpus parity |
| Slow parity | `sbt -batch 'set Test / testOptions := Seq.empty' "testOnly io.spicelabs.cilantro.cil.ParityHarnessTests"'` | every corpus assembly's tier1+tier2 dumps are byte-identical to the goldens |
| Slow properties | `... "testOnly io.spicelabs.cilantro.cil.CorpusPropertyTests"` | parse-all accounting, token round-trip, mutation fuzz (incl. oracle verdict comparison), cyclic-token resolution |
| Clean gate | `sbt -batch clean test` | 0 warnings, 0 errors — a project gate |

The `Slow` tag is excluded from the default run by `build.sbt`
(`--exclude-tags=Slow`); the `set Test / testOptions := Seq.empty`
incantation is required because the exclusion also applies to
`testOnly`.

## Corpus operations

Everything lives under `corpus/`. Committed ground truth (never
written by tooling): `fixtures/`, `golden/fixtures/`,
`golden-index.json`, `manifest.json`, `packages.json`. Regenerable
cache (gitignored, provisioned on demand): `nupkg/`, `bin/`,
`golden/bin`.

### Provisioning / verifying the cache

```
scripts/ensure_corpus.sh
```

Runs the same gate the tests use: JVM fetch (download + sha256 verify +
entry-safe extract + corrupt fixtures), docker golden regeneration when
`corpus/golden/bin` is absent, retry-once, then a clear error with the
manual remedy if anything fails. Equivalent: `cd src/cilantro && sbt
"test:runMain io.spicelabs.cilantro.metadata.CorpusProvisioner"`.

### Docker-only cache fetch (no sbt)

`scripts/fetch_corpus.sh` rebuilds the pinned image (base digest
pinned, GoldenDumper baked — no build/restore/network at runtime) and
runs the pinned `GoldenDumper fetch` inside it: nupkg download,
manifest sha256 discipline (it refuses to rewrite a changed
`manifest.json`), entry-safe extraction, deterministic corrupt
fixtures. It never writes goldens or fixtures (committed ground truth).

### Regenerating goldens (deliberate, versioned)

The goldens are the oracle. `corpus/golden/fixtures` is committed;
`corpus/golden/bin` is regenerated on demand. A full regeneration
(after a Cecil/dumper bump) is a deliberate workflow — dump with the
pinned image into a scratch dir, review, then move into place and
re-index:

```
docker run --rm --user "$(id -u):$(id -g)" \
  -v "$PWD:/work:ro" -w /work \
  cilantro-corpus-fetch:1 \
  bash -lc '/opt/goldendumper/GoldenDumper dump \
              --manifest /work/corpus/manifest.json --out /tmp/golden-new'
# review /tmp/golden-new, then:
#   rm -rf corpus/golden/bin && cp -r <scratch>/bin corpus/golden/bin
# regenerate corpus/golden-index.json (sha256 of the committed
# corpus/golden/fixtures files; see the index schema)
```

The dumper never loads or invokes assembly code
(`GoldenHelperBannedApiScan` pins this), reads symbols off
(`ReadSymbols = false`), uses a NullResolver, and skips tier2 for
`mixedMode` packages (ADR-0007). Fixture regeneration (after editing
`scripts/fixtures/*.il`) uses `GoldenDumper ilasm-compile` in the
image and re-commits the fixture DLLs + their goldens together.

## Interpreting parity failures

A `tier1 diff for ...` / `tier2 diff for ...` failure names the
assembly. The test prints the munit diff (expected/golden vs obtained).
Workflow:

1. Find the first divergent byte.
2. Classify: serialization shape (escaping, number formatting, enum
   names), model wiring (a wrong full name, a wrong attribute), or
   reader semantics (a token resolved under the wrong context — see
   ADR-0006).
3. Fix against the golden, never "correct" the golden. If a golden is
   actually wrong (it should not be — it is the pinned Cecil output),
   that is a golden-regeneration decision (above), not a code change.

## Troubleshooting

- **"corpus manifest not found at .../manifest.json"** — sbt must run
  from `src/cilantro` (the corpus root resolves as `../../corpus`).
  The manifest is COMMITTED; a missing one means the checkout is
  broken or the working directory is wrong — the provisioner says so
  explicitly and does not try to fetch.
- **"corpus population failed (…): … Manual remedy:
  scripts/ensure_corpus.sh"** — the fetch/regeneration failed; the
  message names the step (download / sha mismatch / extraction / golden
  regen / timeout / lock). Docker is required only for the golden
  step; if docker is unavailable and `corpus/golden/bin` is missing,
  copy `corpus/golden/bin` from a machine that has it.
- **"committed ground truth modified"** — a fetch attempted to write
  the committed trees; report it, do not clear it.
- **Slow tests "Ignored"** — the build.sbt exclusion applies to
  `testOnly` too; use `set Test / testOptions := Seq.empty` first.
- **Timeouts** — corpus suites raise munit's timeout to 120 min (a
  cold-cache population can legitimately take minutes); a timeout on a
  warm cache points at a hung fetch — check `corpus/.populating.lock`
  (actually `$XDG_CACHE_HOME/cilantro/corpus-*.lock`).
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
  cilantro-corpus-fetch:1`) and was rebuilt after a helper change
  (`docker build -f scripts/fetch_image/Dockerfile -t
  cilantro-corpus-fetch:1 scripts`).
