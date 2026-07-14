(ns ehr-adapter.reference.type
  (:refer-clojure :exclude [type])
  (:require [ehr-adapter.error :as error])
  (:import
   [java.util Date]
   [java.time Instant LocalDate LocalDateTime]))

(defn check [{:keys [value referent type]} validation-fn]
  (when (some? value)
    (if (validation-fn value)
      value
      (throw (error/info :invalid/type
                         {:message (format "The reference value for %s must be of type: %s" referent type)
                          :scope :ehr-adapter.reference.type
                          :operation :validate-reference-type
                          :value value
                          :expected type})))))

(defmulti validate (fn [m] (:type m)))

(defmethod validate :default
  [{:keys [type]}]
  (throw (error/info :unsupported/reference-type
                     {:message (format "The type %s is not supported. Implement a specific method of ehr-adapter.reference.type/validate for it or try with default supported types." type)
                      :scope :ehr-adapter.reference.type
                      :operation :validate-referent-value
                      :value type
                      :expected "un vector con los nombres de los tipos soportados, no sé si se puede acceder a todos los metodos de un multi para sacar las llaves que matchean"})))

(defmethod validate :integer
  [ref-data]
  (check ref-data integer?))

(defmethod validate :int
  [ref-data]
  (check ref-data int?))

(defmethod validate :pos-int
  [ref-data]
  (check ref-data pos-int?))

(defmethod validate :string
  [ref-data]
  (check ref-data string?))

(defmethod validate :keyword
  [ref-data]
  (check ref-data keyword?))

(defmethod validate :map
  [ref-data]
  (check ref-data map?))

(defmethod validate :vector
  [ref-data]
  (check ref-data vector?))

(defmethod validate :boolean
  [ref-data]
  (check ref-data boolean?))

(defmethod validate :uuid
  [ref-data]
  (check ref-data uuid?))

(defmethod validate :uuid-str
  [ref-data]
  (check ref-data #(uuid? (parse-uuid %))))

(defmethod validate :date
  [ref-data]
  (check ref-data #(instance? Date %)))

(defmethod validate :instant
  [ref-data]
  (check ref-data #(instance? Instant %)))

(defn instant-str? [^String s]
  (try
    (Instant/parse s)
    true
    (catch Exception _ false)))

(defmethod validate :instant-str
  [ref-data]
  (check ref-data instant-str?))

(defmethod validate :local-date
  [ref-data]
  (check ref-data #(instance? LocalDate %)))

(defn local-date-str? [^String s]
  (try
    (LocalDate/parse s)
    true
    (catch Exception _ false)))

(defmethod validate :local-date-str
  [ref-data]
  (check ref-data local-date-str?))

(defmethod validate :local-date-time
  [ref-data]
  (check ref-data #(instance? LocalDateTime %)))

(defn local-date-time-str? [^String s]
  (try
    (LocalDateTime/parse s)
    true
    (catch Exception _ false)))

(defmethod validate :local-date-time-str
  [ref-data]
  (check ref-data local-date-time-str?))


