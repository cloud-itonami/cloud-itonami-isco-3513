(ns netops.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [netops.actor :as actor]
            [netops.ledger :as ledger]
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
  (testing "a draft that names its replacement link commits.

           This request used to omit `:add-links` entirely, which strands n1 --
           the connectivity check was gated on `:apply-topology-change`, so the
           partitioning draft committed a record. `netops.governor-test/
           hard-on-partitioning-draft` now pins the refusal; this test keeps
           the other half, that an admissible draft still writes."
    (let [st (line-store)
          graph (actor/build-graph {:store st})
          request {:client-id "client-1" :op :draft-change :stake :low
                   :remove-links ["l-12"]
                   :add-links [{:link-id "l-13" :a "n1" :b "n3"}]}
          result (actor/run-request! graph request {} "thread-1")]
      (is (= :done (:status result)))
      (is (some? (get-in result [:state :record]))))))

(deftest holds-a-partitioning-draft
  (testing "the graph, not only the pure check, refuses a draft that strands a node"
    (let [st (line-store)
          graph (actor/build-graph {:store st})
          request {:client-id "client-1" :op :draft-change :stake :low
                   :remove-links ["l-12"]}
          result (actor/run-request! graph request {} "thread-partition")]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "client-1"))))))

(deftest refuses-a-nil-op-instead-of-crashing
  (testing "a crash is not a refusal: it leaves no verdict and no ledger entry.
           `{:op nil}` reached `(name nil)` in the advisor and threw before the
           governor was consulted."
    (let [st (line-store)
          graph (actor/build-graph {:store st})
          result (actor/run-request! graph {:client-id "client-1" :op nil} {} "thread-nil")]
      (is (= :hold (:disposition (:state result))))
      (is (= 1 (count (store/ledger st)))))))

(deftest the-ledger-it-leaves-behind-verifies
  (testing "two runs on one store: the chain links the second entry to the
           first, which is what makes unchaining observable"
    (let [st (line-store)
          graph (actor/build-graph {:store st})]
      (actor/run-request! graph {:client-id "client-1" :op :draft-change :stake :low
                                 :remove-links ["l-12"]} {} "t-a")
      (actor/run-request! graph {:client-id "client-1" :op :diagnose :stake :low} {} "t-b")
      (let [l (store/ledger st)]
        (is (= 2 (count l)))
        (is (:ok? (ledger/verify l)))
        (is (= [0 1] (mapv :ledger/seq l)))))))

(deftest records-who-approved-each-write
  (testing "a human-approved application and an automatic draft must not leave
           indistinguishable ledger entries"
    (let [st (line-store)
          graph (actor/build-graph {:store st})]
      (actor/run-request! graph {:client-id "client-1" :op :draft-change :stake :low
                                 :remove-links ["l-12"]
                                 :add-links [{:link-id "l-13" :a "n1" :b "n3"}]}
                          {} "t-auto")
      (actor/run-request! graph {:client-id "client-1" :op :apply-topology-change :stake :high
                                 :remove-links ["l-12"]
                                 :add-links [{:link-id "l-13" :a "n1" :b "n3"}]}
                          {} "t-human")
      (actor/approve! graph "t-human")
      (is (= [:actor :human]
             (mapv :approved-by (filter #(= :commit (:disposition %)) (store/ledger st))))))))

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
