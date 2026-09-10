(ns netops.phase
  "The verdict -> phase mapping for the ISCO-08 3513 community
  network-operations actor, and what each phase is allowed to do.

  Runtime: portable `.cljc` (pure functions over data, no host interop).

  Why this namespace exists. The mapping used to be an inline `cond` inside the
  StateGraph's `:decide` node. Being inline, it could only be exercised by
  building and running a graph, so the routing rule that decides whether a
  topology change is written, held, or sent to a human had no test of its own
  and no name a ledger entry could carry.

  Three phases, and the ordering between them is the whole safety claim:

    :hold             hard violation. Never written. Not overridable.
    :request-approval escalation. Written only after a human resumes.
    :commit           clean. Written.

  `of-verdict` checks `:hard?` before `:escalate?` deliberately. A proposal
  that is both hard-blocked and escalating must hold, not escalate —
  escalating it would put a question to a human that they have no authority to
  answer yes to. For this actor the two conditions coincide on exactly the
  request most likely to be waved through: `:apply-topology-change` is an
  escalating operation *and* a topology-binding one, so it is both the only op
  that reaches a human by construction and an op that can carry a partitioning
  removal, an unregistered link or another operator's link.

  Note precisely what this ordering does and does not currently protect.
  `netops.governor` already computes `:escalate?` as `(and (not hard?) ...)`,
  so a verdict carrying BOTH flags is a shape it does not emit today; the
  ordering here is the second of two independent guards, and the one that holds
  for any caller building a verdict by hand or for a future governor that stops
  zeroing the flag. It is therefore covered by a unit test over this pure
  function and NOT by `netops.sim` — reversing the two clauses leaves the whole
  scenario table green. That is stated rather than left for a reader to
  discover, because a guard whose only test runs through the graph would be a
  guard nobody is actually checking."
  (:require [netops.operation :as op]))

(def phases
  "Every phase this actor can route to, with what it may do."
  {:hold             {:writes? false :human-required? false :terminal? true}
   :request-approval {:writes? false :human-required? true  :terminal? false}
   :commit           {:writes? true  :human-required? false :terminal? true}})

(defn of-verdict
  "Route a governor verdict to a phase. Pure."
  [verdict]
  (cond
    (:hard? verdict)     :hold
    (:escalate? verdict) :request-approval
    :else                :commit))

(defn writes? [phase] (boolean (get-in phases [phase :writes?])))
(defn human-required? [phase] (boolean (get-in phases [phase :human-required?])))
(defn terminal? [phase] (boolean (get-in phases [phase :terminal?])))

(defn refusal?
  "True for phases that did not write. Both `:hold` and `:request-approval` are
  refusals of the proposal as submitted — the second one is a refusal to act
  without a human, not an approval-in-waiting. `netops.sim` counts these; a run
  that produces none has demonstrated nothing."
  [phase]
  (not (writes? phase)))

(defn approved-commit?
  "True when a commit is being reached from an escalation, i.e. a human resumed
  the interrupted thread. The commit node records this so the audit ledger can
  distinguish a human-approved write from an automatic one — measured on the
  pre-change tree, it could not: a topology change applied to a running network
  after human sign-off and a routine draft committed automatically left two
  `{:disposition :commit ...}` entries with no field telling them apart. For a
  change being applied to a community network people depend on, that
  distinction is the entire reason the interrupt exists."
  [disposition]
  (= :request-approval disposition))

(defn escalating-op?
  "True for operations that reach `:request-approval` by declaration rather
  than by confidence. Re-exported from `netops.operation` so a reader of the
  routing rule can see which ops always take the human path without following
  a second namespace."
  [o]
  (op/escalates? o))
