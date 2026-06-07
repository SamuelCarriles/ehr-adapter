(ns ehr-adapter.auth.core
  (:require [ehr-adapter.auth.strategy :as strategy]
            [ehr-adapter.reference :refer [resolve-refs]]))

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
  (select-keys (full-context context (dissoc layer :type)) [:token :token-type :refresh-token :expires-in]))

(defn process-layer
  "Prepares the authentication layer by resolving its dynamic bindings 
  against the context, merging the results, and then dispatches to the 
  corresponding authentication strategy execution."
  [context layer http-client]
  (let [layer-type (:type layer)
        full-ctx (full-context context (:bindings layer))
        ready-layer (resolve-refs full-ctx (dissoc layer :bindings))]
    (if (= :normalize layer-type)
      (normalize full-ctx ready-layer)
      (strategy/execute ready-layer http-client))))

