#!/usr/bin/env bash
# ensure_corpus.sh — ops remedy for a missing, corrupt, or incomplete
# corpus cache (ADR-0006).
#
# It runs the exact same provisioning gate the test suite uses
# (CorpusProvisioner, via the sbt main):
#   1. Pure-JVM fetch of the pinned nupkgs into corpus/nupkg, entry-safe
#      extraction into corpus/bin, deterministic corrupt fixtures
#      (no docker, no .NET).
#   2. Golden regeneration into corpus/golden/bin when absent — docker
#      only (house rule: the pinned Mono.Cecil dumper is a .NET tool).
#   3. Retry-once on failure, then an actionable error message.
#
# Requirements: docker (for the golden step), network (nupkg download),
# and the JVM/sbt (the gate runs in the sbt test classpath).
#
# If docker is unavailable and corpus/golden/bin is missing, the error
# message tells you to copy corpus/golden/bin from a machine that has it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

if ! command -v sbt >/dev/null 2>&1; then
  echo "error: sbt is required to run the provisioning gate (see docs/OPERATIONS)" >&2
  exit 1
fi

cd "${REPO_ROOT}/src/cilantro"
sbt "test:runMain io.spicelabs.cilantro.metadata.CorpusProvisioner"
