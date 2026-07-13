(ns netops.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [netops.actor :as actor]
            [netops.store :as store]))

(defn- line-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Machi Net"})
    (doseq [n ["n1" "n2" "n3"]]
      (store/register-node! st {:node-id n :client-id "client-1" :name n}))
    (store/register-link! st {:link-id "l-12" :client-id "client-1" :a "n1" :b "n2"})
    (store/register-link! st {:link-id "l-23" :client-id "client-1" :a "n2" :b "n3"})
    st))

(deftest commits-a-draft-change
  (let [st (line-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :draft-change :stake :low
                 :remove-links ["l-12"]}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))))

(deftest holds-a-partitioning-apply
  (let [st (line-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :apply-topology-change :stake :high
                 :remove-links ["l-12"]}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-applies-safe-change-on-human-approval
  (let [st (line-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :apply-topology-change :stake :high
                 :remove-links ["l-12"]
                 :add-links [{:link-id "l-13" :a "n1" :b "n3"}]}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
