(ns netops.facts
  "Shape predicates and violation functions for the ISCO-08 3513 community
  network-operations actor: the questions the Governor asks, each one named,
  pure, and testable without building a graph.

  Runtime: portable `.cljc`. Every predicate here is written so that Clojure
  and ClojureScript agree on the answer, which is not automatic — see
  `usable-confidence?`.

  Why this namespace exists. The Governor's checks were an inline `cond->`
  over a proposal map, so each one could only be exercised by calling `check`
  and reading a violation list, and none of them had a name a test could hold.
  Three separate defects lived in that shape, all measured on the pre-change
  tree against a registered client with topology `N-A -- N-B -- N-C` (`L-BC`
  is the bridge that strands `N-C`):

  1. THE CONNECTIVITY PROOF TRUSTED THE ADVISOR. The governor's own docstring
     says the advisor's claim that a link is redundant `is never trusted`,
     because reachability is recomputed. But the recomputation folded the
     proposal's `:add-links` into the graph without checking that their
     endpoints were registered nodes:

         {:op :apply-topology-change
          :remove-links [\"L-BC\"]
          :add-links [{:link-id \"L-GHOST\" :a \"N-A\" :b \"N-C\"}]}
         => {:hard? false :escalate? true :violations []}

     The removal that strands `N-C` reached a human with an EMPTY violation
     list, because a link that does not exist and was never registered was
     accepted as the proof that connectivity survives. The advisor's `this is
     redundant` was indeed not trusted; its `I will add this` was trusted
     completely, and that is the same claim wearing the other sleeve.

  2. AN EMPTY NODE SET PROVED EVERYTHING. Reachability short-circuited on
     `(<= (count node-ids) 1)`. For a client with links registered but no
     nodes, that is vacuously true, so every removal passed the connectivity
     check:

         client C3, one registered link, ZERO registered nodes
         {:op :apply-topology-change :remove-links [\"L-1\"]}
         => {:hard? false :violations []}

     One node genuinely is connected; zero nodes is an unregistered topology
     being read as a proof. `connectivity-violations` separates the two.

  3. THE CONFIDENCE COMPARISON DISAGREED ACROSS HOSTS. `(< conf floor)` on a
     non-numeric confidence is a `ClassCastException` under Clojure and
     `false` under ClojureScript, where it compiles to a JavaScript
     string-versus-number test. Same `.cljc` file, same input, opposite
     outcomes — measured:

         clj:  {:confidence \"high\"} => THREW ClassCastException
         cljs: {:confidence \"high\"} => {:ok? true :hard? false :escalate? false}

     On the host this actor is portable to, an unreadable confidence bought a
     clean admission. And a confidence of `99.0` bought one on BOTH hosts,
     because the floor had no ceiling above it:

         {:op :draft-change :confidence 99.0} => {:ok? true :violations []}

     `usable-confidence?` tests `number?` FIRST, so the ordering comparison
     never sees a non-number on either host, and bounds the value on both
     sides. This is why the check is a named predicate and not an inline
     comparison: the bug was in the comparison's *shape*, and a shape is
     exactly what an inline expression does not let you name or test."
  (:require [clojure.string :as str]
            [netops.operation :as op]
            [netops.store :as store]))

(def confidence-floor
  "Below this, a proposal escalates to a human. It is a floor on a value that
  has already been established to be usable — `usable-confidence?` runs first,
  and an unusable confidence is a hard block rather than a low one, because
  escalating it would ask a human to sign off on a number that does not mean
  anything."
  0.6)

(defn identified?
  "True for a usable identifier: a non-blank string. Used for client, node and
  link ids. A `nil` id is what let the empty-map client land in the store under
  the `nil` key on the pre-change tree, where it then answered for a request
  that carried no client-id at all."
  [id]
  (and (string? id) (not (str/blank? id))))

(defn usable-confidence?
  "True when `c` is a number in [0.0, 1.0].

  `number?` is tested first on purpose. `(< \"high\" 0.6)` throws under Clojure
  and returns false under ClojureScript; ordering a value before establishing
  that it is a number is how one `.cljc` file produced opposite verdicts on two
  hosts. `(== c c)` rejects NaN, which is a number and orders with nothing —
  without it, `(<= 0.0 NaN)` is false, so NaN would be reported as an
  out-of-range value rather than as the unusable one it is. Both bounds are
  checked: a floor with no ceiling admitted `99.0`."
  [c]
  (and (number? c)
       (== c c)
       (<= 0.0 c)
       (<= c 1.0)))

(defn low-confidence?
  "True when a USABLE confidence is below the floor. Callers must establish
  `usable-confidence?` first; this predicate answers a question about a number."
  [c]
  (and (usable-confidence? c) (< c confidence-floor)))

(defn vocabulary-violations
  "The op must be named in `netops.operation`. An undeclared op is a
  vocabulary error; a reserved op is an authority boundary. They are reported
  as different rules so a refusal can say which one it is."
  [{:keys [op]}]
  (cond
    (not (op/declared? op))
    [{:rule :undeclared-op
      :detail (str "宣言されていない op: " (pr-str op)
                   "（netops.operation/supported にある op だけが提案できる）")}]

    (op/reserved? op)
    [{:rule :reserved-op
      :detail (str (pr-str op) " — " (op/reserved-reason op))}]

    :else []))

(defn provenance-violations
  "The request must name a client, and that client must be registered. Both
  halves are needed: on the pre-change tree a client registered as the empty
  map landed under the `nil` key, after which a request carrying no client-id
  resolved to it and passed provenance with an empty violation list."
  [store {:keys [client-id]}]
  (cond
    (not (identified? client-id))
    [{:rule :no-client-id
      :detail (str "request が client-id を持っていない: " (pr-str client-id))}]

    (nil? (store/client store client-id))
    [{:rule :no-client
      :detail (str "未登録 client: " (pr-str client-id))}]

    :else []))

(defn actuation-violations
  "The advisor proposes; it never writes. `:effect` must be `:propose`."
  [{:keys [effect]}]
  (if (= :propose effect)
    []
    [{:rule :no-actuation
      :detail (str "effect は :propose のみ許可（直接書込禁止）: " (pr-str effect))}]))

(defn confidence-violations
  "An unreadable or out-of-range confidence is a hard block, not a low one."
  [{:keys [confidence]}]
  (if (usable-confidence? confidence)
    []
    [{:rule :unusable-confidence
      :detail (str "confidence は 0.0〜1.0 の数でなければならない: " (pr-str confidence))}]))

(defn citation-violations
  "An operation that does not bind to topology must not cite topology. Without
  this, closing the basis checks on topology ops would simply move the hole to
  `:diagnose`, which has no gate — the same denylist mistake one op along."
  [{:keys [op remove-links add-links]}]
  (if (or (op/topology-op? op)
          (and (empty? remove-links) (empty? add-links)))
    []
    [{:rule :citation-without-topology-op
      :detail (str (pr-str op) " は topology を変更しない op なので link を挙げられない: "
                   "remove=" (pr-str (vec remove-links))
                   " add=" (pr-str (vec add-links)))}]))

(defn removal-basis-violations
  "Every cited link must be REGISTERED and belong to this client. No invented
  topology, and no reaching into another operator's network."
  [store client-id {:keys [remove-links]}]
  (let [resolved (mapv #(store/link store %) remove-links)
        unknown  (keep-indexed (fn [i l] (when (nil? l) (nth (vec remove-links) i))) resolved)
        foreign  (filter #(and (some? %) (not= (:client-id %) client-id)) resolved)]
    (cond-> []
      (seq unknown)
      (conj {:rule :unknown-link
             :detail (str "未登録 link: " (vec unknown) "（トポロジの捏造禁止）")})

      (seq foreign)
      (conj {:rule :link-wrong-client
             :detail (str "別 client の link: " (mapv :link-id foreign))}))))

(defn addition-basis-violations
  "Every ADDED link must join two nodes registered to this client. This is the
  half the pre-change tree was missing: the connectivity recomputation folded
  added links into the graph as fact, so a link between invented endpoints
  proved that a partitioning removal was safe."
  [store client-id {:keys [add-links]}]
  (let [known (into #{} (map :node-id) (store/nodes-of store client-id))
        bad-ends (for [l add-links
                       :let [ends (remove known [(:a l) (:b l)])]
                       :when (seq ends)]
                   {:link-id (:link-id l) :unknown-endpoints (vec ends)})
        self-loops (filterv #(= (:a %) (:b %)) add-links)]
    (cond-> []
      (seq bad-ends)
      (conj {:rule :unknown-node
             :detail (str "add-links の端点が未登録 node: " (vec bad-ends)
                          "（存在しない link を接続性の証明に使えない）")})

      (seq self-loops)
      (conj {:rule :self-loop-link
             :detail (str "自己ループは接続性を増やさない: " (mapv :link-id self-loops))}))))

(defn- reachable-count
  "BFS from `start` over `adj`, counting only nodes in `known`."
  [adj known start]
  (loop [seen #{start} frontier [start]]
    (if (empty? frontier)
      (count seen)
      (let [nxt (into [] (comp (mapcat #(get adj % #{}))
                               (filter known)
                               (remove seen)
                               (distinct))
                      frontier)]
        (recur (into seen nxt) nxt)))))

(defn connectivity-violations
  "After removing the cited links and adding the cited ones, every registered
  node of this client must remain reachable from every other.

  Callers must run `addition-basis-violations` first: this function folds
  `add-links` into the graph, and doing that with unvalidated endpoints is
  precisely the hole it otherwise closes. It therefore filters the adjacency to
  REGISTERED nodes as a second, independent guard.

  A client with zero registered nodes gets `:no-registered-topology`, not a
  pass. The pre-change short-circuit `(<= (count node-ids) 1)` treated an empty
  node set as a proof of connectivity, so a client whose topology was never
  registered had every removal admitted. A single registered node genuinely is
  connected; zero is an unanswered question, and the two must not return the
  same value."
  [store client-id {:keys [remove-links add-links]}]
  (let [nodes (mapv :node-id (store/nodes-of store client-id))
        known (set nodes)
        removed (set remove-links)
        remaining (concat (remove #(contains? removed (:link-id %))
                                  (store/links-of store client-id))
                          add-links)
        adj (reduce (fn [m {:keys [a b]}]
                      (if (and (known a) (known b))
                        (-> m (update a (fnil conj #{}) b)
                            (update b (fnil conj #{}) a))
                        m))
                    {} remaining)]
    (cond
      (empty? nodes)
      [{:rule :no-registered-topology
        :detail (str "client " (pr-str client-id)
                     " に登録された node が無い — 接続性は証明できない（空集合は証明ではない）")}]

      (= 1 (count nodes)) []

      (not= (count nodes) (reachable-count adj known (first nodes)))
      [{:rule :partition-risk
        :detail (str "この変更は到達不能ノードを生む（切断を生む変更は承認不可 — 冗長路を先に足すこと）"
                     " nodes=" (count nodes)
                     " reachable=" (reachable-count adj known (first nodes)))}]

      :else [])))
