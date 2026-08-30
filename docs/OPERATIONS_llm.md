# Operations (LLM copy)

Machine-oriented operations facts. Human narrative: OPERATIONS.md.

## Commands (verbatim)

```
# repo root
scripts/ensure_corpus.sh                       # verify/fix the cache (docker)
scripts/fetch_corpus.sh                        # cold fetch + golden regen

# src/cilantro
sbt -batch test                                 # fast gate (165)
sbt -batch clean test                           # + zero warnings gate
sbt -batch 'set Test / testOptions := Seq.empty' \
  "testOnly io.spicelabs.cilantro.cil.ParityHarnessTests"            # Slow parity (2)
sbt -batch 'set Test / testOptions := Seq.empty' \
  "testOnly io.spicelabs.cilantro.cil.CorpusPropertyTests"           # Slow properties (5)
mvn -Dcilantro.skipCorpus=true package          # artifact build, no corpus check
```

## Files

- `corpus/manifest.json` (committed): schemaVersion 1, packages[]
  with id/version/tags/assemblies[path]/nupkgSha256/assembly sha256s.
- `corpus/packages.json` (committed): the seed the fetcher works from.
- `corpus/{nupkg,bin,golden}` (gitignored): cache, sha256-pinned.
- `scripts/GoldenDumper/` — the C# oracle: commands
  `fetch | dump | extract | verify | ilasm-compile | make-hostile |
  make-benign | probe`.
- `scripts/fetch_image/` — the Dockerfile for
  `cilantro-corpus-fetch:1` (pinned base digest
  `sha256:bb32ba3ba3ea...`).

## Golden regeneration procedure

1. Review what changed (diff the code paths the dumper covers).
2. Rebuild the helper in the image, run `dump` as the invoking user
   (see OPERATIONS.md for the exact docker invocation). `dump` writes
   goldens only; it never touches the manifest, and it skips `corrupt`
   packages entirely (no goldens by design) and skips tier2 for
   `mixedMode` packages.
3. Commit the new goldens. The manifest changes only via the pinned
   `fetch` (which refuses to re-record a changed package). Never
   regenerate implicitly.

## Interpreting parity failure output

- `tier1 diff for <rel>` — the model dump diverges (names, attributes,
  constants, custom attributes).
- `tier2 diff for <rel>` — the body dump diverges (opcode names,
  operand formatting, resolved tokens).
- munit prints expected (golden) vs obtained (ours). The canonical
  debug: dump both strings, walk to the first mismatching byte, read
  the ±150-byte context, classify (escape/number formatting vs model
  wiring vs context-dependent resolution — ADR-0006), fix ours against
  the golden.

## Known environmental dependencies

- Docker for the corpus + oracle (image `cilantro-corpus-fetch:1`).
- The helper binary path inside the image:
  `scripts/GoldenDumper/bin/Release/net8.0/GoldenDumper.dll`.
- The C4-07 helper comparison mounts the temp mutation dir at
  `/work/probe` and `scripts/GoldenDumper` at `/work/gd`; it runs
  `probe --file` per mutation and compares ok/fail verdicts with ours.
- All docker mounts preserve the invoking uid/gid
  (`--user "$(id -u):$(id -g)"` — house rule 13).
