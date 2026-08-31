#!/usr/bin/env bash
# fetch_corpus.sh — download, verify and entry-safe extract the corpus
# cache (corpus/nupkg, corpus/bin) inside the pinned fetch image.
#
# ADR-0006 ground-truth discipline:
#   - corpus/golden and corpus/fixtures are COMMITTED and are never
#     written by this script (the GoldenDumper fetch refuses to rewrite
#     manifest.json and no longer compiles fixtures or writes goldens).
#   - Golden regeneration into corpus/golden/bin happens on demand from
#     the test-side provisioner (CorpusProvisioner), not here.
#
# Every step fails the script on error (`set -euo pipefail`):
#   1. Download each pinned .nupkg from the nuget.org flat container.
#   2. Verify the nupkg sha256 against the committed manifest (the fetch
#      refuses to re-bless a changed manifest).
#   3. Entry-safe extraction (see scripts/GoldenDumper/Extraction.cs):
#      rejects absolute paths, `..`, backslash paths, symlink/hardlink
#      entries, and enforces per-entry (512 MB) and total (2 GB) caps.
#   4. Generate the deterministic corrupt fixtures into corpus/bin/corrupt.
#
# Supply chain decision (ADR): pinned version strings + sha256 manifests are
# the integrity control; NuGet signature verification is deliberately not
# attempted (nuget.org countersigns; hash pinning is sufficient for
# read-only test data).
set -euo pipefail

FETCH_IMAGE="cilantro-corpus-fetch:1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

if ! docker image inspect "${FETCH_IMAGE}" >/dev/null 2>&1; then
  echo "building ${FETCH_IMAGE} (pinned base: mcr.microsoft.com/dotnet/sdk:8.0@sha256:bb32ba3ba3ea36e38572d9d8db76fa15f7cbf722f3f886e06bca6d528bd4fba8)"
  docker build -f "${SCRIPT_DIR}/fetch_image/Dockerfile" -t "${FETCH_IMAGE}" "${SCRIPT_DIR}"
fi

exec docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e DOTNET_CLI_TELEMETRY_OPTOUT=1 \
  -e DOTNET_NOLOGO=1 \
  -e HOME=/tmp/container-home \
  -v "${REPO_ROOT}:/work" \
  -w /work \
  "${FETCH_IMAGE}" \
  bash -lc '
    set -euo pipefail
    mkdir -p "${HOME}"
    if [ -x /opt/goldendumper/GoldenDumper ]; then
      DUMPER=/opt/goldendumper/GoldenDumper
    else
      cd /work/scripts/GoldenDumper
      dotnet build -c Release -v quiet
      cd /work
      DUMPER=scripts/GoldenDumper/bin/Release/net8.0/GoldenDumper
    fi
    ${DUMPER} fetch --seed corpus/packages.json --out corpus
  '
