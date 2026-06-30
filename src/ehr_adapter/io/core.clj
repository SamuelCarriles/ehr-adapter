(ns ehr-adapter.io.core
  (:require [clojure.string :as str]))

(defn path->ref
  "Builds a :ref keyword from a sequence of keys/indices representing
   a location inside the configuration map.
   Args:
   - path : A vector of keywords and/or integers (e.g. [:auth :initial 0 :handler]).
   Returns:
   A namespaced :ref/... keyword joining the path segments with dots,
   or nil if path is empty."
  [path]
  (when (seq path)
    (->> path
         (map #(if (keyword? %) (name %) %))
         (str/join ".")
         (keyword "ref"))))

(defn walk
  "Recursively walks a value, replacing any function found with the
   :ref keyword corresponding to its location in the structure.
   Args:
   - x    : The value to walk (map, vector, function, or scalar).
   - path : The path accumulated so far to reach x.
   Returns:
   x unchanged if it has no functions inside; a :ref keyword if x itself
   is a function; or a structurally-equal map/vector with every nested
   function replaced by its :ref."

  [x path]
  (cond
    (fn? x)
    (path->ref path)

    (map? x)
    (reduce-kv (fn [acc k v]
                 (assoc acc k (walk v (conj path k)))) {} x)

    (vector? x)
    (vec (map-indexed #(walk %2 (conj path %1)) x))

    :else
    x))

(defn ->serializable
  "Transforms an adapter configuration into a serializable form, replacing
   every function it contains with a :ref/... keyword the developer must
   resupply when reimporting the configuration.
   :middlewares is replaced as a single whole :ref (not walked into); :auth
   and :network-config are walked recursively so every nested handler gets
   its own indexed :ref.
   Args:
   - config : An adapter configuration map (as accepted by ehr-adapter.schema/AdapterConfiguration).
   Returns:
   A new configuration map with the same shape, safe to serialize to
   EDN or transit+json."
  [config]
  (cond-> config
    (:middlewares config)
    (assoc :middlewares :ref/middlewares)

    (:auth config)
    (update :auth walk [:auth])

    (:network-config config)
    (update :network-config walk [:network-config])))
