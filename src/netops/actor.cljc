(ns netops.actor
  "NetworkSystemsActor — the ISCO-08 3513 community netops actor as a
  `langgraph.graph/state-graph` (ADR-2607011000 / CLAUDE.md Actors
  section). One graph run = one netops operation request
  (intake → advise → govern → decide → commit/hold, with a
  human-approval interrupt for escalated proposals). No infinite
  internal loop; checkpointed per superstep so an interrupted run can
  resume after human sign-off. Modeled on cloud-itonami-isco-2411's
  accounting.actor.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                             +-> :request-approval   (:escalate? true, interrupt-before)
                                             +-> :hold               (:hard? true)
  ```

  The unconditional invariant: the NetworkSystemsAdvisor can never
  directly commit a record the NetworkSystemsGovernor refuses —
  every commit-record! call is gated behind `:decide`.

  Routing is `netops.phase/of-verdict`, and every write appends a CHAINED
  `netops.ledger` entry recording whether the write was reached by a human
  resuming an escalated thread (`:approved-by :human`) or admitted
  automatically (`:actor`). On the pre-change tree both cases produced
  `{:disposition :commit :record ...}` with nothing to tell them apart, on the
  one actor whose interrupt exists precisely to make that difference."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [netops.advisor :as advisor]
            [netops.governor :as governor]
            [netops.ledger :as ledger]
            [netops.phase :as phase]
            [netops.store :as store]))

(defn- append-chained!
  "Append `m` to the store's ledger as the next CHAINED entry. Reads the
  current ledger so the new entry commits to both its content and its
  position. Returns the entry."
  [store m]
  (let [e (ledger/entry (vec (store/ledger store)) m)]
    (store/append-ledger! store e)
    e))

(defn build-graph
  "Build a compiled NetworkSystemsActor graph. `store` implements
  `netops.store/Store`. `advisor` implements
  `netops.advisor/Advisor` (defaults to `mock-advisor`).
  `checkpointer` defaults to an in-memory one."
  [{:keys [store advisor checkpointer]
    :or {advisor (advisor/mock-advisor)
         checkpointer (cp/mem-checkpointer)}}]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :audit       {:reducer into :default []}}})
      (g/add-node :intake (fn [s] s))
      (g/add-node :advise
                   (fn [{:keys [request]}]
                     (let [p (advisor/-advise advisor store request)]
                       {:proposal p
                        :audit [{:node :advise :request request :proposal p}]})))
      (g/add-node :govern
                   (fn [{:keys [request context proposal]}]
                     (let [v (governor/check request context proposal store)]
                       {:verdict v
                        :audit [{:node :govern :verdict v}]})))
      (g/add-node :decide
                   (fn [{:keys [verdict]}]
                     ;; The routing rule is `netops.phase/of-verdict`, not an
                     ;; inline cond, so it has a name a test and a ledger entry
                     ;; can both carry. It checks :hard? before :escalate?.
                     {:disposition (phase/of-verdict verdict)}))
      (g/add-node :request-approval (fn [s] s))
      (g/add-node :commit
                   (fn [{:keys [request proposal disposition]}]
                     (let [record {:client-id (:client-id request)
                                    :op (:op proposal)
                                    :remove-links (:remove-links proposal)
                                    :add-links (:add-links proposal)
                                    :payload proposal}
                           approved-by (if (phase/approved-commit? disposition)
                                         :human
                                         :actor)]
                       (store/commit-record! store record)
                       (append-chained! store (ledger/commit-entry record approved-by))
                       {:record record
                        :audit [{:node :commit :record record :approved-by approved-by}]})))
      (g/add-node :hold
                   (fn [{:keys [verdict]}]
                     (append-chained! store (ledger/hold-entry verdict))
                     {:audit [{:node :hold :verdict verdict}]}))
      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges
       :decide
       (fn [{:keys [disposition]}]
         (case disposition
           :commit :commit
           :request-approval :request-approval
           :hold)))
      (g/add-edge :request-approval :commit)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)
      (g/compile-graph {:checkpointer checkpointer
                         :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one operation request to completion or interrupt. `thread-id`
  scopes checkpointing for resume after human approval."
  [graph request context thread-id]
  (g/run* graph {:request request :context context} {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: the interrupted `:request-approval` node
  advances straight to `:commit` on resume (approval is the act of
  resuming the thread)."
  [graph thread-id]
  (g/run* graph nil {:thread-id thread-id :resume? true}))
