(ns netops.sim
  "Deterministic governed-scenario harness for the ISCO-08 3513 community
  network-operations actor: run a table of requests through the real
  StateGraph and report which ones the governor refused.

  Runtime: `run` and `report` are portable `.cljc`. `-main` is `:clj`-only,
  because process exit codes are a host concern; the `:cljs` branch throws
  rather than pretending to exit.

  Why this namespace exists, and why it fails loudly. A governed actor's claim
  is not that it acts — it is that there exist actions it refuses. A harness
  that ran only clean scenarios would print green while demonstrating nothing,
  which is the shape this workspace has repeatedly caught: a check that could
  not fail returning the same value as a check that passed.

  So `run` counts refusals, and `-main` exits non-zero when the count is zero.
  A scenario table that has stopped exercising the governor is a defect in the
  table, and it is reported as one rather than as a pass.

  The four questions this harness answers that a unit test does not:
    * does the *wired graph* refuse, or only the pure `check` function
    * does it refuse for the reason it names, or for some other reason that
      happens to produce the same phase
    * does an escalated request actually interrupt rather than write
    * does the ledger it leaves behind verify, and does it record who approved
      each write

  The second one is why every refusal scenario carries `:because`, a violation
  rule that must appear in the verdict, and every admissible one carries
  `:clean?`, which asserts the violation list is empty. A scenario asserting
  only the phase counts a run that failed for an unrelated reason as a
  demonstration — and this table has a concrete instance of that: the
  partitioning draft and the draft citing an unregistered link both reach
  `:hold`, so without `:because` a governor that had lost the connectivity
  check entirely would still show green on both.

  Two mutations are NOT caught here, and naming them is more useful than
  implying the table is complete:

    * reversing the two clauses of `phase/of-verdict` — the governor does not
      emit a verdict that is both hard and escalating, so the ordering has no
      observable effect on any scenario. `netops.phase-test` covers it.
    * unchaining `ledger/entry` (always hashing against prev 0) — almost every
      scenario here runs the graph once, and a single-entry ledger has prev 0
      legitimately. `netops.ledger-test` covers it, and
      `actor-test/the-ledger-it-leaves-behind-verifies` runs the graph twice on
      one store, which is what makes the break observable.

  Every scenario marked `pre-change` below is one of the refusals measured as
  MISSING on the pre-change tree — see the docstrings of `netops.operation` and
  `netops.facts` for those measurements. This table is the standing evidence
  that they are refusals now."
  (:require [netops.actor :as actor]
            [netops.advisor :as advisor]
            [netops.ledger :as led]
            [netops.phase :as phase]
            [netops.store :as store]))

(def registered-client
  {:client-id "sim-client-1" :name "Awai Community Network"})

(def other-client
  {:client-id "sim-client-2" :name "Another Operator"})

(def registered-nodes
  "A -- B -- C. `L-BC` is a bridge: removing it strands `N-C`."
  [{:node-id "N-A" :client-id "sim-client-1" :name "village hall"}
   {:node-id "N-B" :client-id "sim-client-1" :name "relay mast"}
   {:node-id "N-C" :client-id "sim-client-1" :name "clinic"}])

(def registered-links
  [{:link-id "L-AB" :client-id "sim-client-1" :a "N-A" :b "N-B"}
   {:link-id "L-BC" :client-id "sim-client-1" :a "N-B" :b "N-C"}])

(def foreign-link
  "Registered, but to the OTHER client. Citing it is a basis violation, not a
  missing link."
  {:link-id "L-FOREIGN" :client-id "sim-client-2" :a "X" :b "Y"})

(defn- tweaking-advisor
  "An advisor that proposes as the mock does, then applies `f` to the proposal.
  Used to reach proposal shapes a well-formed request cannot produce — an
  unusable confidence, a direct write effect."
  [f]
  (let [inner (advisor/mock-advisor)]
    (reify advisor/Advisor
      (-advise [_ store request] (f (advisor/-advise inner store request))))))

(def scenarios
  "Each entry: the request, the phase it must reach, and why.

  `:expect` is the phase, not merely 'refused', so a scenario that starts
  holding for the wrong reason, or that escalates where it should hold, is a
  mismatch rather than a pass.

  `:because` is the violation rule that must appear in the verdict. Without it
  a scenario passes when the graph holds for any reason at all, which is the
  shape where a check stops discriminating without turning red.

  `:clients`, `:nodes` and `:links` override the default registration for
  scenarios about the registration itself."
  [{:name :clean-diagnose
    :request {:client-id "sim-client-1" :op :diagnose}
    :expect :commit
    :clean? true
    :why "a diagnosis citing no links is admissible without a human"}

   {:name :clean-draft-replacing-the-bridge
    :request {:client-id "sim-client-1" :op :draft-change
              :remove-links ["L-BC"]
              :add-links [{:link-id "L-AC" :a "N-A" :b "N-C"}]}
    :expect :commit
    :clean? true
    :why "removing the bridge is safe when the replacement joins REGISTERED nodes"}

   {:name :apply-topology-change-always-escalates
    :request {:client-id "sim-client-1" :op :apply-topology-change
              :remove-links ["L-BC"]
              :add-links [{:link-id "L-AC" :a "N-A" :b "N-C"}]}
    :expect :request-approval
    :clean? true
    :why "mutating a running network is human sign-off even when every check passes"}

   {:name :draft-removing-the-bridge
    :request {:client-id "sim-client-1" :op :draft-change :remove-links ["L-BC"]}
    :because :partition-risk
    :expect :hold
    :why "pre-change: a draft that strands the clinic was committed clean"}

   {:name :draft-citing-unregistered-link
    :request {:client-id "sim-client-1" :op :draft-change :remove-links ["L-NOT-REAL"]}
    :because :unknown-link
    :expect :hold
    :why "pre-change: invented topology in a draft was committed clean"}

   {:name :draft-citing-another-operators-link
    :request {:client-id "sim-client-1" :op :draft-change :remove-links ["L-FOREIGN"]}
    :because :link-wrong-client
    :expect :hold
    :why "pre-change: another operator's link in a draft was committed clean"}

   {:name :phantom-replacement-link
    :request {:client-id "sim-client-1" :op :apply-topology-change
              :remove-links ["L-BC"]
              :add-links [{:link-id "L-GHOST" :a "N-A" :b "N-GHOST"}]}
    :because :unknown-node
    :expect :hold
    :why "pre-change: an invented endpoint proved the partition away, and reached a human with no violations"}

   {:name :undeclared-op
    :request {:client-id "sim-client-1" :op :drop-the-backbone}
    :because :undeclared-op
    :expect :hold
    :why "pre-change: an op nobody declared was admitted as a clean verdict"}

   {:name :nil-op
    :request {:client-id "sim-client-1" :op nil}
    :because :undeclared-op
    :expect :hold
    :why "pre-change: a nil op was admitted as a clean verdict"}

   {:name :reserved-op-traffic-interception
    :request {:client-id "sim-client-1" :op :intercept-traffic}
    :because :reserved-op
    :expect :hold
    :why "an authority boundary is a permanent block, never an escalation"}

   {:name :diagnose-citing-links
    :request {:client-id "sim-client-1" :op :diagnose :remove-links ["L-BC"]}
    :because :citation-without-topology-op
    :expect :hold
    :why "closing the gate on topology ops must not leave the ungated op as the new hole"}

   {:name :unregistered-client
    :request {:client-id "sim-client-404" :op :diagnose}
    :because :no-client
    :expect :hold
    :why "client provenance"}

   {:name :request-without-client-id
    :clients [{}]
    :request {:op :diagnose}
    :because :no-client-id
    :expect :hold
    :why "pre-change: the empty-map client landed under the nil key and answered for this request"}

   {:name :client-with-no-registered-topology
    :clients [{:client-id "sim-client-3" :name "No Topology Co"}]
    :nodes []
    :links [{:link-id "L-1" :client-id "sim-client-3" :a "P" :b "Q"}]
    :request {:client-id "sim-client-3" :op :draft-change :remove-links ["L-1"]}
    :because :no-registered-topology
    :expect :hold
    :why "pre-change: an empty node set proved connectivity for every removal"}

   {:name :unusable-confidence-non-numeric
    :request {:client-id "sim-client-1" :op :diagnose}
    :tweak #(assoc % :confidence "high")
    :because :unusable-confidence
    :expect :hold
    :why "pre-change: threw on clj, admitted clean on cljs — same file, opposite verdicts"}

   {:name :unusable-confidence-above-one
    :request {:client-id "sim-client-1" :op :diagnose}
    :tweak #(assoc % :confidence 99.0)
    :because :unusable-confidence
    :expect :hold
    :why "pre-change: a floor with no ceiling let 99.0 buy out of escalation"}

   {:name :low-confidence-escalates
    :request {:client-id "sim-client-1" :op :diagnose}
    :tweak #(assoc % :confidence 0.1)
    :expect :request-approval
    :clean? true
    :why "a usable but low confidence is a question for a human, not a block"}

   {:name :direct-write-effect
    :request {:client-id "sim-client-1" :op :diagnose}
    :tweak #(assoc % :effect :write)
    :because :no-actuation
    :expect :hold
    :why "the advisor proposes; it never writes"}])

(defn- seeded-store [scenario]
  (let [st (store/mem-store)]
    (doseq [c (:clients scenario [registered-client other-client])]
      (store/register-client! st c))
    (doseq [n (:nodes scenario registered-nodes)]
      (store/register-node! st n))
    (doseq [l (:links scenario (conj registered-links foreign-link))]
      (store/register-link! st l))
    st))

(defn- run-one [scenario]
  (let [st (seeded-store scenario)
        graph (actor/build-graph
               (cond-> {:store st}
                 (:tweak scenario) (assoc :advisor (tweaking-advisor (:tweak scenario)))))
        thread (str "sim-" (name (:name scenario)))
        result (actor/run-request! graph (:request scenario) {} thread)
        state (:state result)
        actual (or (:disposition state)
                   ;; A run that never reached :decide produced no phase at
                   ;; all; report that rather than defaulting it to a phase,
                   ;; which would make an unrun scenario look like a verdict.
                   :no-phase)
        rules (into #{} (map :rule) (:violations (:verdict state)))
        ;; A scenario with neither :because nor :clean? asserts nothing about
        ;; WHY, so it is reported as unreasoned rather than silently passing.
        reasoned? (or (contains? scenario :because) (:clean? scenario))
        because-ok? (cond
                      (:because scenario) (contains? rules (:because scenario))
                      (:clean? scenario)  (empty? rules)
                      :else false)]
    {:name (:name scenario)
     :expect (:expect scenario)
     :actual actual
     :why (:why scenario)
     :status (:status result)
     :because (:because scenario)
     :rules rules
     :reasoned? reasoned?
     :because-ok? because-ok?
     :match? (and (= actual (:expect scenario)) because-ok?)
     :phase-match? (= actual (:expect scenario))
     :refusal? (and (not= actual :no-phase) (phase/refusal? actual))
     :wrote? (pos? (count (store/records-of st (:client-id (:request scenario)))))
     :ledger-verify (led/verify (store/ledger st))}))

(defn run
  "Run every scenario. Returns
  `{:results [..] :refusals n :mismatches [..] :ledger-breaks [..] :ok? bool}`.

  `:ok?` requires five things: every scenario reached its expected phase FOR
  THE REASON IT NAMES, every scenario names a reason at all, no refusal wrote a
  record anyway, every ledger left behind verifies, and at least one refusal was
  demonstrated."
  []
  (let [results (mapv run-one scenarios)
        refusals (count (filter :refusal? results))
        mismatches (filterv (complement :match?) results)
        unreasoned (filterv (complement :reasoned?) results)
        ;; A refusal that still wrote a record is the worst outcome available
        ;; and would otherwise hide inside a matching phase.
        wrote-anyway (filterv #(and (:refusal? %) (:wrote? %)) results)
        ledger-breaks (filterv #(not (:ok? (:ledger-verify %))) results)]
    {:results results
     :refusals refusals
     :mismatches mismatches
     :unreasoned unreasoned
     :wrote-anyway wrote-anyway
     :ledger-breaks ledger-breaks
     :ok? (and (empty? mismatches)
               (empty? unreasoned)
               (empty? wrote-anyway)
               (empty? ledger-breaks)
               (pos? refusals))}))

(defn report
  "Human-readable run report. Pure: takes the result of `run`."
  [{:keys [results refusals mismatches unreasoned wrote-anyway ledger-breaks ok?]}]
  (str
   "netops.sim — governed scenario run\n"
   (apply str
          (for [r results]
            (str "  " (if (:match? r) "ok  " "BAD ")
                 (name (:name r))
                 " expect=" (name (:expect r))
                 " actual=" (name (:actual r))
                 (when (:refusal? r) " [refused]")
                 (cond
                   (not (:reasoned? r)) " NO-REASON-DECLARED"
                   (:because-ok? r) (if (:because r)
                                      (str " because=" (name (:because r)))
                                      " clean")
                   :else (str " WRONG-REASON want=" (pr-str (:because r))
                              " got=" (pr-str (:rules r))))
                 "\n")))
   "  scenarios=" (count results)
   " refusals=" refusals
   " mismatches=" (count mismatches)
   " unreasoned=" (count unreasoned)
   " wrote-anyway=" (count wrote-anyway)
   " ledger-breaks=" (count ledger-breaks)
   "\n"
   (cond
     (zero? refusals)
     "  REFUSING TO REPORT A PASS: the scenario table demonstrated no refusal.\n"
     ok? "  PASS\n"
     :else "  FAIL\n")))

#?(:clj
   (defn -main [& _]
     (let [r (run)]
       (print (report r))
       (flush)
       (System/exit (if (:ok? r) 0 1))))
   :cljs
   (defn -main [& _]
     (throw (ex-info "netops.sim/-main is :clj-only (process exit codes are a host concern); call `run` and inspect the result instead" {}))))
