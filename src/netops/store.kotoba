(ns netops.store
  "SSoT for the ISCO-08 3513 community network-operations actor
  (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors section).
  Modeled on cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client — a registered organization (:client-id, :name)
    node   — a registered network node {:node-id :client-id :name}
    link   — a registered undirected link {:link-id :client-id :a :b}
             (:a/:b are node-ids). Nodes + links are the topology SSoT
             the governor recomputes reachability against.
    record — a committed operating record (change draft, applied
             topology change, diagnosis) — written ONLY via
             commit-record!.
    ledger — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (nodes-of [s client-id])
  (links-of [s client-id])
  (link [s link-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-node! [s n])
  (register-link! [s l])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (nodes-of [_ client-id] (filter #(= client-id (:client-id %)) (:nodes @a)))
  (links-of [_ client-id] (filter #(= client-id (:client-id %)) (:links @a)))
  (link [_ link-id] (first (filter #(= link-id (:link-id %)) (:links @a))))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-node! [s n]
    (swap! a update :nodes (fnil conj []) n) s)
  (register-link! [s l]
    (swap! a update :links (fnil conj []) l) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :nodes [] :links []
                                    :records [] :ledger []}
                                   seed)))))
