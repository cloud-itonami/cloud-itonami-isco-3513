(ns netops.governor
  "NetworkSystemsGovernor — the independent safety/traceability layer for the
  ISCO-08 3513 community network-operations actor (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section).

  Runtime: portable `.cljc`.

  Network-specific twist: CONNECTIVITY PRESERVATION — before any link removal
  is eligible for approval, the governor recomputes reachability over the
  remaining topology. A change that partitions the network is a graph fact; the
  advisor's claim that a link is redundant is never trusted.

  This namespace is now a composition, not a `cond->`. Each question is a named
  function in `netops.facts` and the vocabulary is `netops.operation`; what
  lives here is the ORDER the questions are asked in and which answers are
  hard. That ordering is the safety property, so it is stated once, here:

    1. vocabulary   — is the op one this actor may propose at all
    2. provenance   — is there a client, and is it registered
    3. actuation    — is the proposal a proposal
    4. confidence   — is the confidence a number in [0,1]
    5. citation     — does a non-topology op stay out of the topology
    6. removal basis— is every cited link registered, and to this client
    7. addition basis—is every added link between this client's registered nodes
    8. connectivity — does the network stay whole afterwards

  Steps 6 and 7 gate step 8, and this is the ordering that carries weight
  rather than the numbering: `facts/connectivity-violations` folds the
  proposal's `add-links` into the graph, so running it against unvalidated
  endpoints is exactly the hole that step 7 exists to close. On the pre-change
  tree a removal that stranded a node reached a human with an empty violation
  list because a link between invented endpoints was accepted as the proof
  that connectivity survived. Basis before reachability, always.

  ALL EIGHT ARE HARD (:hard? true, ALWAYS :hold, never overridable). Nothing
  above escalates, because there is no version of these questions a human can
  answer yes to: a human cannot make an unregistered link exist, cannot
  transfer another operator's link, and cannot approve a partition into
  existence — the redundant path has to be added first.

  ESCALATION (:escalate? true, human sign-off) has exactly two sources:
    * the operation declares `:escalates?` — `:apply-topology-change` mutates a
      running network, so it is always human, even when connectivity is
      preserved and every basis check passes.
    * a usable confidence below `facts/confidence-floor`.

  What changed and why. The pre-change governor gated both basis checks and the
  connectivity check on `(= :apply-topology-change op)`, which made it a
  denylist over an undeclared vocabulary: `:draft-change` — the document a
  human later reads and approves — was exempt from all three, and any op nobody
  had named was exempt from everything. The measurements are in the docstrings
  of `netops.operation` and `netops.facts`; `netops.sim` is the standing
  evidence that each of them is a refusal now."
  (:require [netops.facts :as facts]
            [netops.operation :as op]))

(def confidence-floor
  "Re-exported from `netops.facts` so existing callers and operators reading
  this namespace still find it here."
  facts/confidence-floor)

(defn- hard-violations
  "Ask the eight questions in order. Returns the first non-empty group rather
  than concatenating all of them: a proposal naming an undeclared op has no
  meaningful topology basis to report, and a violation list mixing 'this op
  does not exist' with 'its links are unregistered' invites a reader to fix the
  second one. Basis violations for removals and additions ARE reported
  together, because they are the same question about two halves of one change."
  [store request proposal]
  (let [{:keys [op]} proposal
        client-id (:client-id request)
        vocabulary (facts/vocabulary-violations proposal)
        provenance (facts/provenance-violations store request)
        actuation  (facts/actuation-violations proposal)
        confidence (facts/confidence-violations proposal)
        citation   (facts/citation-violations proposal)]
    (or (seq vocabulary)
        (seq provenance)
        (seq actuation)
        (seq confidence)
        (seq citation)
        (when (op/topology-op? op)
          (let [basis (into (facts/removal-basis-violations store client-id proposal)
                            (facts/addition-basis-violations store client-id proposal))]
            (or (seq basis)
                (seq (facts/connectivity-violations store client-id proposal)))))
        [])))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a `store`
  implementing `netops.store/Store`. Pure — never mutates the store.

  `:ok?` is the conjunction the graph does NOT route on: `netops.phase/of-verdict`
  reads `:hard?` and `:escalate?` directly, so a future edit that makes `:ok?`
  disagree with them cannot silently turn a hold into a write. It is kept
  because it is the field an operator reads."
  [request context proposal store]
  (let [hard (vec (hard-violations store request proposal))
        hard? (boolean (seq hard))
        conf (:confidence proposal)
        low? (facts/low-confidence? conf)
        escalating-op? (op/escalates? (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not escalating-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? escalating-op?))
     :context context}))
