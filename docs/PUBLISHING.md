# PUBLISHING — Releasing cilantro to Maven Central

The Phase 6 plan's integration constraint: Goat Rodeo integrates once
cilantro is published to Maven Central. This documents the release
steps and the frozen contract.

## Prerequisites

- The zero-warnings clean build: `sbt -batch clean test` (0 warnings,
  0 errors) and the Slow suites green.
- The corpus cache verified: `scripts/ensure_corpus.sh`.
- GPG keys configured (the pom already binds the maven-gpg-plugin).

## Release steps

1. Bump the version in `build.sbt` from `0.0.1-SNAPSHOT` to the release
   version (e.g. `0.1.0`); the GitHub Actions override is the CI path.
2. `sbt publishSigned` (or the project's CI release workflow) stages
   the artifacts.
3. Close and release the staging repository on OSSRH.
4. Verify the published artifact: a clean consumer build depending on
   `io.spicelabs:cilantro:0.1.0` compiles the frozen API (§ below).

## The frozen API (0.1.0)

The traversal surface GR will consume (pinned by `ContractTests.C5-06a`
in this repo):

```
io.spicelabs.cilantro.dump.CanonicalJson.typeToJson: TypeDefinition => Try[String]
io.spicelabs.cilantro.ModuleDefinition.resources: ArrayBuffer[Resource]
io.spicelabs.cilantro.EmbeddedResource.resourceData(): Try[Array[Byte]]
io.spicelabs.cilantro.PE.Image.securityDirectory: Option[DataDirectory]
io.spicelabs.cilantro.MetadataReader.readCertificateEntries(): ArrayBuffer[CertificateEntry]
io.spicelabs.cilantro.MetadataReader.readWin32Resources(): ArrayBuffer[Win32Resource]
io.spicelabs.cilantro.MetadataReader.readDebugEntryData(): ArrayBuffer[DebugEntryData]
io.spicelabs.cilantro.MetadataReader.readEmbeddedPortablePdb(): Option[EmbeddedPdb]
```

The whole surface is Option/Try, no null — the same contract as the
existing GR-facing accessors (ADR-0001).

## After the publication

The GR integration follows `docs/GR_INTEGRATION.md` (the walker, the
MIME matrix, the edges) — a separate GR-side plan.
