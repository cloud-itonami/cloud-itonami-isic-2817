# ADR-0001: OfficeMachOpsAdvisor ⊣ Office Machinery Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2817` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2817` publishes an OSS blueprint for
office-machinery **plant operations coordination** (production-batch
product-type/dielectric-withstand-test-result/quantity/defect-rate
data logging, assembly/test-bench-equipment maintenance scheduling,
safety-concern flagging, and outbound office-machinery shipment
coordination). Like every actor in this fleet, the blueprint alone is
not an implementation: this ADR records the governed-actor
architecture that promotes it to real, tested code, following the
same langgraph StateGraph + independent Governor + Phase 0->3 rollout
pattern established across the cloud-itonami fleet.

The closest domain analogs are `cloud-itonami-isic-2652` (Manufacture
of watches and clocks) and `cloud-itonami-isic-2710` (Manufacture of
electric motors, generators, transformers and electricity
distribution and control apparatus): all three are back-office
coordination actors for a fixed processing PLANT with precision
manufacturing/assembly/test-bench equipment and a real physical safety
dimension, and all share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/
`:flag-safety-concern`/`:coordinate-shipment`) and the same two-entity
verified/registered gate structure (equipment for maintenance
scheduling, batch for shipment coordination), plus the same
domain-specific certification-authority permanent block shape (2652
blocks self-issued chronometer/accuracy marks; 2710 blocks self-issued
electrical-safety marks; this build blocks self-issued UL/CE-type
safety-certification marks). This build mirrors
`cloud-itonami-isic-2652`'s architecture closely but adapts the hazard
profile and equipment/product vocabulary to the office-machinery
plant: this vertical's central physical hazard is electrical safety
(mains-powered office equipment -- typewriters, calculators, cash
registers, photocopiers, duplicating machines, postage meters, adding
machines -- with a dielectric-withstand/hipot test dimension), closer
in shape to 2710's dielectric-test-kv than to 2652's horological
accuracy-test-seconds-per-day; its permanent equipment-actuation block
guards assembly/test-bench EQUIPMENT (`:actuate-equipment?`) rather
than movement-assembly/casing/regulation/testing EQUIPMENT; its
production-batch record declares a `:product-type` (closed set
spanning typewriter/calculator/cash-register/photocopier/
duplicating-machine/postage-meter/adding-machine -- explicitly
EXCLUDING computers and peripheral equipment, which fall under ISIC
2620, per this class's own official ISIC Rev.4 name) and a
`:dielectric-withstand-test-kv` (the routine electrical-safety
withstand/hipot test reading in kV, plausibility-checked 0.0-5.0
against general low-voltage office-equipment electrical-safety testing
practice, e.g. IEC 62368-1/UL 62368-1 class-I hipot withstand tests) in
addition to a `:defect-rate-percent`, rather than 2652's
`:accuracy-test-seconds-per-day`; and its shipment quantity is tracked
in finished-unit UNITS (`:units`/`:quantity-units`/`:shipped-units`),
the same shape 2652/2710 use for finished discrete-counted products
(counted, not weighed, for freight coordination).

This vertical additionally has a DOMAIN-SPECIFIC permanent block in
the same shape 2652/2710 need (adapted, not copied verbatim):
manufacture of office machinery is subject to mandatory/voluntary
safety-certification regimes for mains-powered equipment (most notably
UL listing in North America and CE marking under applicable EU
electrical-safety/machinery directives). This actor is never the
certification authority — any proposal (regardless of op) that
declares `:issue-certification? true` is a HARD, PERMANENT,
unconditional block
(`officemach.governor/certification-authority-blocked-violations`),
the same "no phase, no human override" posture as the
equipment-actuation block.

This vertical has NO pre-existing `kotoba-lang/officemach`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic — pure functions in
`officemach.registry` (equipment/batch verification, shipment-quantity
recompute, product-type validation, dielectric-withstand-test
plausibility validation, defect-rate plausibility validation) are
re-verified independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2652`'s `watchmfg.registry` and
`cloud-itonami-isic-2710`'s `elecequipmfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:office-machinery-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "office-machinery-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created).

## Registry-name note

The `kotoba-lang/industry` registry's own `:name` field for `:id
"2817"` reads "Manufacture of office machinery and equipment" (missing
the official ISIC Rev.4 trailing clause "(except computers and
peripheral equipment)" that other registry entries with an exclusion
clause do preserve, e.g. entries for classes with an "except ..."
qualifier elsewhere in the same file). The `:id "2817"` itself,
surrounding sequential IDs (`"2816"`/`"2818"`), and the absence of any
computer/peripheral-equipment framing confirm this is genuinely ISIC
2817 with an abbreviated registry `:name` string, not a misassigned
class -- this build corrects the `:name` field to the full official
ISIC Rev.4 name as part of the same in-place `:spec` -> `:implemented`
registry edit.

## Decision

### Decision 1: Self-contained domain logic (no external office-machinery-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
office-machinery vertical has NO pre-existing capability library to
wrap. The equipment/batch-verification / shipment-quantity /
product-type / dielectric-withstand-test-result / defect-rate
validation functions live as pure functions in `officemach.registry`
and are re-verified independently by `officemach.governor` — the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-2652`'s `watchmfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of office-machinery
plant operations. It does NOT:
- Control assembly or test-bench equipment directly
- Make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
- Actuate assembly/test-bench equipment
- Self-issue a safety-certification mark (e.g. UL listing or CE marking)

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the certification body's authority — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: office-machinery manufacturing is a
safety-critical domain (electrical-safety hazard, precision-defect
risk, safety certification, downstream consumer-safety and
product-quality consequence). Safety-concern flagging NEVER
auto-commits. All safety concerns escalate immediately to human
review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (electrical-safety/mechanical-safety/UL-CE-
compliance concern) ALWAYS escalates, never auto-commits. This is not
a "low-stakes proposal" — it is a circuit-breaker that must reach
human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2652`/`cloud-itonami-isic-2710`, this
vertical has TWO entity kinds each gating a different op:
`:schedule-maintenance` independently verifies the referenced
**equipment** unit's own `:verified?`/`:registered?` fields;
`:coordinate-shipment` independently verifies the referenced
**batch**'s own `:verified?`/`:registered?` fields. Both are the same
"plant/batch record must be independently verified/registered before
any action" HARD invariant applied to the two distinct record kinds
this domain actually has. `:coordinate-shipment` additionally
independently recomputes whether a batch's own recorded
shipped-to-date unit quantity plus the proposal's own claimed unit
quantity would exceed the batch's own recorded production quantity —
never taken on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into twelve concrete checks
in `officemach.governor`, mirroring `cloud-itonami-isic-2652`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct assembly/test-bench-equipment control, equipment actuation, or self-issued safety-certification is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Office-machinery plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and no certification mark can ever be
self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into twelve concrete governor checks) protect against scope creep into
unauthorized equipment operation, equipment actuation, or
certification self-issuance. Safety concerns are a circuit-breaker,
not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and certification
issuance remain human-/institution-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, certification-body APIs)
— this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-2817`: `clojure -M:test` green -- 76 tests
  containing 212 assertions, 0 failures, 0 errors (verified from an
  independent fresh clone; see the superproject ADR and
  `kotoba-lang/industry` registry entry for the exact re-verification
  output), `clojure -M:lint` clean (0 errors, 0 warnings), `clojure
  -M:dev:run` demo narrative exercises proposal submission,
  escalation, and every HARD-hold scenario directly (not-propose-
  effect, unknown-op, equipment-not-verified, batch-not-verified,
  shipment-quantity-exceeded, equipment-actuate-blocked,
  certification-authority-blocked, already-scheduled, invalid-
  product-type, invalid-dielectric-withstand-test-kv, invalid-
  defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
