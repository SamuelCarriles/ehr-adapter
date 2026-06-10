(ns ehr-adapter.auth.core
  (:require [ehr-adapter.auth.strategy :as strategy]
            [ehr-adapter.reference :refer [resolve]]
            [ehr-adapter.time :refer [now]]))

(defn bindings
  "Resolves a map of dynamic paths against the given context map.
  
  Takes a context map (`ctx`) and a map of bindings (`paths`), where each key
  represents the target parameter name and each value is a vector path (suitable
  for `get-in`) pointing to the location of the real value within `ctx`.

  Returns a new map containing only the successfully resolved key-value pairs.
  If a path does not exist in the context, it is safely ignored.

  Example:
    (bindings {:tenant {:id \"123\"}} {:client-id [:tenant :id]})
    => {:client-id \"123\"}"
  [ctx paths]
  (reduce-kv

   (fn [acc b p]
     (let [extract (if (vector? p) get-in get)
           v (extract ctx p)]
       (if (some? v)
         (assoc acc b v)
         acc)))

   {} paths))

(defn full-context
  [context bind-paths]
  (cond-> context
    (seq bind-paths)
    (merge (bindings context bind-paths))))

(defn normalize
  [context layer]
  (let [ctx (full-context context (dissoc layer :type))
        token-data (select-keys ctx [:token :token-type :refresh-token])

        expires-in (some-> ctx :expires-in str parse-long)]
    (cond-> token-data
      expires-in
      (assoc :expires-at (+ (now) expires-in)))))

(defn process-layer
  "Prepares the authentication layer by resolving its dynamic bindings 
  against the context, merging the results, and then dispatches to the 
  corresponding authentication strategy execution."
  [context layer http-client]
  (let [layer-type (:type layer)
        full-ctx (full-context context (:bindings layer))
        ready-layer (resolve full-ctx (dissoc layer :bindings))]
    (if (= :normalize layer-type)
      (normalize full-ctx ready-layer)
      (strategy/execute ready-layer http-client))))

(defn run
  "Sequentially executes a pipeline of authentication layers.

  Applies a `reduce` over `auth-layers`, passing the context map through
  each layer along with the `http-client`. The result of processing a layer
  becomes the input context for the next one.

  Returns the final, fully transformed context map."
  [initial-context auth-layers request-handler]
  (reduce
   (fn [context layer]
     (process-layer context layer request-handler))
   initial-context
   auth-layers))

(defn token-expired?
  [{:keys [expires-at]}]
  (and (some? expires-at)
       (>= (now) expires-at)))

(defn refresh
  [auth-layers request-handler]
  (fn [ctx]
    (run ctx auth-layers request-handler)))
