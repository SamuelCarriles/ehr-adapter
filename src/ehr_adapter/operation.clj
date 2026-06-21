(ns ehr-adapter.operation
  (:refer-clojure :exclude [compile])
  (:require
   [clojure.string :as str]
   [clojure.walk :refer [postwalk]]
   [clojure.set :refer [difference]]
   [ehr-adapter.schema :as schema]
   [ehr-adapter.reference :as ref]))

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

(defn path->str
  "Resolves any dynamic references inside the path vector using the provided 
   context (ctx) and joins the resulting segments into a URL path string."
  [ctx path]
  (->> path
       (ref/resolve ctx)
       (str/join "/")))

(defn full-url
  "Constructs a complete URL by resolving the path against the context (ctx) 
   and appending it to the base-url found within the context."
  [ctx path]
  (let [base-url (:ehr-adapter/base-url ctx)
        path (if (string? path) path (path->str ctx path))]
    (str base-url "/" path)))

(defn clasify-ref-keys
  "Groups a collection of reference keywords into a map of :required-keys 
   and :optional-keys based on their namespace. If a key is marked as both 
   required and optional, requirement takes priority."
  [refs]
  (let [keys-group (group-by ref/required-reference? refs)
        req-k (->> (get keys-group true) (map ref/referent) set)
        opt-k (->> (get keys-group false) (map ref/referent) set)
        filter-opt-k (difference opt-k req-k)]
    (cond-> {:required-keys req-k}
      (seq filter-opt-k)
      (assoc :optional-keys filter-opt-k))))

(defn compile
  "Compiles an operation map into an executable closure mapped to the 
   operation's name. The resulting map also exposes the required and 
   optional referent keys needed for execution.
   
   The compiled function expects a runtime context map and a request handler."
  [{:keys [path method auth? request description] :as op :or {auth? true}}]
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
    (let [op-name (:name op)
          ref-keys (clasify-ref-keys (ref/extract op))
          op-map (cond-> (merge {:handler operation :auth? auth?} ref-keys)
                   description
                   (assoc :description description))]
      {op-name op-map})))



