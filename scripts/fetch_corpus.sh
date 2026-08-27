#!/usr/bin/env bash
# fetch_corpus.sh — download, verify, extract and golden-dump the cilantro
# parity corpus. Runs entirely inside a docker image derived from a pinned
# mcr.microsoft.com/dotnet/sdk image (by digest, never :latest), with the
# invoking user's uid/gid so files written to the mounted corpus/ volume are
# owned correctly.
#
# Every step fails the script on error (`set -euo pipefail`):
#   1. Download each pinned .nupkg from the nuget.org flat container.
#   2. Verify/record the nupkg sha256 in corpus/manifest.json.
#   3. Entry-safe extraction (see scripts/GoldenDumper/Extraction.cs):
#      rejects absolute paths, `..`, backslash paths, symlink/hardlink
#      entries, and enforces per-entry (512 MB) and total (2 GB) caps.
#   4. Extract lib DLLs to corpus/bin/<package>/<tfm>/<file>.dll, skipping
#      satellite/resource/native DLLs by rule; record each DLL sha256.
#   5. Generate goldens (tier1/tier2) into corpus/golden/ with Mono.Cecil
#      0.11.6 (pinned) and compile the hand-authored ilasm fixture.
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
  docker build -t "${FETCH_IMAGE}" "${SCRIPT_DIR}/fetch_image"
fi

exec docker run --rm \
  --user "$(id -u):$(id -g)" \
  --network host \
  -e DOTNET_CLI_TELEMETRY_OPTOUT=1 \
  -e DOTNET_NOLOGO=1 \
  -e HOME=/tmp/container-home \
  -v "${REPO_ROOT}:/work" \
  -w /work \
  "${FETCH_IMAGE}" \
  bash -lc '
    set -euo pipefail
    mkdir -p "${HOME}"
    cd /work/scripts/GoldenDumper
    dotnet build -c Release -v quiet
    cd /work
    scripts/GoldenDumper/bin/Release/net8.0/GoldenDumper fetch \
      --seed corpus/packages.json --out corpus
    scripts/GoldenDumper/bin/Release/net8.0/GoldenDumper dump \
      --manifest corpus/manifest.json --out corpus/golden
  '
