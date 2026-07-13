(ns ehr-adapter.operation-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.operation :as op]
            [clojure.string :as str]))

(deftest test-full-url
  (testing "Constructs full URL using a static string path"
    (let [ctx {:ehr-adapter/base-url "https://api.advancedmd.com"}]
      (is (= "https://api.advancedmd.com/v1/ping"
             (op/full-url ctx "v1/ping")))))

  (testing "Constructs full URL using a dynamic vector path"
    (let [ctx {:ehr-adapter/base-url "https://api.advancedmd.com" :patientId "999"}]
      (is (= "https://api.advancedmd.com/v1/Patient/999"
             (op/full-url ctx ["v1" "Patient" :ref/patientId])))))

  (testing "Constructs full URL when path is nil (returns base-url only)"
    (let [ctx {:ehr-adapter/base-url "https://api.advancedmd.com"}]
      (is (= "https://api.advancedmd.com"
             (op/full-url ctx nil))))))

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
        operation-fn (get-in compiled [:get-patient-history :handler])]

    (testing "Compiler output metadata verification"
      (is (fn? operation-fn))
      (is (= "Fetch patient clinical history" (get-in compiled [:get-patient-history :description])))
      (is (= #{:patientId} (get-in compiled [:get-patient-history :required-keys]))))

    (testing "Throws ExceptionInfo when the generated URL is invalid"
      (let [op-spec {:name :bad-url-op
                     :method :get
                     :path ["v1" "Patient"]}
            compiled (op/compile op-spec)
            operation-fn (get-in compiled [:bad-url-op :handler])
            ctx {:base-url "not-a-valid-url"}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"The given URL is not valid"
                              (operation-fn ctx (fn [req] req))))))

    (testing "Closure execution: map merging, reference resolution, and deep nil removal"
      (let [ctx {:ehr-adapter/base-url "https://api.ehr.com"
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

        (is (= "Bearer TOKEN" (get-in result [:headers "Authorization"])))
        (is (= "overridden-value" (get-in result [:headers "X-Static-Header"])))

        (is (= "all-records" (get-in result [:query-params :filter])))
        (is (= "active" (get-in result [:query-params :status])))

        (is (not (contains? (:query-params result) :empty-param)))))))

(deftest test-compile-auth-flag
  (testing "When :auth? is not specified, it defaults to true"
    (let [op-spec {:name :get-patient
                   :method :get
                   :path "Patient/123"}
          compiled (op/compile op-spec)]
      (is (= true (get-in compiled [:get-patient :auth?])))))

  (testing "When :auth? is explicitly true, it is preserved"
    (let [op-spec {:name :get-patient
                   :method :get
                   :path "Patient/123"
                   :auth? true}
          compiled (op/compile op-spec)]
      (is (= true (get-in compiled [:get-patient :auth?])))))

  (testing "When :auth? is explicitly false, it is preserved"
    (let [op-spec {:name :get-metadata
                   :method :get
                   :path "metadata"
                   :auth? false}
          compiled (op/compile op-spec)]
      (is (= false (get-in compiled [:get-metadata :auth?])))))

  (testing "Multiple operations with mixed :auth? values"
    (let [op-specs [{:name :get-metadata
                     :method :get
                     :path "metadata"
                     :auth? false}
                    {:name :get-patient
                     :method :get
                     :path "Patient/123"}
                    {:name :create-patient
                     :method :post
                     :path "Patient"
                     :auth? true}]
          compiled (map op/compile op-specs)
          merged (apply merge compiled)]
      (is (= false (get-in merged [:get-metadata :auth?])))
      (is (= true (get-in merged [:get-patient :auth?])))
      (is (= true (get-in merged [:create-patient :auth?]))))))

(deftest test-flatten-operations
  (testing "Returns operations unchanged when there are no groups"
    (let [ops [{:name :get-patient :method :get :path "Patient/123"}
               {:name :get-metadata :method :get :path "metadata"}]]
      (is (= [{:name :get-patient :method :get :path ["Patient/123"]}
              {:name :get-metadata :method :get :path ["metadata"]}]
             (op/flatten-operations ops)))))

  (testing "Flattens single-level group with string prefix"
    (let [ops [{:prefix "v1/Patient"
                :operations [{:name :search-patient :method :get}
                             {:name :read-patient :method :get :path [:ref/patient-id]}]}]]
      (is (= [{:name :search-patient :method :get :path ["v1/Patient"]}
              {:name :read-patient :method :get :path ["v1/Patient" :ref/patient-id]}]
             (op/flatten-operations ops)))))

  (testing "Flattens single-level group with vector prefix"
    (let [ops [{:prefix ["v1" "Patient"]
                :operations [{:name :create-patient :method :post}
                             {:name :update-patient :method :put :path [:ref/patient-id]}]}]]
      (is (= [{:name :create-patient :method :post :path ["v1" "Patient"]}
              {:name :update-patient :method :put :path ["v1" "Patient" :ref/patient-id]}]
             (op/flatten-operations ops)))))

  (testing "Flattens multiple groups at the same level"
    (let [ops [{:prefix "api/patients"
                :operations [{:name :list-patients :method :get}
                             {:name :get-patient :method :get :path [:ref/patient-id]}]}
               {:prefix "api/appointments"
                :operations [{:name :list-appointments :method :get}
                             {:name :get-appointment :method :get :path [:ref/appointment-id]}]}]]
      (is (= [{:name :list-patients :method :get :path ["api/patients"]}
              {:name :get-patient :method :get :path ["api/patients" :ref/patient-id]}
              {:name :list-appointments :method :get :path ["api/appointments"]}
              {:name :get-appointment :method :get :path ["api/appointments" :ref/appointment-id]}]
             (op/flatten-operations ops)))))

  (testing "Flattens nested groups (2-level hierarchy)"
    (let [ops [{:prefix "FHIR/R4"
                :operations [{:name :capabilities :method :get :path "metadata"}
                             {:prefix "Patient"
                              :operations [{:name :search-patient :method :get}
                                           {:name :read-patient :method :get :path [:ref/patient-id]}]}
                             {:prefix "Observation"
                              :operations [{:name :search-observation :method :get}
                                           {:name :read-observation :method :get :path [:ref/observation-id]}]}]}]]
      (is (= [{:name :capabilities :method :get :path ["FHIR/R4" "metadata"]}
              {:name :search-patient :method :get :path ["FHIR/R4" "Patient"]}
              {:name :read-patient :method :get :path ["FHIR/R4" "Patient" :ref/patient-id]}
              {:name :search-observation :method :get :path ["FHIR/R4" "Observation"]}
              {:name :read-observation :method :get :path ["FHIR/R4" "Observation" :ref/observation-id]}]
             (op/flatten-operations ops)))))

  (testing "Flattens deeply nested groups (3-level hierarchy)"
    (let [ops [{:prefix "api"
                :operations [{:prefix "v1"
                              :operations [{:prefix "Patient"
                                            :operations [{:name :get-patient :method :get}]}]}]}]]
      (is (= [{:name :get-patient :method :get :path ["api" "v1" "Patient"]}]
             (op/flatten-operations ops)))))

  (testing "Handles mixed operations and groups at root level"
    (let [ops [{:name :health-check :method :get :path "health"}
               {:prefix "v1"
                :operations [{:name :get-patient :method :get :path "Patient"}]}
               {:name :metadata :method :get :path "metadata"}]]
      (is (= [{:name :health-check :method :get :path ["health"]}
              {:name :get-patient :method :get :path ["v1" "Patient"]}
              {:name :metadata :method :get :path ["metadata"]}]
             (op/flatten-operations ops)))))

  (testing "Handles operations without path inside groups"
    (let [ops [{:prefix "Patient"
                :operations [{:name :search-patient :method :get}
                             {:name :create-patient :method :post}]}]]
      (is (= [{:name :search-patient :method :get :path ["Patient"]}
              {:name :create-patient :method :post :path ["Patient"]}]
             (op/flatten-operations ops)))))

  (testing "Returns empty vector when given empty input"
    (is (= [] (op/flatten-operations []))))

  (testing "Preserves all operation fields except path"
    (let [ops [{:prefix "v1"
                :operations [{:name :get-patient
                              :method :get
                              :auth? false
                              :description "Get patient by ID"
                              :request {:headers {"Accept" "application/json"}}
                              :path [:ref/patient-id]}]}]]
      (is (= [{:name :get-patient
               :method :get
               :auth? false
               :description "Get patient by ID"
               :request {:headers {"Accept" "application/json"}}
               :path ["v1" :ref/patient-id]}]
             (op/flatten-operations ops))))))

(deftest test->transformer
  (testing "Returns identity-like function when transformers vector is empty"
    (let [transformer (op/->transformer [])]
      (is (= {:a 1} (transformer {:a 1})))))

  (testing "Composes single transformer"
    (let [transformer (op/->transformer [inc])]
      (is (= 6 (transformer 5)))))

  (testing "Composes multiple transformers in correct order (last in vector executes first)"
    (let [transformer (op/->transformer [#(str % "!") str/upper-case])]
      (is (= "HELLO!" (transformer "hello")))))

  (testing "Composes transformers correctly with reverse"
    (let [transformer (op/->transformer [inc #(* % 2)])]
      (is (= 12 (transformer 5))))))

(deftest test-compile-with-transformers
  (testing "Operation with :in transformers composes and stores them correctly"
    (let [op-spec {:name :transform-test
                   :method :get
                   :path "test"
                   :request {:body {:arg :ref/injected}}
                   :transformers {:in [(fn [ctx] (assoc ctx :injected "value"))]}}
          compiled (op/compile op-spec)
          in-transformer (get-in compiled [:transform-test :transformers :in])]

      ;; Transformer is composed and stored
      (is (fn? in-transformer))

      ;; Transformer works correctly when called
      (let [ctx {:ehr-adapter/base-url "https://api.test.com"}
            transformed-ctx (in-transformer ctx)]
        (is (= "value" (:injected transformed-ctx))))

      ;; Handler does NOT execute the transformer
      (let [operation-fn (get-in compiled [:transform-test :handler])
            ctx {:ehr-adapter/base-url "https://api.test.com"
                 :injected "value"} ;; Simulate transformer already ran
            result (operation-fn ctx identity)]
        ;; Now :injected is in ctx, so :ref/injected resolves
        (is (= "value" (get-in result [:body :arg]))))))

  (testing "Operation with :out transformers composes and stores them correctly"
    (let [op-spec {:name :transform-out-test
                   :method :get
                   :path "test"
                   :transformers {:out [(fn [resp] (assoc resp :transformed true))
                                        (fn [resp] (update resp :status (fnil inc 0)))]}}
          compiled (op/compile op-spec)
          out-transformer (get-in compiled [:transform-out-test :transformers :out])]

      ;; Transformer is composed and stored
      (is (fn? out-transformer))

      ;; Transformer works correctly when called
      (let [transformed-resp (out-transformer {:status 200 :body "OK"})]
        (is (= true (:transformed transformed-resp)))
        (is (= 201 (:status transformed-resp))))

      ;; Handler does NOT execute the transformer
      (let [operation-fn (get-in compiled [:transform-out-test :handler])
            ctx {:ehr-adapter/base-url "https://api.test.com"}
            mock-handler (fn [_] {:status 200 :body "OK"})
            result (operation-fn ctx mock-handler)]
        ;; :transformed is not in result because handler doesn't run transformers
        (is (not (:transformed result)))
        (is (= 200 (:status result))))))

  (testing "Operation with both :in and :out transformers stores both"
    (let [op-spec {:name :full-transform-test
                   :method :post
                   :path "test"
                   :request {:body {:date :ref/timestamp}}
                   :transformers {:in [(fn [ctx] (assoc ctx :timestamp "2024-01-01"))]
                                  :out [(fn [resp] (assoc resp :processed true))]}}
          compiled (op/compile op-spec)
          in-transformer (get-in compiled [:full-transform-test :transformers :in])
          out-transformer (get-in compiled [:full-transform-test :transformers :out])]

      ;; Both transformers are composed and stored
      (is (fn? in-transformer))
      (is (fn? out-transformer))

      ;; Transformers work correctly when called
      (let [ctx {:ehr-adapter/base-url "https://api.test.com"}
            transformed-ctx (in-transformer ctx)]
        (is (= "2024-01-01" (:timestamp transformed-ctx))))

      (let [transformed-resp (out-transformer {:status 200})]
        (is (= true (:processed transformed-resp))))))

  (testing "Operation without transformers has no :transformers key"
    (let [op-spec {:name :no-transform-test
                   :method :get
                   :path "test"}
          compiled (op/compile op-spec)]
      (is (nil? (get-in compiled [:no-transform-test :transformers]))))))
