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
             (core/normalize network-response layer))))))

;; =============================================================================
;; 3. PROCESS-LAYER TESTS
;; =============================================================================

(deftest process-layer-test
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

(deftest process-layer-response-validation-test
  (testing "When strategy returns a successful HTTP response (2xx), it passes through"
    (let [mock-context {}
          mock-layer {:type :oauth2}
          mock-response {:status 200 :body {:access_token "token-123" :expires_in 3600}}]

      (with-redefs [strategy/execute (fn [_ _] mock-response)]
        (is (= mock-response (core/process-layer mock-context mock-layer nil))))))

  (testing "When strategy returns a failed HTTP response (4xx), it throws :http/failure"
    (let [mock-context {}
          mock-layer {:type :oauth2}
          mock-response {:status 401 :body {:error "invalid_client"}}]

      (with-redefs [strategy/execute (fn [_ _] mock-response)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Authentication request failed"
             (core/process-layer mock-context mock-layer nil))))))

  (testing "When strategy returns a failed HTTP response (5xx), it throws :http/failure"
    (let [mock-context {}
          mock-layer {:type :smart-on-fhir/backend-services}
          mock-response {:status 500 :body {:error "server_error"}}]

      (with-redefs [strategy/execute (fn [_ _] mock-response)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Authentication request failed"
             (core/process-layer mock-context mock-layer nil))))))

  (testing "When strategy returns a token map (no :status), it passes through without validation"
    (let [mock-context {}
          mock-layer {:type :basic-auth}
          token-map {:token "dXNlcjpwYXNz" :token-type "Basic"}]

      (with-redefs [strategy/execute (fn [_ _] token-map)]
        (is (= token-map (core/process-layer mock-context mock-layer nil))))))

  (testing "When strategy returns nil status, it passes through without validation"
    (let [mock-context {}
          mock-layer {:type :custom}
          result-without-status {:body "some-data"}]

      (with-redefs [strategy/execute (fn [_ _] result-without-status)]
        (is (= result-without-status (core/process-layer mock-context mock-layer nil)))))))

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

;; ==================================================================
;; Token realated tests 
;; ==================================================================

(deftest token-expired?-test
  (testing "Token is strictly expired (past expires-at)"
    (with-redefs [time/now (constantly 1100)]
      (is (true? (core/token-expired? {:expires-at 1000} 0)))
      (is (true? (core/token-expired? {:expires-at 1000} 60)))))

  (testing "Token is strictly valid (future expires-at)"
    (with-redefs [time/now (constantly 900)]
      (is (false? (core/token-expired? {:expires-at 1000} 0)))
      (is (false? (core/token-expired? {:expires-at 1000} 60)))))

  (testing "Buffer behavior: expires-at is within the proactive refresh window"
    ;; expires-at is 1000, buffer is 60. Alert threshold is 940.
    (with-redefs [time/now (constantly 950)]
      ;; 950 >= (1000 - 60) -> 950 >= 940 -> true (expired, triggers proactive refresh)
      (is (true? (core/token-expired? {:expires-at 1000} 60)))
      ;; With 0 buffer, 950 >= 1000 -> false (not expired yet)
      (is (false? (core/token-expired? {:expires-at 1000} 0)))))

  (testing "Buffer behavior: exactly at the boundary"
    ;; expires-at is 1000, buffer is 60. Alert threshold is 940.
    (with-redefs [time/now (constantly 940)]
      (is (true? (core/token-expired? {:expires-at 1000} 60)))))

  (testing "Missing expires-at key (nil)"
    ;; Should return false (not expired) to avoid unnecessary refreshes if the auth server didn't provide it
    (with-redefs [time/now (constantly 9999)]
      (is (false? (core/token-expired? {} 60)))
      (is (false? (core/token-expired? {:expires-at nil} 60)))))

  (testing "Single arity defaults to 0 buffer"
    (with-redefs [time/now (constantly 1000)]
      ;; Exactly at expiration: 1000 >= (1000 - 0) -> true
      (is (true? (core/token-expired? {:expires-at 1000})))
      ;; 1 second before expiration: 1000 >= (1001 - 0) -> false
      (is (false? (core/token-expired? {:expires-at 1001})))
      ;; 1 second after expiration: 1000 >= (999 - 0) -> true
      (is (true? (core/token-expired? {:expires-at 999}))))))

(defn- make-auth-data [initial-state refresh-fn]
  {:state (atom initial-state)
   :refresh-fn refresh-fn})

(deftest ensure-token!-test
  (with-redefs [time/now (constantly 1000)]
    (testing "1. Valid token: doesn't call refresh-fn and returns auth-data unchanged"
      (let [refresh-called? (atom false)
            refresh-fn (fn [_] (reset! refresh-called?  true) {:token "new"})
            auth-data (make-auth-data {:token "valid" :expires-at 2000} refresh-fn)]
        (is (= auth-data (core/ensure-token! auth-data 60)))
        (is (false? @refresh-called?) "refresh-fn should not be called for valid token")
        (is (= {:token "valid" :expires-at 2000} @(:state auth-data)) "Atom state remains unchanged")))

    (testing "2. Expired token (single thread): calls refresh-fn, updates atom, returns auth-data"
      (let [refresh-called? (atom false)
            refresh-fn (fn [_] (reset! refresh-called? true) {:token "refreshed" :expires-at 3000})
            auth-data (make-auth-data {:token "expired" :expires-at 900} refresh-fn)]

        (is (= auth-data (core/ensure-token! auth-data 60)))
        (is (true? @refresh-called?) "refresh-fn should be called for expired token")
        (is (= {:token "refreshed" :expires-at 3000} @(:state auth-data)) "Atom state updated with new token")))

    (testing "3. Concurrent access: Only one leader refreshes, followers wait and succeed"
      (let [refresh-count (atom 0)
            refresh-fn (fn [_] (Thread/sleep 50)
                         (swap! refresh-count inc)
                         {:token "concurrent-refreshed" :expires-at 4000})
            auth-data (make-auth-data {:token "expired" :expires-at 900} refresh-fn)

            futures (doall (repeatedly 5 #(future (core/ensure-token! auth-data 60))))]

        (doseq [f futures] @f)

        (is (= 1 @refresh-count) "Only ONE refresh should occur despite 5 concurrent calls")
        (is (= {:token "concurrent-refreshed" :expires-at 4000} @(:state auth-data)))))

    (testing "4. Refresh failure: Exception propagates, atom state is restored"
      (let [refresh-fn (fn [_] (Thread/sleep 50) (throw (ex-info "Auth server down" {})))
            auth-data (make-auth-data {:token "expired" :expires-at 900} refresh-fn)
            caught-errors (atom [])
            futures (doall (repeatedly 2 #(future (try (core/ensure-token! auth-data 60)
                                                       (catch Throwable e (swap! caught-errors conj e))))))]

        (doseq [f futures] @f)

        (is (>= (count @caught-errors) 1) "At least one thread must catch the exception")
        (is (= {:token "expired" :expires-at 900} @(:state auth-data)) "Atom state MUST be restored after failure")))))
