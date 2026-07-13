(ns netops.governor
  "NetworkSystemsGovernor — the independent safety/traceability layer
  for the ISCO-08 3513 community network-operations actor (itonami
  actor pattern, ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4311's bookkeeping.governor. Network-specific
  twist: CONNECTIVITY PRESERVATION — before any link removal is even
  eligible for approval, the governor recomputes reachability over the
  remaining topology (pure BFS over the registered nodes/links). A
  change that partitions the network is a graph fact; the advisor's
  'that link is redundant' claim is never trusted.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. topology basis    — every removed link must be REGISTERED and
                           belong to this client (no invented topology).
    4. connectivity      — after removing the cited links, every
                           registered node must remain reachable from
                           every other. A partition-inducing change is
                           held at any confidence; add the redundant
                           path first.
  ESCALATION invariants (:escalate? true, human sign-off):
    5. :op :apply-topology-change (real network mutation — always
       human, even when connectivity is preserved).
    6. low confidence (< `confidence-floor`)."
  (:require [netops.store :as store]))

(def confidence-floor 0.6)

(defn- connected-after-removal?
  "Pure reachability check: BFS over the client's registered nodes with
  `removed-ids` links excluded (and `added` links included, so a plan
  that removes a bridge while adding its replacement passes)."
  [store client-id removed-ids added]
  (let [node-ids (mapv :node-id (store/nodes-of store client-id))
        removed (set removed-ids)
        remaining (concat (remove #(contains? removed (:link-id %))
                                  (store/links-of store client-id))
                          added)
        adj (reduce (fn [m {:keys [a b]}]
                      (-> m (update a (fnil conj #{}) b)
                          (update b (fnil conj #{}) a)))
                    {} remaining)]
    (if (<= (count node-ids) 1)
      true
      (loop [seen #{(first node-ids)}
             frontier [(first node-ids)]]
        (if (empty? frontier)
          (= (count seen) (count node-ids))
          (let [nxt (mapcat #(remove seen (get adj % #{})) frontier)]
            (recur (into seen nxt) (vec (distinct nxt)))))))))

(defn- hard-violations [{:keys [request proposal]} client-record store]
  (let [{:keys [op remove-links add-links]} proposal
        apply? (= :apply-topology-change op)
        client-id (:client-id request)
        resolved (when apply? (mapv #(store/link store %) remove-links))
        unknown (when apply?
                  (keep-indexed (fn [i l] (when (nil? l) (nth remove-links i))) resolved))
        foreign (when apply?
                  (filter #(and (some? %) (not= (:client-id %) client-id)) resolved))]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and apply? (seq unknown))
      (conj {:rule :unknown-link
             :detail (str "未登録 link: " (vec unknown) "（トポロジの捏造禁止）")})

      (and apply? (seq foreign))
      (conj {:rule :link-wrong-client
             :detail (str "別 client の link: " (mapv :link-id foreign))})

      (and apply? (empty? unknown) (empty? foreign)
           (not (connected-after-removal? store client-id remove-links add-links)))
      (conj {:rule :partition-risk
             :detail "この変更は到達不能ノードを生む（切断を生む変更は承認不可 — 冗長路を先に足すこと）"}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `netops.store/Store`. Pure — never mutates the
  store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        hard (hard-violations {:request request :proposal proposal}
                              client-record store)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (= :apply-topology-change (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
