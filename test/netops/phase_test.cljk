(ns netops.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [netops.phase :as phase]))

(deftest hard-beats-escalate
  (testing "a verdict carrying BOTH flags must hold. Escalating it would put a
           question to a human that they have no authority to answer yes to.

           This is the mutation `netops.sim` cannot catch: the governor never
           emits both flags today, so reversing the two clauses of `of-verdict`
           leaves the whole scenario table green. That is exactly why this
           assertion is here and not there."
    (is (= :hold (phase/of-verdict {:hard? true :escalate? true})))
    (is (= :hold (phase/of-verdict {:hard? true})))
    (is (= :request-approval (phase/of-verdict {:escalate? true})))
    (is (= :commit (phase/of-verdict {})))
    (is (= :commit (phase/of-verdict {:hard? false :escalate? false})))))

(deftest only-commit-writes
  (is (phase/writes? :commit))
  (is (not (phase/writes? :hold)))
  (testing "an escalation has NOT written yet -- it is a refusal to act without
           a human, not an approval in waiting"
    (is (not (phase/writes? :request-approval)))
    (is (phase/refusal? :request-approval))
    (is (phase/refusal? :hold))
    (is (not (phase/refusal? :commit)))))

(deftest an-unknown-phase-does-not-write
  (testing "get-in on an absent phase returns nil, and nil must mean no"
    (is (not (phase/writes? :not-a-phase)))
    (is (not (phase/human-required? :not-a-phase)))
    (is (not (phase/terminal? :not-a-phase)))))

(deftest approval-provenance
  (is (phase/approved-commit? :request-approval))
  (is (not (phase/approved-commit? :commit)))
  (is (not (phase/approved-commit? nil))))

(deftest escalating-op-is-the-operation-vocabulary
  (is (phase/escalating-op? :apply-topology-change))
  (is (not (phase/escalating-op? :draft-change))))
