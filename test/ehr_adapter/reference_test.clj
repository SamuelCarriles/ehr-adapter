(ns ehr-adapter.reference-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.reference :as ref]))

(deftest reference?-test
  (testing "Identifies valid required :ref/... keywords"
    (is (true? (ref/required-reference? :ref/patientId)))
    (is (true? (ref/required-reference? :ref/encounter-id))))

  (testing "Identifies valid optional :ref?/... keywords"
    (is (true? (ref/optional-reference? :ref?/patientId))))

  (testing "Combined reference? predicate works for both"
    (is (true? (ref/reference? :ref/id)))
    (is (true? (ref/reference? :ref?/id)))
    (is (false? (ref/reference? :normal/id))))
  ;; -----------------------------------------------------------

  (testing "Rejects keywords from other namespaces or without namespace"
    (is (false? (ref/required-reference? :patient/id)))
    (is (false? (ref/required-reference? :id)))
    (is (false? (ref/required-reference? :ref))))

  (testing "Rejects non-keyword types"
    (is (false? (ref/required-reference? "ref/patientId")))
    (is (false? (ref/required-reference? 123)))
    (is (false? (ref/required-reference? nil)))))

(deftest resolve-test
  (testing "Successful resolution of references"
    (let [bindings {:patientId "pat-991"
                    :encounterId "enc-004"
                    :status "active"}
          template {:resourceType "Observation"
                    :id :ref/patientId
                    :meta {:encounter :ref/encounterId}
                    :tags [:ref/status :static-tag]}]

      (is (= {:resourceType "Observation"
              :id "pat-991"
              :meta {:encounter "enc-004"}
              :tags ["active" :static-tag]}
             (ref/resolve bindings template)))))

  (testing "Leaves untouched keywords that are not in the :ref namespace"
    (let [bindings {:id "123"}
          template [:v1 :Patient :ref/id :some/other-key]]
      (is (= [:v1 :Patient "123" :some/other-key]
             (ref/resolve bindings template)))))

  (testing "Resolves optional references to nil when missing"
    (let [bindings {:id "123"}
          template {:id :ref/id :name :ref?/name}]
      (is (= {:id "123" :name nil}
             (ref/resolve bindings template)))))
  ;; -----------------------------------------------------------

  (testing "Throws ExceptionInfo when a required reference cannot be resolved"
    (let [bindings {:wrongKey "value"}
          template {:path ["v1" "Patient" :ref/missingId]}]

      (try
        (ref/resolve bindings template)
        (is false "Expected ExceptionInfo to be thrown due to missing binding")
        (catch clojure.lang.ExceptionInfo ex
          (let [ex-msg  (.getMessage ex)
                ex-meta (ex-data ex)]
            (is (= "The reference :ref/missingId can't be resolved" ex-msg))
            (is (= :invalid/reference (:code ex-meta)))
            (is (= template (get-in ex-meta [:details :context])))
            (is (= bindings (get-in ex-meta [:details :ref-bindings])))))))))

(deftest partial-resolve-test
  (testing "Resolves available references and leaves missing ones intact"
    (let [bindings {:base-url "https://api.example.com"
                    :client-id "my-client-123"}
          template {:base-url :ref/base-url
                    :auth {:client-id :ref/client-id
                           :secret :ref/secret}
                    :operations {:search {:path [:ref/tenant-id "/patients"]}}}
          expected {:base-url "https://api.example.com"
                    :auth {:client-id "my-client-123"
                           :secret :ref/secret}
                    :operations {:search {:path [:ref/tenant-id "/patients"]}}}]

      (is (= expected (ref/partial-resolve bindings template)))))

  (testing "Leaves missing optional references intact (does not turn them to nil)"
    (let [bindings {:id "123"}
          template {:id :ref/id :name :ref?/name :status :ref?/status}]
      (is (= {:id "123" :name :ref?/name :status :ref?/status}
             (ref/partial-resolve bindings template))))))
;; -----------------------------------------------------------

(deftest extract-references-test
  (testing "should return an empty set when no references are present"
    (is (= #{} (ref/extract {:path ["api" "v1" "Patient"]
                             :request {:headers {"Content-Type" "application/json"}
                                       :body {:active true}}}))))

  (testing "should extract required and optional references from any deep structure"
    (is (= #{:ref/patient-id :ref?/status :ref/room-id :ref?/format}
           (ref/extract {:path ["api" "v1" "Patient" :ref/patient-id]
                         :query-params {:status :ref?/status}
                         :request {:body {:room :ref/room-id
                                          :meta '(:ref?/format)}}}))))

  (testing "should avoid duplicates by leveraging a Set when the same reference is repeated"
    (is (= #{:ref/patient-id :ref?/active}
           (ref/extract {:path ["api" "v1" "Patient" :ref/patient-id]
                         :request {:body {:id :ref/patient-id
                                          :active :ref?/active
                                          :state :ref?/active}}}))))

  (testing "should keep original namespaces intact for downstream classification"
    (let [result (ref/extract {:id :ref/user-id :name :ref?/user-name})]
      (is (set? result))
      (is (contains? result :ref/user-id))
      (is (contains? result :ref?/user-name)))))
