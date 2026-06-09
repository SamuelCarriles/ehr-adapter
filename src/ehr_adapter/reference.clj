(ns ehr-adapter.reference
  (:refer-clojure :exclude [resolve])
  (:require [clojure.walk :refer [postwalk]]
            [ehr-adapter.error :as error]))

(defn reference?
  "Returns true if x is a keyword with the namespace 'ref' 
  (e.g., :ref/patientId). Otherwise, returns false."
  [x]
  (and (keyword? x)
       (= "ref" (namespace x))))

(defn optional-reference?
  [x]
  (and (keyword? x)
       (= "ref?" (namespace x))))

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
  (when (not (map? ref-bindings))
    (throw (error/info :invalid/type
                       {:message "ref-bindings must be a map"
                        :scope :ehr-adapter.reference
                        :operation :resolve-reference
                        :expected [:map]})))
  (postwalk
   (fn [v]
     (letfn [(get-ref [r] (->> r name keyword (get ref-bindings)))]

       (cond

         (reference? v)
         (if-some [resolved-ref (get-ref v)]
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
         (get-ref v)
         ;;
         :else
         v)))
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
  (let [refs (atom #{})]
    (postwalk
     (fn [v]
       (when (or (reference? v) (optional-reference? v))
         (swap! refs conj v))
       v)
     x)
    @refs))

