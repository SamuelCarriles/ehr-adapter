(ns ehr-adapter.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ehr-adapter.core :as core]))

(defn mock-middleware [handler]
  (fn [req] (handler req)))

(defn- make-mock-http-handler
  "Creates a handler that logs every request in an atom and returns a standard 
   HTTP response depending on the endpoint."
  [call-log]
  (fn [req]
    (swap! call-log conj req)
    (if (str/includes? (:url req) "/oauth/token")
      ;; Standard OAuth2 Token Response
      {:status 200
       :body {:access_token "integration-test-token-xyz"
              :token_type "Bearer"
              :expires_in 3600}}
      ;; Standard API Success Response
      {:status 200
       :body {:status "success"
              :message "Operation executed successfully"}})))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest core-integration-full-flow-test
  (testing "End-to-End: partial-resolve, token refresh, and dynamic operation execution"
    (let [call-log (atom [])
          mock-handler (make-mock-http-handler call-log)

          config {:domain :test/clinic
                  :base-url :ref/base-url
                  :middlewares [mock-middleware]
                  :network-config {:request-handler mock-handler}
                  :auth {:initial [{:type :oauth2
                                    :token-url "https://auth.test-clinic.com/oauth/token"
                                    :grant-type "client_credentials"
                                    :client-id :ref/client-id
                                    :client-secret :ref/client-secret}
                                   {:type :normalize
                                    :token [:body :access_token]
                                    :token-type [:body :token_type]
                                    :expires-in [:body :expires_in]}]}
                  :operations [{:name :get-patient
                                :path ["patients" :ref/patient-id]
                                :method :get}]}

          init-ctx {:base-url "https://api.test-clinic.com/v1"
                    :client-id "my-client-123"
                    :client-secret "super-secret"}

          instance (core/initialize init-ctx config)

          result (core/invoke instance :get-patient {:patient-id "98765"
                                                     :request {:headers {"X-Custom-Header" "keep-me"}}})]

      (is (map? instance))
      (is (contains? instance :ehr-adapter/operations))

      ;; The mock handler was called exactly 2 times (1 auth + 1 API)
      (is (= 2 (count @call-log)) "Should make exactly 2 HTTP calls")

      ;; Verify the FIRST call (Authentication)
      (let [auth-req (first @call-log)]
        (is (= "https://auth.test-clinic.com/oauth/token" (:url auth-req)))
        (is (= :post (:method auth-req)))
        (is (= "my-client-123" (get-in auth-req [:form-params "client_id"]))))

      ;; Verify the SECOND call (API Request)
      (let [api-req (second @call-log)]
        (is (= "https://api.test-clinic.com/v1/patients/98765" (:url api-req)))
        (is (= :get (:method api-req)))
        ;; Verify the token was correctly injected into the :headers map
        (is (= "Bearer integration-test-token-xyz" (get-in api-req [:headers "Authorization"])))
        ;; Verify the user's custom header was preserved alongside the auth header
        (is (= "keep-me" (get-in api-req [:headers "X-Custom-Header"]))))

      ;; The final result is the standard success response from the API
      (is (= {:status "success"
              :message "Operation executed successfully"}
             (:body result))))))

(deftest core-integration-no-auth-flow-test
  (testing "Flow without authentication: bypasses token logic, resolves dynamic refs, preserves user headers"
    (let [call-log (atom [])
          mock-handler (make-mock-http-handler call-log)

          config {:domain :test/public-api
                  :base-url "https://public.api.com/v1"
                  :middlewares [mock-middleware]
                  :network-config {:request-handler mock-handler}
                  :operations [{:name :get-status
                                :path ["status" :ref/env]
                                :method :get}]}

          instance (core/initialize config)
          result (core/invoke instance :get-status {:env "prod"
                                                    :request {:headers {"X-Custom-Header" "my-value"}}})]

      ;; Only 1 HTTP call (no auth)
      (is (= 1 (count @call-log)))

      ;; Verify the API request structure
      (let [api-req (first @call-log)]
        (is (= "https://public.api.com/v1/status/prod" (:url api-req)))
        ;; Verify custom header exists
        (is (= "my-value" (get-in api-req [:headers "X-Custom-Header"])))
        ;; Verify NO Authorization header was injected
        (is (nil? (get-in api-req [:headers "Authorization"]))))

      ;; The final result is the standard success response
      (is (= {:status "success"
              :message "Operation executed successfully"}
             (:body result))))))

(deftest core-integration-auth-flag-test
  (testing "Operation with :auth? false should bypass authentication even when auth is configured"
    (let [call-log (atom [])
          mock-handler (make-mock-http-handler call-log)

          config {:domain :test/mixed-auth
                  :base-url "https://api.test.com/v1"
                  :middlewares [mock-middleware]
                  :network-config {:request-handler mock-handler}
                  :auth {:initial [{:type :oauth2
                                    :token-url "https://auth.test.com/oauth/token"
                                    :grant-type "client_credentials"
                                    :client-id "client-123"
                                    :client-secret "secret-456"}
                                   {:type :normalize
                                    :token [:body :access_token]
                                    :token-type [:body :token_type]
                                    :expires-in [:body :expires_in]}]}
                  :operations [{:name :get-metadata
                                :path "metadata"
                                :method :get
                                :auth? false}
                               {:name :get-patient
                                :path "Patient/123"
                                :method :get}]}

          instance (core/initialize config)]

      ;; Clear the log after initialization (which triggers initial auth)
      (reset! call-log [])

      ;; Invoke the operation with :auth? false
      (let [result (core/invoke instance :get-metadata)]
        ;; Should only make 1 HTTP call (no auth)
        (is (= 1 (count @call-log)) "Should make exactly 1 HTTP call (no auth)")

        ;; Verify the API request structure
        (let [api-req (first @call-log)]
          (is (= "https://api.test.com/v1/metadata" (:url api-req)))
          (is (= :get (:method api-req)))
          ;; Verify NO Authorization header was injected
          (is (nil? (get-in api-req [:headers "Authorization"]))))

        ;; The final result is the standard success response
        (is (= {:status "success"
                :message "Operation executed successfully"}
               (:body result))))

      ;; Clear the log and invoke the operation with default :auth? (true)
      (reset! call-log [])
      (core/invoke instance :get-patient)
;; Should make 1 HTTP call (token already cached from initialize)      
      (is (= 1 (count @call-log)) "Should make exactly 1 HTTP call (token already cached)")

;; Verify the API Request
      (let [api-req (first @call-log)]
        (is (= "https://api.test.com/v1/Patient/123" (:url api-req)))
        (is (= :get (:method api-req)))
          ;; Verify the token was correctly injected
        (is (= "Bearer integration-test-token-xyz" (get-in api-req [:headers "Authorization"])))))))
