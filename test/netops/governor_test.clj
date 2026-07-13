(ns netops.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [netops.store :as store]
            [netops.governor :as governor]))

(defn- triangle-store
  "Three nodes in a triangle: any single link is redundant."
  []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Machi Net"})
    (doseq [n ["n1" "n2" "n3"]]
      (store/register-node! st {:node-id n :client-id "client-1" :name n}))
    (store/register-link! st {:link-id "l-12" :client-id "client-1" :a "n1" :b "n2"})
    (store/register-link! st {:link-id "l-23" :client-id "client-1" :a "n2" :b "n3"})
    (store/register-link! st {:link-id "l-13" :client-id "client-1" :a "n1" :b "n3"})
    st))

(defn- line-store
  "Three nodes in a line: the middle links are bridges."
  []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Machi Net"})
    (doseq [n ["n1" "n2" "n3"]]
      (store/register-node! st {:node-id n :client-id "client-1" :name n}))
    (store/register-link! st {:link-id "l-12" :client-id "client-1" :a "n1" :b "n2"})
    (store/register-link! st {:link-id "l-23" :client-id "client-1" :a "n2" :b "n3"})
    st))

(defn- apply-change [remove-ids & [add]]
  {:op :apply-topology-change :effect :propose
   :remove-links remove-ids :add-links (vec add)
   :confidence 0.9 :stake :high})

(def ^:private req {:client-id "client-1"})

(deftest redundant-link-removal-escalates-only
  (testing "triangle minus one edge stays connected: no HARD, but
            applying still needs a human"
    (let [st (triangle-store)
          v (governor/check req {} (apply-change ["l-13"]) st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest hard-on-bridge-removal
  (testing "removing a bridge partitions the line: graph fact, held at
            any confidence"
    (let [st (line-store)
          v (governor/check req {} (assoc (apply-change ["l-12"])
                                          :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :partition-risk (:rule %)) (:violations v))))))

(deftest bridge-removal-with-replacement-passes
  (testing "removing a bridge WHILE adding the replacement path is fine"
    (let [st (line-store)
          v (governor/check req {} (apply-change ["l-12"]
                                                 [{:link-id "l-13" :a "n1" :b "n3"}]) st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest hard-on-unregistered-client
  (let [st (triangle-store)
        v (governor/check {:client-id "nobody"} {} (apply-change ["l-13"]) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (triangle-store)
        v (governor/check req {} (assoc (apply-change ["l-13"])
                                        :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest hard-on-invented-link
  (let [st (triangle-store)
        v (governor/check req {} (apply-change ["l-ghost"]) st)]
    (is (:hard? v))
    (is (some #(= :unknown-link (:rule %)) (:violations v)))))

(deftest hard-on-foreign-link
  (let [st (triangle-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (store/register-node! st {:node-id "x1" :client-id "client-2" :name "x1"})
    (store/register-node! st {:node-id "x2" :client-id "client-2" :name "x2"})
    (store/register-link! st {:link-id "l-x" :client-id "client-2" :a "x1" :b "x2"})
    (let [v (governor/check req {} (apply-change ["l-x"]) st)]
      (is (:hard? v))
      (is (some #(= :link-wrong-client (:rule %)) (:violations v))))))

(deftest draft-change-is-ok
  (let [st (line-store)
        v (governor/check req {} {:op :draft-change :effect :propose
                                  :remove-links ["l-12"]
                                  :confidence 0.9 :stake :low} st)]
    (is (:ok? v))))

(deftest escalates-low-confidence
  (let [st (triangle-store)
        v (governor/check req {} {:op :diagnose :effect :propose
                                  :confidence 0.3 :stake :low} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
