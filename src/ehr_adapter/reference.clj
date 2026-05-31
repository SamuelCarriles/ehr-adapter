(ns ehr-adapter.reference
  (:require [clojure.walk :refer [postwalk]]))

(defn reference?
  "Returns true if x is a keyword with the namespace 'ref' 
  (e.g., :ref/patientId). Otherwise, returns false."
  [x]
  (and (keyword? x)
       (= "ref" (namespace x))))

(defn resolve-refs
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

  [ref-bindings form]
  (postwalk
   (fn [x]
     (if (reference? x)
       (let [k (keyword (name x))]
         (if (contains? ref-bindings k)
           (get ref-bindings k)
           (throw (ex-info (format "The reference %s can't be resolved" x)
                           {:type :invalid/reference
                            :details {:context form
                                      :ref-bindings ref-bindings}}))))
       x))
   form))


