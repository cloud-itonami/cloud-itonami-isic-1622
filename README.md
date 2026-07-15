# cloud-itonami-isic-1622: Manufacture of builders' carpentry and joinery

Open Business Blueprint for **ISIC Rev.5 1622**: manufacture of builders' carpentry and joinery — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office millwork-shop **plant operations**: production-batch data logging (dimensional-spec/unit-count/output-quality for doors, window frames, staircases and structural wood components), cutting/joinery/finishing-equipment maintenance scheduling, safety-concern flagging, and outbound millwork shipment coordination.

This repository designs a forkable OSS business for millwork-shop plant
operations: run by a qualified operator so a builders'-carpentry-and-
joinery shop (doors, window frames, staircases, structural wood
components manufactured for construction) keeps its own operating
records instead of renting a closed SaaS.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — millwork batch dimensional-spec/output-quality data logging (administrative, not an operational decision)
- `:schedule-maintenance` — cutting/joinery/finishing-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a materials-safety/equipment-safety/dust-hazard concern (always escalates)
- `:coordinate-shipment` — outbound millwork shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain** (panel
saws and CNC routers, tenoning/mortising machines, wood-dust fire/
explosion hazard, finishing-line solvent/coating fume hazard):

- Does NOT control cutting, joinery, or finishing-line equipment directly
- Does NOT make plant-safety or hazard decisions (that's the plant supervisor's exclusive human authority)
- Does NOT authorize or finalize a cutting/joinery/finishing-line run (human plant supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`millwork.operation/build`, a langgraph-clj StateGraph):
1. **`millwork.advisor`** (sealed intelligence node, `MillworkAdvisor`): proposes decisions only, never commits
2. **`millwork.governor`** (independent, `Millwork Shop Plant Operations Governor`): validates against domain rules, re-derived from `millwork.registry`'s pure functions and `millwork.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Shop/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct cutting/joinery/finishing-line-equipment control)
     - Finalizing a cutting/joinery/finishing-line run (`:finalize? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped unit count past its own logged production unit count (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:dimensional-spec` value on a production-batch patch
     - No physically implausible `:output-quality-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`millwork.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`millwork.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
