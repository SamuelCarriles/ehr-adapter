(ns ehr-adapter.auth.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.auth.core :as core]
            [ehr-adapter.schema :as schema]
            [ehr-adapter.time :as time]
            [ehr-adapter.auth.strategy :as strategy]))

;; =============================================================================
;; 1. BINDINGS TESTS
;; =============================================================================

(deftest bindings-test
  (let [ctx {:status 200
             :body {:access_token "ey-token-123"
                    :expires_in 3600
                    :nested {:flag false}}
             :flat-key "flat-value"}]

    (testing "Extracting with vector paths (get-in behavior)"
      (is (= {:token "ey-token-123" :expires 3600}
             (core/bindings ctx {:token [:body :access_token]
                                 :expires [:body :expires_in]}))))

    (testing "Extracting with plain keywords (get behavior)"
      (is (= {:flat "flat-value"}
             (core/bindings ctx {:flat :flat-key}))))

    (testing "Safe treatment of boolean false values (using some? check)"
      (is (= {:is-active false}
             (core/bindings ctx {:is-active [:body :nested :flag]}))))

    (testing "Safely ignores missing paths or nil values"
      (is (= {}
             (core/bindings ctx {:missing [:body :not-found]
                                 :ignored :non-existing-root-key}))))))

;; =============================================================================
;; 2. NORMALIZE TESTS
;; =============================================================================

(deftest normalize-test
  (testing "Standard extraction and mapping to strict 4-key schema"
    (let [network-response {:body {:access_token "token" :expires_in 3600}}
          layer {:type :normalize
                 :token [:body :access_token]
                 :expires-in [:body :expires_in]}]
      (with-redefs [time/now (constantly 1000)]
        (is (= {:token "token" :expires-at 4600}
               (core/normalize network-response layer))))))

  (testing "Fallback behavior: keeps values that are already clean in the root context"
    (let [clean-response {:token "already-clean-token"
                          :token-type "Bearer"
                          :garbage-key "drop-me"}
          layer {:type :normalize}]
      (is (= {:token "already-clean-token" :token-type "Bearer"}
             (core/normalize clean-response layer)))))

  (testing "Strict contract: filters out operational system keys like :type"
    (let [network-response {:token "token"}
          layer {:type :normalize :extra-unwanted-key "garbage"}]
      (is (= {:token "token"}
             (core/normalize network-response layer)))))

;; =============================================================================
;; 3. PROCESS-LAYER TESTS
;; =============================================================================

  (testing "When layer is a transport strategy -> dispatches correctly to strategy/execute"
    (let [mock-context {:initial "data"}
          mock-layer   {:type :oauth2
                        :token-url "https://api.ehr.com/token"
                        :grant-type "client_credentials"
                        :client-id "id"
                        :client-secret "secret"
                        :bindings {:local-val [:initial]}
                        :options {:request
                                  {:form-params
                                   {"local" :ref/local-val}}}}]

      (with-redefs [strategy/execute (fn [ready-layer _] ready-layer)]
        (let [result (core/process-layer mock-context mock-layer nil)
              form-params (get-in result [:options :request :form-params])]

          (is (= :oauth2 (:type result)))
          (is (not (contains? result :bindings)))
          (is (map? form-params))
          (is (= "data" (get form-params "local"))))))))

;; =============================================================================
;; 4. FULL FLOW INTEGRATION TESTS
;; =============================================================================

(deftest process-layer-full-flow-test
  (testing "End-to-End: Resolves bindings, dispatches to real :oauth2 strategy, and constructs valid HTTP request"
    (let [mock-context {:initial "data"}
          mock-layer   {:type :oauth2
                        :token-url "https://api.ehr.com/token"
                        :grant-type "client_credentials"
                        :client-id "my-client-id"
                        :client-secret "my-client-secret"
                        :bindings {:local-val [:initial]}
                        :options {:request
                                  {:form-params
                                   {"local" :ref/local-val}
                                   :headers {"Authorization" "Initial-Header"}}}}

          result (core/process-layer mock-context mock-layer identity)]

      (is (schema/validate-http-request result)
          "The generated output must strictly comply with the engine's HTTP request schema contract")

      (testing "Target endpoint and method configuration"
        (is (= "https://api.ehr.com/token" (:url result)))
        (is (= :post (:method result)))
        (is (= :form-url-encoded (:content-type result))))

      (testing "Form parameters payload compilation (with resolved dynamic references)"
        (let [form-params (:form-params result)]
          (is (map? form-params))
          (is (= "client_credentials" (get form-params "grant_type")))
          (is (= "my-client-id" (get form-params "client_id")))
          (is (= "my-client-secret" (get form-params "client_secret")))
          (is (= "data" (get form-params "local")))))

      (testing "Headers preservation and compliance"
        (let [headers (:headers result)]
          (is (map? headers))
          (is (= "Initial-Header" (get headers "Authorization"))))))))
