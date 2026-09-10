(ns netops.ledger-test
  (:require [clojure.test :refer [deftest is testing]]
            [netops.ledger :as ledger]))

(deftest chain-hash-is-deterministic
  (is (= (ledger/chain-hash 0 {:a 1}) (ledger/chain-hash 0 {:a 1})))
  (testing "the hash commits to the PREVIOUS hash, so position matters"
    (is (not= (ledger/chain-hash 0 {:a 1}) (ledger/chain-hash 7 {:a 1})))))

(deftest chain-hash-stays-in-range
  (testing "intermediate values must stay exactly representable so clj and
           cljs agree"
    (doseq [c [{:a 1} {:long (apply str (repeat 500 "x"))} {:nested {:b [1 2 3]}}]]
      (let [h (ledger/chain-hash 12345 c)]
        (is (<= 0 h))
        (is (< h 2147483647))
        (is (= h (long h)) "must be an integer")))))

(deftest append-builds-a-verifying-chain
  (let [l (-> [] (ledger/append {:x 1}) (ledger/append {:x 2}) (ledger/append {:x 3}))]
    (is (= [0 1 2] (mapv :ledger/seq l)))
    (is (= 0 (:ledger/prev (first l))))
    (is (= (:ledger/hash (nth l 0)) (:ledger/prev (nth l 1))))
    (is (:ok? (ledger/verify l)))
    (is (= 3 (:length (ledger/verify l))))))

(deftest verify-detects-a-tampered-entry
  (let [l (-> [] (ledger/append {:x 1}) (ledger/append {:x 2}))
        tampered (assoc-in (vec l) [1 :x] 99)
        r (ledger/verify tampered)]
    (is (not (:ok? r)))
    (is (= 1 (:broken-at r)))
    (is (= :hash-mismatch (:reason r)))))

(deftest verify-detects-a-reordered-ledger
  (let [l (-> [] (ledger/append {:x 1}) (ledger/append {:x 2}))
        r (ledger/verify (vec (reverse l)))]
    (is (not (:ok? r)))
    (is (= :seq-mismatch (:reason r)))))

(deftest verify-detects-an-unchained-entry
  (testing "this is the mutation `netops.sim` cannot catch: almost every
           scenario runs the graph once, and a single-entry ledger has prev 0
           legitimately"
    (let [first-e (ledger/entry [] {:x 1})
          ;; the second entry built as if the ledger were empty
          unchained (conj [first-e] (assoc (ledger/entry [] {:x 2}) :ledger/seq 1))
          r (ledger/verify unchained)]
      (is (not (:ok? r)))
      (is (= 1 (:broken-at r)))
      (is (= :prev-mismatch (:reason r))))))

(deftest a-truncated-ledger-verifies-and-that-is-stated
  (testing "a chain cannot detect entries it never saw; verify claims only
           what it can show"
    (let [l (-> [] (ledger/append {:x 1}) (ledger/append {:x 2}))]
      (is (:ok? (ledger/verify (vec (butlast l)))))
      (is (= 1 (:length (ledger/verify (vec (butlast l)))))))))

(deftest commit-and-hold-entries-keep-the-same-shape
  (testing "recording :actor explicitly rather than omitting the key stops a
           reader mistaking an absent field for an unaudited one"
    (let [c (ledger/commit-entry {:op :draft-change} :actor)
          h (ledger/hold-entry {:hard? true :violations []})]
      (is (= :commit (:disposition c)))
      (is (= :actor (:approved-by c)))
      (is (= :human (:approved-by (ledger/commit-entry {} :human))))
      (is (= :hold (:disposition h)))
      (is (contains? h :approved-by)))))

(deftest summary-names-the-approver
  (let [l (-> [] (ledger/append (ledger/commit-entry {:op :diagnose} :human)))]
    (is (re-find #"approved-by=human" (ledger/summary l)))))
