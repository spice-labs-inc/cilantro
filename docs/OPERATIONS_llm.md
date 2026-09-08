# Cilantro Operations — LLM copy

This is the machine-readable companion to OPERATIONS.md. Same facts,
tuned for retrieval. Claims carry their pinning test in parentheses.

## Run model

- Suite: `cd src/cilantro && sbt -batch test` — runs every test; no
  tag exclusions (fast/slow split removed 2026-09-04).
- Prerequisites: JVM always; docker only when `corpus/golden/bin` is
  absent (golden regeneration, ADR-0009); network only when the cache
  is cold.
- Corpus root resolution: `../../corpus` relative to CWD — sbt MUST run
  from `src/cilantro` (CorpusHelpers.scala).

## Corpus layout (ADR-0009)

Committed (never written by tooling; verified by CorpusPinTests C9-01 /
C9-04):
- `corpus/fixtures/*.dll` — 4 hand-authored fixture DLLs.
- `corpus/golden/fixtures/*` — oracle dumps (tier1/tier2 = gzip JSON,
  resources/debug = plain JSON).
- `corpus/golden-index.json` — path → sha256 of the committed goldens.
- `corpus/manifest.json` (schemaVersion 1; pins nupkg + bin sha256s,
  incl. the deterministic corrupt fixtures) and `corpus/packages.json`
  (seed list + corruptFixtures recipe).

Cache (gitignored, provisioned on demand):
- `corpus/nupkg/`, `corpus/bin/`, `corpus/golden/bin/`.

## Provisioning gate (CorpusProvisioner, ADR-0009)

`ensureCorpus()` (throws on failure with an actionable message) /
`ensureCorpusWith(cfg)` (Either; test-only seams).

- Fast path (no docker, no network; memoized per JVM incl. failures):
  manifest parses (schemaVersion 1); every manifest assembly path is a
  non-symlink regular file with matching sha256 (P1-01, P1-03, P1-04);
  every golden-index entry matches (P1-15); `golden/bin` exists.
- Completeness definition: bin (per manifest) + golden-index
  (committed) + golden/bin (existence). nupkg is fetch-input only — a
  missing/tampered nupkg alone never triggers anything (P1-24).
- Slow path: cross-process FileLock at
  `$XDG_CACHE_HOME/cilantro/corpus-<sha256-of-root>.lock` (outside the
  repo); re-check fast path; JVM fetch (CorpusFetcher: download,
  nupkg sha256, entry-safe extract, corrupt fixtures); golden
  regeneration via docker seam when `golden/bin` absent; post-verify =
  fast path re-run + committed-snapshot byte-compare ("committed ground
  truth modified" on any change — P1-16); retry-once (P1-08); 30-min
  wall-clock timeout (P1-17); failure memoized (P1-10/P1-11).
- Error message contract (R5): step label + root cause + corpus root +
  manual remedy `scripts/ensure_corpus.sh` (P1-08).
- Symlink policy: gate rejects symlinked cache paths and refuses to
  follow them (P1-15a); population never creates symlinks (P1-15b).
- Path safety: manifest paths with `..` / absolute / backslash are
  rejected (P1-20); malformed manifest / wrong schemaVersion fail with
  a clear message (P1-21); empty packages list = complete (P1-22);
  absent corpus root = clear manifest error, no fetch (P1-18).

## CorpusFetcher (pure JVM; no docker, no .NET)

- Inputs: committed manifest + packages.json. Downloads pinned nupkgs
  (skips when the cached sha matches — P1-24), verifies sha, extracts
  with CorpusExtractor, generates deterministic corrupt fixtures
  (truncate + last-byte XOR 0x5A — P1-25, recipe verified against the
  manifest pins).
- Downloader seam; production downloader = java.net.http with bounded
  timeouts and atomic temp+rename (CorpusFetcher.scala).

## CorpusExtractor (entry-safe port of scripts/GoldenDumper/Extraction.cs)

- Keeps only `lib/<tfm>/<file>.dll` (P1-27): satellite culture dirs,
  native helper DLLs (SQLite.Interop.dll etc.), non-DLL entries,
  top-level ref/runtimes skipped benignly.
- Rejects (Left(reason)): backslash paths, absolute/drive paths, `..`
  segments, Unix symlink/device/fifo mode entries (via a minimal zip
  central-directory reader for external attributes — java.util.zip
  does not expose them), oversized entries (512 MB per entry / 2 GB
  total), pre-existing symlinks in the destination chain (P1-26).
- Never creates symlinks; never throws (C0-04).

## Docker footprint (the only docker in the pipeline)

- Golden regeneration: `defaultGoldenRegen` runs the pinned image
  (cilantro-corpus-fetch:1) with repo mounted READ-ONLY, only
  `corpus/golden/bin` writable, `--user $(id -u):$(id -g)`, no
  `--network host`; uses the baked `/opt/goldendumper/GoldenDumper`
  (fallback: copy+build in the container); dump with
  `--fixtures-dir /tmp/no-fixtures` so the committed
  `corpus/golden/fixtures` is never touched. Regenerated goldens are
  byte-identical to a warm cache (verified during Phase 3).
- Pre-existing docker tests (unchanged): GoldenDeterminismTests
  (C1-07 determinism + C1-07b committed-oracle reproducibility),
  CorpusPropertyTests (probe oracle), ExtractionSafetyTests (C# 
  extractor vectors).

## CI (build-and-test-scala.yml)

- Pre-populate step before `sbt test`:
  `sbt "test:runMain io.spicelabs.cilantro.metadata.CorpusProvisioner"`.
- On pull requests the step runs from a second checkout pinned to
  `pull_request.base.sha` (trusted refs); the populated cache
  (nupkg/, bin/, golden/bin) is then copied into the PR checkout, so
  the test JVM fast-paths and PR-authored fetch inputs never run with
  docker.
- Remediated 2026-08-31: GoldenDeterminismTests C1-07 previously
  passed trivially — its ABSOLUTE submanifest paths made .NET
  `Path.Combine` write the tier dumps next to the assemblies (into
  corpus/bin) while the test compared empty output dirs. Now the
  submanifest lives in /work/corpus with RELATIVE paths (resolving
  against the manifest's directory), fixtures dumping is excluded
  (`--fixtures-dir /tmp/no-fixtures`), a file-count guard (exactly 9
  goldens for the 5-assembly submanifest) makes a trivial pass
  impossible, and a bash EXIT trap removes the submanifest afterward.
  Verified: 9 files dumped under `--out`, byte-identical across the
  two runs, zero strays left in corpus/bin.
