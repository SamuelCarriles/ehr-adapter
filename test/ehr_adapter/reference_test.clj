(ns ehr-adapter.reference-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.reference :as ref]))

(deftest reference?-test
  (testing "Identifies valid :ref/... keywords"
    (is (true? (ref/reference? :ref/patientId)))
    (is (true? (ref/reference? :ref/encounter-id))))

  (testing "Rejects keywords from other namespaces or without namespace"
    (is (false? (ref/reference? :patient/id)))
    (is (false? (ref/reference? :id)))
    (is (false? (ref/reference? :ref))))

  (testing "Rejects non-keyword types"
    (is (false? (ref/reference? "ref/patientId")))
    (is (false? (ref/reference? 123)))
    (is (false? (ref/reference? nil)))))

(deftest resolve-refs-test
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

  (testing "Throws ExceptionInfo when a reference cannot be resolved"
    (let [bindings {:wrongKey "value"}
          template {:path ["v1" "Patient" :ref/missingId]}]

      (try
        (ref/reference? (ref/resolve bindings template))
        (is false "Expected ExceptionInfo to be thrown due to missing binding")
        (catch clojure.lang.ExceptionInfo ex
          (let [ex-msg  (.getMessage ex)
                ex-meta (ex-data ex)]
            (is (= "The reference :ref/missingId can't be resolved" ex-msg))

            (is (= :invalid/reference (:code ex-meta)))
            (is (= template (get-in ex-meta [:details :context])))
            (is (= bindings (get-in ex-meta [:details :ref-bindings])))))))))
