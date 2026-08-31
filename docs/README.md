# Cilantro documentation

Table of contents for the human and LLM documentation.

## Human docs

| Doc | What it covers |
|---|---|
| [README.md](../README.md) | Project readme (root) |
| [ARCHITECTURE.md](ARCHITECTURE.md) | How cilantro reads a .NET assembly; the model; pinned reader semantics; security posture; corpus provisioning gate (ADR-0009) |
| [OPERATIONS.md](OPERATIONS.md) | Getting started, suites, corpus operations (provision, fetch, golden regeneration), parity-failure interpretation, troubleshooting |
| [GR_INTEGRATION.md](GR_INTEGRATION.md) | Golden-helper integration |
| [PUBLISHING.md](PUBLISHING.md) | Publishing |
| [adr/0009_corpus_ground_truth_and_on_demand_provisioning.md](adr/0009_corpus_ground_truth_and_on_demand_provisioning.md) | ADR-0009: committed ground truth, on-demand cache provisioning |

## LLM docs

| Doc | What it covers |
|---|---|
| [README_llm.md](../README_llm.md) | Project readme, LLM copy (root) |
| [ARCHITECTURE_llm.md](ARCHITECTURE_llm.md) | Machine-oriented architecture facts incl. the provisioning gate, fetcher, extractor and pin tests |
| [OPERATIONS_llm.md](OPERATIONS_llm.md) | Machine-oriented operations incl. the completeness definition, lock protocol and docker footprint, each claim tied to its test |
| [GR_INTEGRATION_llm.md](GR_INTEGRATION_llm.md) | Golden-helper integration, LLM copy |
