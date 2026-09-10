(ns netops.operation
  "The closed vocabulary of operations the ISCO-08 3513 community
  network-operations actor may propose.

  Runtime: portable `.cljc` (pure data + pure predicates, no host interop).

  Why this namespace exists. Before it, the operation vocabulary lived in two
  places that could not disagree loudly: the README's prose, and the
  Governor's private `(= :apply-topology-change op)` test. That made the
  Governor a *denylist* — it bound one named op and admitted everything else.
  Measured on the pre-change tree, against a registered client whose topology
  `N-A -- N-B -- N-C` was fully registered:

      {:op :drop-the-backbone :effect :propose :confidence 0.95}
      => {:ok? true :hard? false :escalate? false :violations []}

  Admitted, and admitted as a *clean* verdict: no escalation, no human, and an
  empty violation list to show a reviewer. `:op nil` was admitted the same way.

  An actor whose operation set is open cannot be governed, because the governor
  is answering a question about a vocabulary nobody declared. So the vocabulary
  is declared here, once, as an allowlist, and `netops.governor` refuses
  anything outside it.

  Two disjoint maps:

  * `supported` — what the actor may propose. `:escalates?` and `:topology-op?`
    are properties of the operation, not of the governor's mood, so they live
    beside it.
  * `reserved` — operations naming authority this cognitive actor does not
    hold: taking a community network down, granting access, intercepting
    traffic, removing the audit trail. These are *declared* rather than merely
    absent so the refusal can say why. An undeclared op is a vocabulary error;
    a reserved op is an authority boundary. Conflating them would let a future
    edit `supported`-list one of them by accident.

  `:topology-op?` is the field that closes the gap this repo shipped with. Both
  basis checks and the connectivity check were gated on
  `(= :apply-topology-change op)`, so `:draft-change` — the operation that
  produces the document a human later reads and approves — was exempt from all
  three. Measured on the pre-change tree, same registered client, where
  `L-BC` is the bridge whose removal strands `N-C`, `L-NOT-REAL` is
  unregistered and `L-FOREIGN` belongs to a different client:

      {:op :draft-change :remove-links [\"L-NOT-REAL\"] ...} => {:ok? true :violations []}
      {:op :draft-change :remove-links [\"L-FOREIGN\"]  ...} => {:ok? true :violations []}
      {:op :draft-change :remove-links [\"L-BC\"]       ...} => {:ok? true :violations []}

  The same three requests under `:apply-topology-change` were refused. So the
  network-partitioning change was committed as a clean draft, and the topology
  it cited was never required to exist or to belong to the client — the draft
  is where an operator's judgement is actually formed, and it was the one
  document the governor was not reading. Binding is a property of the
  operation, so it is declared here and the governor reads it, rather than the
  governor naming one op and forgetting the one that precedes it.")

(def supported
  "Operations the actor may propose.

  `:escalates?` true means human sign-off is required regardless of advisor
  confidence. `:topology-op?` true means the proposal binds to this client's
  REGISTERED topology, and therefore must satisfy both basis checks — every
  cited link registered to this client, every added link's endpoints a
  registered node of this client — and the connectivity check.

  Only the three operations the README has always claimed are listed. The
  vocabulary is deliberately not widened here: this namespace exists to close
  an opening, and adding ops would be the opposite of that."
  {:draft-change
   {:escalates?   false
    :topology-op? true
    :summary "draft a topology change: cite the links to remove and the links that replace them"}

   :apply-topology-change
   {:escalates?   true
    :topology-op? true
    :summary "apply a topology change to the running network (always human sign-off)"}

   :diagnose
   {:escalates?   false
    :topology-op? false
    :summary "diagnose a reported fault; cites no links, changes no topology"}})

(def reserved
  "Operations reserved to someone this actor is not. Naming one in a proposal
  is a permanent hard block, never an escalation: escalation would imply a
  human could approve the *actor* doing it, and neither the on-call operator
  nor the network's owner can delegate taking a community network down, an
  access grant, traffic interception, or the removal of an audit trail to a
  remote cognitive actor.

  This is the machine-readable form of the scope sentence the README has
  carried since the repo was created — the advisor only proposes. Prose in a
  README does not refuse anything."
  {:shutdown-network
   {:reason "コミュニティ網を落とす決定は網の所有者のもので、実施は現地の責任者が行う（提案の範囲外）"}

   :grant-network-access
   {:reason "権限付与は網の所有者の決定であって、トポロジ分析の結論ではない"}

   :intercept-traffic
   {:reason "通信の傍受は法的権限の問題であって、運用上の必要から導けるものではない"}

   :disable-audit-ledger
   {:reason "監査台帳を外すことは、この actor 自身の governance が依拠している証拠を消すこと"}})

(defn supported? [op] (contains? supported op))
(defn reserved? [op] (contains? reserved op))

(defn declared?
  "True if `op` is named anywhere in this vocabulary. An op that is neither
  supported nor reserved is undeclared — the governor refuses it."
  [op]
  (or (supported? op) (reserved? op)))

(defn escalates?
  "True if the operation itself always requires human sign-off. Unsupported ops
  are never reached by this predicate (the governor hard-blocks first), so a
  false here is not an admission."
  [op]
  (boolean (get-in supported [op :escalates?])))

(defn topology-op?
  "True if the operation binds to this client's registered topology and must
  therefore satisfy both basis checks and the connectivity check. False for
  undeclared and reserved ops, which the governor hard-blocks before this is
  consulted, and false for `:diagnose`, which may cite no links at all —
  `netops.facts/citation-violations` enforces that other half, so closing the
  gate on topology ops does not simply move the hole to the op that has no
  gate."
  [op]
  (boolean (get-in supported [op :topology-op?])))

(defn reserved-reason [op] (get-in reserved [op :reason]))
