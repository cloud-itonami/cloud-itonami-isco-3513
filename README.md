# cloud-itonami-isco-3513

**Community Network Operations** — the ISCO-08 3513 (Computer Network
and Systems Technicians) actor, an ISCO **Wave 0** occupation per
ADR-2607121000: pure-cognitive work (planning/monitoring/triage), the
LLM-first wave, no robotics gate. Completes the ICT-operations cluster
(2521 database / 2522 sysadmin / 3513 network).

**Maturity: `:implemented`** — NetworkSystemsAdvisor ⊣
NetworkSystemsGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
66 tests / 244 assertions green, plus an 18-scenario governed run
(`clojure -M:sim`) of which 16 are refusals.

The network-specific HARD invariant: **connectivity preservation** —
before any link removal is eligible for approval, the governor
recomputes reachability over the remaining topology (pure BFS over the
REGISTERED nodes/links, with the plan's added links included, so
remove-bridge-while-adding-replacement passes). A partition-inducing
change is a graph fact held at any confidence; the advisor's "that
link is redundant" claim is never trusted — add the redundant path
first. Also HARD: undeclared and reserved ops, invented/foreign links,
an added link whose endpoints are not registered nodes, a client with
no registered topology, an unreadable or out-of-range confidence, a
request carrying no client-id, an unregistered organization, and a
non-`:propose` effect. Escalations (always human sign-off):
`:apply-topology-change` (even when connectivity is preserved), and a
usable confidence below 0.6.

**These invariants apply to `:draft-change` as well as to
`:apply-topology-change`.** Until 2026-09-10 they did not: both basis
checks and the connectivity check were gated on
`(= :apply-topology-change op)`, so a draft that stranded a node, cited
an unregistered link, or cited another operator's link was committed
with `:ok? true` and an empty violation list. The draft is the document
an operator reads before approving, so it was the one the governor was
not reading. The measurements are in the docstrings of
`netops.operation` and `netops.facts`; `netops.sim` is the standing
evidence that each is a refusal now.

Seven namespaces, all portable `.cljc`:

| namespace | what it owns |
|---|---|
| `netops.operation` | the closed op vocabulary — `supported` (allowlist) and `reserved` (authority boundaries) |
| `netops.facts` | one named, pure, testable predicate per question the governor asks |
| `netops.governor` | the ORDER those questions are asked in, and which answers are hard |
| `netops.phase` | verdict → phase (`:hold` before `:request-approval`), and what each phase may do |
| `netops.ledger` | hash-chained append-only entries, `verify`, and who approved each write |
| `netops.store` | the topology and record SSoT |
| `netops.actor` | the wired StateGraph |
| `netops.advisor` | proposes; never writes |

```bash
clojure -M:test   # unit tests
clojure -M:sim    # governed scenario run — exits non-zero if it refused nothing
clojure -M:lint   # clj-kondo, errors fail
```

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
