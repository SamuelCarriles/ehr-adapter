(ns ehr-adapter.reference.core
  (:refer-clojure :exclude [resolve type])
  (:require
   [clojure.string :as str]
   [clojure.walk :refer [postwalk]]
   [ehr-adapter.reference.type :as ref-type]
   [ehr-adapter.error :as error]))

(def ^:private ref-regex #"^ref(\?|#.+|\?#.+)?$")

(defn reference?
  "Returns true if x is a reference keyword (e.g., :ref/id, :ref?/opt, :ref#int/num). Returns false otherwise."
  [x]
  (and (keyword? x)
       (if-some [ne (namespace x)]
         (some? (re-matches ref-regex ne))
         false)))

(defn parse
  "Parses a reference keyword into a map with :kind (:required or :optional), :referent (keyword), and optionally :type (keyword).
 Returns nil if k is not a valid reference."
  [k]
  (when (reference? k)
    (let [ns-str (namespace k)
          name-str (name k)
          [kind type] (str/split ns-str #"#")
          kind-key (case kind
                     "ref" :required
                     "ref?" :optional)]

      (cond-> {:kind kind-key :referent (keyword name-str)}
        (some? type)
        (assoc :type (keyword type))))))

(defn  required-reference?
  "Returns true if x is a required reference keyword (e.g., :ref/patientId, :ref#int/limit).
  Otherwise, returns false."
  [x]
  (= :required (:kind (parse x))))

(defn optional-reference?
  "Returns true if x is an optional reference keyword (e.g., :ref?/filter, :ref?#string/term). 
  Otherwise, returns false."
  [x]
  (= :optional (:kind (parse x))))

(defn referent
  "Returns the underlying target keyword (the referent) of a given reference 
   by stripping away its 'ref' or 'ref?' namespace.
   
   Example:
     (referent :ref/patientId)  ;; => :patientId
     (referent :ref?/patientId) ;; => :patientId
     (referent :plain-keyword)  ;; => nil"
  [ref]
  (:referent (parse ref)))

(defn validate-ref-bindings
  "Returns true when ref-bindings is a Clojure map, else throws :invalid/type error"
  [ref-bindings]
  (if (map? ref-bindings)
    ref-bindings
    (throw (error/info :invalid/type
                       {:message "ref-bindings must be a map"
                        :scope :ehr-adapter.reference
                        :operation :resolve-reference
                        :value ref-bindings
                        :expected [:map]}))))

(defn get-ref
  [ref-bindings ref]
  (let [ref-data (parse ref)
        value (get ref-bindings (:referent ref-data))
        full-ref-data (assoc ref-data :value value)]
    (if (some? (:type ref-data))
      (ref-type/validate full-ref-data)
      value)))

(defn resolve
  "Recursively traverses the given `form` (maps, vectors, lists, etc.) 
  and replaces any placeholder keyword matching the format :ref/variable-name 
  with its corresponding runtime value extracted from `ref-bindings`.

  Arguments:
    - `ref-bindings`: A map containing runtime dynamic data (e.g., {:patientId \"123\"}).
    - `form`: Any Clojure data structure or template that contains :ref/... keywords.

  Returns:
    The deeply transformed data structure with all references resolved.

  Throws:
    clojure.lang.ExceptionInfo if a required :ref/... keyword found in the form 
    cannot be resolved within the provided `ref-bindings` map. The exception 
    includes the full original context for deep production debugging."

  [ref-bindings x]
  (validate-ref-bindings ref-bindings)
  (postwalk
   (fn [v]
     (cond

       (required-reference? v)
       (if-some [resolved-ref (get-ref ref-bindings v)]
         resolved-ref
         (throw (error/info :invalid/reference
                            {:message (format "The reference %s can't be resolved" v)
                             :scope :ehr-adapter.reference
                             :operation :resolve-reference
                             :reference v
                             :context x
                             :ref-bindings ref-bindings})))
         ;;
       (optional-reference? v)
       (get-ref ref-bindings v)
         ;;
       :else
       v))
   x))

(defn partial-resolve
  [ref-bindings x]
  (validate-ref-bindings ref-bindings)
  (postwalk
   (fn [v]
     (cond
       (reference? v)
       (if-let [resolved-ref (get-ref ref-bindings v)]
         resolved-ref v)

       :else v))

   x))

(defn extract
  "Recursively traverses the given data structure `x` and collects all unique 
   reference keywords (e.g., :ref/variable or :ref?/variable).

   Returns a set containing all the encountered reference keywords intact, 
   preserving their original namespaces so they can be classified later.

   Example:
     (extract {:path [:room :ref/id] :request {:name :ref?/name}})
     ;; => #{:ref/id :ref?/name}"
  [x]
  (let [refs (transient #{})]
    (postwalk
     (fn [v]
       (when (reference? v)
         (conj! refs v))
       v)
     x)
    (persistent! refs)))

