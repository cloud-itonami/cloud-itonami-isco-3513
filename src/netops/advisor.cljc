(ns netops.advisor
  "NetworkSystemsAdvisor — proposes a network operation (draft a
  topology change, apply a topology change, diagnose) for a registered
  organization. Swappable mock/llm; the advisor ONLY proposes —
  `netops.governor` recomputes connectivity independently. Modeled on
  cloud-itonami-isco-4311's advisor.

  A proposal: {:op :draft-change|:apply-topology-change|:diagnose
               :effect :propose
               :remove-links [link-id ...]
               :add-links [{:link-id str :a str :b str} ...]
               :stake kw :confidence n :rationale str}")

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake remove-links add-links] :as request}]
  {:op op
   :effect :propose
   :remove-links (vec remove-links)
   :add-links (vec add-links)
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a network operations advisor. Given a request, propose an
   :op, the :remove-links/:add-links, an honest :confidence and a
   :stake. Never claim a link is redundant — the governor recomputes
   reachability.")

(defn- parse-proposal [content]
  (try
    (let [p (read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
