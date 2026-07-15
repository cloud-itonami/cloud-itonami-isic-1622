# ADR-0001: MillworkAdvisor ⊣ Millwork Shop Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-1622` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-1622` publishes an OSS blueprint for millwork-shop
**plant operations coordination** (production-batch dimensional-
spec/unit-count/output-quality data logging for doors, window frames,
staircases and structural wood components; cutting/joinery/finishing-
equipment maintenance scheduling; safety-concern flagging; and
outbound millwork shipment coordination). Like every actor in this
fleet, the blueprint alone is not an implementation: this ADR records
the governed-actor architecture that promotes it to real, tested
code, following the same langgraph StateGraph + independent Governor
+ Phase 0->3 rollout pattern established across the cloud-itonami
fleet.

The closest domain analogs are `cloud-itonami-isic-1610` (Sawmilling
and planing of wood) and `cloud-itonami-isic-1621` (Manufacture of
veneer sheets and wood-based panels): all three are back-office
coordination actors for heavy wood-processing plant equipment with a
real physical safety dimension. 1622 differs in one structural
respect that shapes this design: 1610's central process is size
reduction (sawing/planing a log into dimension lumber) and 1621's
central process is sheet/panel formation (peeling/slicing a log into
veneer, then pressing sheets into panels); 1622's central process is
**assembly/joinery of finished building components** (cutting,
mortise-and-tenon or dowel joinery, and finishing of doors, window
frames, staircases and structural wood components such as glue-
laminated beams). The central ground-truth entity is therefore a
**production batch of millwork units** (dimensional-spec/unit-count/
output-quality, not grade/volume-cubic-m) moving through a **shop**
with panel-saw/CNC-router/tenoning-machine/edge-bander/finishing-line
equipment (not saw/planer/kiln or veneer-lathe/hot-press/glue-spreader
equipment) -- the domain's HARD invariant about pre-verification
still applies to the SAME two independent entity kinds (the
referenced equipment unit for maintenance scheduling, and the
referenced batch for shipment coordination), inherited unchanged from
1610's and 1621's own Decision 4.

This vertical has NO pre-existing `kotoba-lang/millwork`-style
capability library to wrap (verified: no such repo exists). This
build therefore uses self-contained domain logic — pure functions in
`millwork.registry` (equipment/batch verification, shipment-unit-
count recompute, dimensional-spec validation, output-quality
plausibility validation) are re-verified independently by the
governor, the same "ground truth, not self-report" discipline
established across prior actors (most directly `cloud-itonami-isic-
1621`'s `veneerpanel.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:millwork-shop-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "millwork-shop-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external millwork capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
builders'-carpentry-and-joinery vertical has NO pre-existing
capability library to wrap. The equipment/batch-verification /
shipment-unit-count / dimensional-spec / output-quality validation
functions live as pure functions in `millwork.registry` and are
re-verified independently by `millwork.governor` — the same "ground
truth, not self-report" discipline established across prior actors
(most directly `cloud-itonami-isic-1621`'s `veneerpanel.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of millwork-shop
plant operations. It does NOT:
- Control cutting, joinery, or finishing-line equipment directly
- Make plant-safety or hazard decisions (exclusive to the human plant supervisor)
- Authorize or finalize a cutting/joinery/finishing-line run

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority —
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: builders'-carpentry-and-joinery
manufacturing is a safety-critical domain (panel-saw and CNC-router
blade/cutter injury risk, tenoning/mortising-machine crush/entrapment
risk, wood-dust fire/explosion hazard, finishing-line solvent/coating
fume inhalation hazard). Safety-concern flagging NEVER auto-commits.
All safety concerns escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (materials-safety concern, equipment hazard,
dust hazard, crew fatigue) ALWAYS escalates, never auto-commits. This
is not a "low-stakes proposal" — it is a circuit-breaker that must
reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Unlike a single-ground-truth-entity domain, this vertical has TWO
entity kinds each gating a different op, inherited unchanged from
`cloud-itonami-isic-1610`'s and `cloud-itonami-isic-1621`'s own
Decision 4: `:schedule-maintenance` independently verifies the
referenced **equipment** unit's own `:verified?`/`:registered?`
fields; `:coordinate-shipment` independently verifies the referenced
**batch**'s own `:verified?`/`:registered?` fields. Both are the same
"shop/batch record must be independently verified/registered before
any action" HARD invariant applied to the two distinct record kinds
this domain actually has. `:coordinate-shipment` additionally
independently recomputes whether a batch's own recorded shipped-to-
date unit count plus the proposal's own claimed unit count would
exceed the batch's own recorded production unit count — never taken
on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into ten concrete checks in
`millwork.governor`, mirroring `cloud-itonami-isic-1610`'s and
`cloud-itonami-isic-1621`'s own elaboration of their HARD invariants
into concrete checks) block proposals and cannot be overridden by
human approval:
1. Shop/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's unit count must independently recompute within the batch's own logged production unit count
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct cutting/joinery/finishing-line-equipment control or cutting-line-run finalization is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Millwork-shop plant operations back-office now has a documented,
governed, auditable coordination layer that funnels all decisions
through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into ten concrete governor checks) protect against scope creep into
unauthorized equipment operation or cutting/joinery/finishing-line-
run finalization. Safety concerns are a circuit-breaker, not a
threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and cutting/joinery/finishing-
line-run execution remain human-controlled via external channels.

(-) No integration with real shop-management databases (equipment
telemetry, batch tracking, freight dispatch) — this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-1622`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-unit-count-exceeded,
  cutting-line-finalize-blocked, already-scheduled,
  invalid-dimensional-spec, invalid-output-quality).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
