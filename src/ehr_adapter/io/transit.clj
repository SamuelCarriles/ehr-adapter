(ns ehr-adapter.io.transit
  (:require [cognitect.transit :as transit])
  (:import [java.io ByteArrayOutputStream ByteArrayInputStream]))

(defn ->string
  "Serializes a Clojure value to a transit+json string."
  [x]
  (let [out (ByteArrayOutputStream. 4096)
        writer (transit/writer out :json)]
    (transit/write writer x)
    (.toString out)))

(defn <-string
  "Reads a transit+json string back into a Clojure value."
  [^String s]
  (let [in (ByteArrayInputStream. (.getBytes s))
        reader (transit/reader in :json)]
    (transit/read reader)))