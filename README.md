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
12 tests / 25 assertions green.

The network-specific HARD invariant: **connectivity preservation** —
before any link removal is eligible for approval, the governor
recomputes reachability over the remaining topology (pure BFS over the
REGISTERED nodes/links, with the plan's added links included, so
remove-bridge-while-adding-replacement passes). A partition-inducing
change is a graph fact held at any confidence; the advisor's "that
link is redundant" claim is never trusted — add the redundant path
first. Also HARD: invented/foreign links, unregistered organization,
non-`:propose` effect. Escalations (always human sign-off):
`:apply-topology-change` (even when connectivity is preserved), low
confidence (< 0.6).

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
