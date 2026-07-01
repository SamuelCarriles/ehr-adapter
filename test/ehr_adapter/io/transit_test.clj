(ns ehr-adapter.io.transit-test
  (:require [clojure.test :refer [deftest is testing are]]
            [ehr-adapter.io.transit :as transit]))

;; =============================================================================
;; Roundtrip Tests (->string then <-string)
;; =============================================================================

(deftest roundtrip-test
  (testing "Scalars survive roundtrip"
    (are [x] (= x (transit/<-string (transit/->string x)))
      "hello"
      42
      3.14
      true
      false
      nil))

  (testing "Keywords survive roundtrip"
    (are [x] (= x (transit/<-string (transit/->string x)))
      :foo
      :ref/middlewares
      :ehr-adapter/domain))

  (testing "Collections survive roundtrip"
    (are [x] (= x (transit/<-string (transit/->string x)))
      [1 2 3]
      {:a 1 :b 2}
      #{:x :y :z}))

  (testing "Nested structures survive roundtrip"
    (let [data {:domain :eclinicalworks/tenant-alpha
                :base-url "https://fhir.ecw.com/v1/fhir"
                :network-config {:retries 3
                                 :retry-on [500 502 503]}
                :middlewares :ref/middlewares
                :auth {:initial [{:type :custom
                                  :handler :ref/auth.initial.0.handler}]}}]
      (is (= data (transit/<-string (transit/->string data))))))

  (testing "Empty collections survive roundtrip"
    (are [x] (= x (transit/<-string (transit/->string x)))
      {}
      []
      #{})))

;; =============================================================================
;; ->string Tests
;; =============================================================================

(deftest ->string-test
  (testing "Returns a non-empty string"
    (is (string? (transit/->string {:a 1})))
    (is (pos? (count (transit/->string {:name "test" :value 42}))))))

;; =============================================================================
;; <-string Tests
;; =============================================================================

(deftest <-string-test
  (testing "Reads back what ->string wrote"
    (let [original {:domain :epic/hospital
                    :operations [{:name :read-patient
                                  :path ["v1" "Patient" :ref/id]}]}]
      (is (= original (transit/<-string (transit/->string original)))))))