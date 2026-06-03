(ns ehr-adapter.middleware.header
  (:require [ehr-adapter.error :as error]))

(def format->mime
  {:json "application/json"
   :fhir/json "application/fhir+json"
   :form-url-encoded "application/x-www-form-urlencoded"
   :xml "application/xml"
   :text "text/plain"
   :multipart "multipart/form-data"})

(defn content-type
  "Returns a map with {\"Content-Type\" k} with the mime code of k"
  [k]
  (when-not (nil? k)
    (if-let [mime (get format->mime k)]
      {"Content-Type" mime}
      (throw (error/info :invalid/format
                         {:message "Unsupported Content-Type value"
                          :scope :ehr-adapter.middleware.header
                          :operation :resolve-header
                          :value k
                          :expected (into [] (keys format->mime))})))))

(defn accept
  [k]
  (when-not (nil? k)
    (if-let [mime (get format->mime k)]
      {"Accept" mime}
      (throw (error/info :invalid/format
                         {:message "Unsupported Accept value"
                          :scope :ehr-adapter.middleware.header
                          :opeartion :resolve-header
                          :value k
                          :expected (into [] (keys format->mime))})))))
