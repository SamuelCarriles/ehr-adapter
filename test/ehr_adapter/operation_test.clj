(ns ehr-adapter.operation-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.operation :as op]))

(deftest test-path->str
  (testing "Successful path resolution with valid required references"
    (let [ctx {:patientId "pat-123" :encounterId "enc-456"}]
      (is (= "v1/Patient/pat-123/Encounter/enc-456"
             (op/path->str ctx ["v1" "Patient" :ref/patientId "Encounter" :ref/encounterId])))))

  (testing "Throws ExceptionInfo when a required reference is missing from context"
    (let [ctx {:encounterId "enc-456"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (op/path->str ctx ["v1" "Patient" :ref/patientId]))))))

(deftest test-full-url
  (testing "Constructs full URL using a static string path"
    (let [ctx {:base-url "https://api.advancedmd.com"}]
      (is (= "https://api.advancedmd.com/v1/ping"
             (op/full-url ctx "v1/ping")))))

  (testing "Constructs full URL using a dynamic vector path"
    (let [ctx {:base-url "https://api.advancedmd.com" :patientId "999"}]
      (is (= "https://api.advancedmd.com/v1/Patient/999"
             (op/full-url ctx ["v1" "Patient" :ref/patientId]))))))

(deftest test-clasify-ref-keys
  (testing "Basic classification of required and optional keys"
    (let [refs #{:ref/patientId :ref?/facilityId :ref?/appointmentId}]
      (is (= {:required-keys #{:patientId}
              :optional-keys #{:facilityId :appointmentId}}
             (op/clasify-ref-keys refs)))))

  (testing "Requirement takes precedence when the same key is both required and optional"
    (let [refs #{:ref/patientId :ref?/patientId :ref?/facilityId}]
      (is (= {:required-keys #{:patientId}
              :optional-keys #{:facilityId}}
             (op/clasify-ref-keys refs)))))

  (testing "Excludes :optional-keys key entirely from the map if no optional keys remain"
    (let [refs #{:ref/patientId}]
      (is (= {:required-keys #{:patientId}}
             (op/clasify-ref-keys refs))))))

(deftest test-compile-and-execution
  (let [op-spec {:name :get-patient-history
                 :description "Fetch patient clinical history"
                 :method :get
                 :path ["v1" "Patient" :ref/patientId "History"]
                 :expected-status #{200}
                 :request {:headers {"X-Static-Header" "static"}
                           :content-type :json}}
        compiled (op/compile op-spec)
        operation-fn (get-in compiled [:get-patient-history :fn])]

    (testing "Compiler output metadata verification"
      (is (fn? operation-fn))
      (is (= "Fetch patient clinical history" (get-in compiled [:get-patient-history :description])))
      (is (= #{:patientId} (get-in compiled [:get-patient-history :required-keys]))))

    (testing "Closure execution: map merging, reference resolution, and deep nil removal"
      (let [ctx {:base-url "https://api.ehr.com"
                 :patientId "123"
                 :request {:headers {"Authorization" "Bearer TOKEN"
                                     "X-Static-Header" "overridden-value"}
                           :query-params {:filter :ref?/filter-val
                                          :empty-param :ref?/missing-val
                                          :status "active"}}}
            capture-handler (fn [final-req] final-req)
            ctx-with-filter (assoc ctx :filter-val "all-records")
            result (operation-fn ctx-with-filter capture-handler)]

        (is (= "https://api.ehr.com/v1/Patient/123/History" (:url result)))
        (is (= :get (:method result)))
        (is (= #{200} (:expected-status result)))

        (is (= "Bearer TOKEN" (get-in result [:headers "Authorization"])))
        (is (= "overridden-value" (get-in result [:headers "X-Static-Header"])))

        (is (= "all-records" (get-in result [:query-params :filter])))
        (is (= "active" (get-in result [:query-params :status])))

        (is (not (contains? (:query-params result) :empty-param)))))))
