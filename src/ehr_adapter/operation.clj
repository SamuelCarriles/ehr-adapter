(ns ehr-adapter.operation
  (:refer-clojure :exclude [compile])
  (:require
   [clojure.string :as str]
   [clojure.walk :refer [postwalk]]
   [clojure.set :refer [difference rename-keys]]
   [ehr-adapter.schema :as schema]
   [ehr-adapter.reference.core :as ref]))

(defn- deep-merge
  "Recursively merges multiple maps. If a key collision occurs and both 
   values are maps, they are merged recursively; otherwise, the rightmost 
   value overwrites the leftmost one."
  [& maps]
  (letfn [(merge-fn [v1 v2]
            (if (and (map? v1) (map? v2))
              (deep-merge v1 v2)
              v2))]
    (apply merge-with merge-fn maps)))

(defn- clean-nil
  "Deeply traverses the data structure x and removes all keys with nil values 
   from maps, and all nil elements from vectors and sequences."
  [x]
  (postwalk

   (fn [v]
     (cond

       (map-entry? v) v

       (map? v)
       (->> v (remove (fn [[_ value]] (nil? value))) (into {}))

       (vector? v)
       (->> v (remove nil?) (into []))

       (seq? v)
       (->> v (remove nil?) seq)

       :else
       v))

   x))

(defn ->path
  [x]
  (cond
    (string? x) [x]
    (nil? x) []
    :else x))

(defn full-url
  "Constructs a complete URL by resolving the path against the context (ctx) 
   and appending it to the base-url found within the context."
  [ctx path]
  (let [base-url (:ehr-adapter/base-url ctx)
        path (->path path)
        resolved-path (ref/resolve ctx path)]
    (str/join "/" (into [base-url] resolved-path))))

(defn classify-ref-keys
  "Groups a collection of reference keywords into a map of :required-keys 
   and :optional-keys based on their namespace. If a key is marked as both 
   required and optional, requirement takes priority."
  [refs]
  (let [keys-group (group-by ref/required-reference? refs)
        get-data #(-> %
                      ref/parse
                      (select-keys [:referent :type])
                      (rename-keys {:referent :key}))
        data-set #(set (map get-data %))
        req-k (->> (get keys-group true) data-set)
        opt-k (->> (get keys-group false) data-set)
        filter-opt-k (difference opt-k req-k)]
    (cond-> {:required-keys req-k}
      (seq filter-opt-k)
      (assoc :optional-keys filter-opt-k))))

(defn flatten-operations
  "Flattens nested operation groups into a single vector with fully resolved paths."
  [operations]
  (letfn [(flatter [ops prefix-acc]
            (mapcat (fn [op]
                      (if (and (:prefix op)
                               (:operations op))
                        (let [new-prefix (into prefix-acc (->path (:prefix op)))]
                          (flatter (:operations op) new-prefix))
                        [(assoc op :path (into prefix-acc (->path (:path op))))]))
                    ops))]
    (vec (flatter operations []))))

(defn ->transformer [trs]
  (apply comp (reverse trs)))

(defn compile
  "Compiles an operation map into an executable closure mapped to the 
   operation's name. The resulting map also exposes the required and 
   optional referent keys needed for execution.
   
   The compiled function expects a runtime context map and a request handler."
  [{:keys [path method auth? request transformers description] :as op :or {auth? true}}]
  (letfn [(operation [ctx req-handler]
            (let [full-url (->> path
                                (full-url ctx)
                                schema/validate-url)

                  new-req (:request ctx)

                  req (cond-> {:url full-url :method method}
                        request
                        (merge request)

                        new-req
                        (deep-merge new-req))]
              (->> req
                   (ref/resolve ctx)
                   clean-nil
                   req-handler)))]

    (let [{:keys [in out]} transformers
          op-name (:name op)
          ref-keys (classify-ref-keys (ref/extract op))
          op-map (cond-> (merge {:handler operation :auth? auth?} ref-keys)

                   (seq in)
                   (assoc-in [:transformers :in] (->transformer in))

                   (seq out)
                   (assoc-in [:transformers :out] (->transformer out))
                   description
                   (assoc :description description))]
      {op-name op-map})))



