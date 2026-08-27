#!/usr/bin/env bash
# ensure_corpus.sh — check the corpus cache against the committed
# integrity manifest, and fix it (full fetch) when anything is missing or
# wrong. Fails loudly on any problem the fetch cannot repair.
#
#   verify (warm cache)  ->  seconds, no network
#   fetch  (cold/broken) ->  downloads pinned nupkgs, re-extracts,
#                            regenerates goldens (docker, house rule)
#
# The committed corpus/manifest.json pins the expected sha256s; the fetch
# refuses to re-record a changed nupkg, so a tampered cache can never
# re-bless itself.
set -euo pipefail

FETCH_IMAGE="cilantro-corpus-fetch:1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

if ! command -v docker >/dev/null 2>&1; then
  echo "error: docker is required to check/fix the corpus cache (house rule)" >&2
  exit 1
fi

if ! docker image inspect "${FETCH_IMAGE}" >/dev/null 2>&1; then
  echo "building ${FETCH_IMAGE} (pinned base: mcr.microsoft.com/dotnet/sdk:8.0@sha256:bb32ba3ba3ea36e38572d9d8db76fa15f7cbf722f3f886e06bca6d528bd4fba8)"
  docker build -t "${FETCH_IMAGE}" "${SCRIPT_DIR}/fetch_image"
fi

run_verify() {
  docker run --rm \
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
      cd /work/scripts/GoldenDumper
      dotnet build -c Release -v quiet
      cd /work
      scripts/GoldenDumper/bin/Release/net8.0/GoldenDumper verify \
        --manifest corpus/manifest.json --out corpus
    '
}

if [ ! -f "${REPO_ROOT}/corpus/manifest.json" ]; then
  echo "corpus cache absent (no manifest) — fetching"
  "${SCRIPT_DIR}/fetch_corpus.sh"
  exit 0
fi

if run_verify; then
  echo "corpus cache verified"
  exit 0
fi

echo "corpus cache incomplete — fetching"
"${SCRIPT_DIR}/fetch_corpus.sh"

if ! run_verify; then
  echo "error: corpus cache still incomplete after fetch" >&2
  exit 1
fi

echo "corpus cache verified after fetch"
