(ns ehr-adapter.reference.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.reference.core :as ref]))

(deftest reference?-test
  (testing "Identifies valid required :ref/... keywords"
    (is (true? (ref/required-reference? :ref/patientId)))
    (is (true? (ref/required-reference? :ref/encounter-id))))

  (testing "Identifies valid optional :ref?/... keywords"
    (is (true? (ref/optional-reference? :ref?/patientId))))

  (testing "Combined reference? predicate works for both"
    (is (true? (ref/reference? :ref/id)))
    (is (true? (ref/reference? :ref?/id)))
    (is (false? (ref/reference? :normal/id)))
    (is (true? (ref/reference? :ref#pos-int/age)))
    (is (false? (ref/reference? :ref#/age)))
    (is (true? (ref/reference? :ref?#string/code)))
    (is (false? (ref/reference? :ref?#/code))))

  (testing "Rejects keywords from other namespaces or without namespace"
    (is (false? (ref/required-reference? :patient/id)))
    (is (false? (ref/required-reference? :id)))
    (is (false? (ref/required-reference? :ref))))

  (testing "Rejects non-keyword types"
    (is (false? (ref/required-reference? "ref/patientId")))
    (is (false? (ref/required-reference? 123)))
    (is (false? (ref/required-reference? nil)))))

(deftest get-ref-test
  (testing "Resolves and validates a typed required reference successfully"
    (is (= 42 (ref/get-ref {:age 42} :ref#pos-int/age)))
    (is (= "active" (ref/get-ref {:status "active"} :ref#string/status))))

  (testing "Throws :invalid/type when typed reference value does not match the type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :pos-int"
                          (ref/get-ref {:age "forty-two"} :ref#pos-int/age))))

  (testing "Returns nil for missing optional typed reference without throwing"
    (is (nil? (ref/get-ref {} :ref?#string/name)))))

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
            (is (= bindings (get-in ex-meta [:details :ref-bindings]))))))))

  (testing "Resolves typed references correctly within nested structures"
    (let [bindings {:age 30 :active true}
          template {:patient {:age :ref#pos-int/age
                              :active :ref#boolean/active}}]
      (is (= {:patient {:age 30 :active true}}
             (ref/resolve bindings template)))))

  (testing "Throws :invalid/type when a required typed reference has the wrong type during resolve"
    (let [bindings {:age "thirty"}
          template {:patient {:age :ref#pos-int/age}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"must be of type: :pos-int"
                            (ref/resolve bindings template))))))

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
             (ref/partial-resolve bindings template)))))

  (testing "Resolves valid typed references and leaves missing optional typed references intact"
    (let [bindings {:age 25}
          template {:age :ref#pos-int/age :name :ref?#string/name}]
      (is (= {:age 25 :name :ref?#string/name}
             (ref/partial-resolve bindings template)))))

  (testing "Leaves missing required typed references intact in partial-resolve"
    (let [bindings {}
          template {:id :ref#pos-int/id}]
      (is (= {:id :ref#pos-int/id}
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
      (is (contains? result :ref?/user-name))))

  (testing "Extracts typed references (required and optional) correctly"
    (is (= #{:ref#pos-int/age :ref?#string/name :ref/uuid}
           (ref/extract {:age :ref#pos-int/age
                         :name :ref?#string/name
                         :id :ref/uuid})))))

(deftest check-reference-coherence-test
  (testing "Returns x when all referents are unique"
    (let [x {:path [:ref/patient-id]
             :query {:name :ref?/name}
             :body {:age :ref#pos-int/age}}]
      (is (= x (ref/check x)))))

  (testing "Throws when same referent appears with different kind (required vs optional)"
    (let [x {:path [:ref/patient-id]
             :query {:id :ref?/patient-id}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"The referent :patient-id appears with different specs"
                            (ref/check x)))))

  (testing "Throws when same referent appears with different types"
    (let [x {:path [:ref#int/patient-id]
             :query {:id :ref#string/patient-id}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"The referent :patient-id appears with different specs"
                            (ref/check x)))))

  (testing "Throws when same referent appears with different kind and type"
    (let [x {:path [:ref#int/patient-id]
             :query {:id :ref?#string/patient-id}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"The referent :patient-id appears with different specs"
                            (ref/check x)))))

  (testing "Error includes context with all conflicting references"
    (let [x {:path [:ref#int/patient-id]
             :query {:id :ref?#string/patient-id}}]
      (try
        (ref/check x)
        (is false "Expected exception")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :patient-id (get-in data [:details :reference])))
            (is (= #{:ref#int/patient-id :ref?#string/patient-id}
                   (set (get-in data [:details :context])))))))))

  (testing "Allows identical references (set deduplication)"
    (let [x {:path [:ref/patient-id]
             :body {:id :ref/patient-id}}]
      (is (= x (ref/check x)))))

  (testing "Returns x for empty structure"
    (is (= {} (ref/check {}))))

  (testing "Returns x when no references present"
    (let [x {:path ["api" "v1" "Patient"]
             :body {:name "John"}}]
      (is (= x (ref/check x))))))
