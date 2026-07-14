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
                  :network {:request-handler mock-handler
                            :middlewares [mock-middleware]}
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
             (:body result)))))
  (testing "Initialize with OperationGroups resolves the prefix into the final URL"
    (let [call-log (atom [])
          mock-handler (make-mock-http-handler call-log)
          config {:domain :test/groups
                  :base-url "https://api.test.com"
                  :network {:request-handler mock-handler
                            :middlewares [mock-middleware]}
                  :operations [{:prefix "v1/Patient"
                                :operations [{:name :search-patient
                                              :method :get}
                                             {:name :read-patient
                                              :method :get
                                              :path [:ref/patient-id]}]}]}
          instance (core/initialize config)]

      (core/invoke instance :search-patient)
      (is (= "https://api.test.com/v1/Patient" (:url (first @call-log))))

      (reset! call-log [])
      (core/invoke instance :read-patient {:patient-id "42"})
      (is (= "https://api.test.com/v1/Patient/42" (:url (first @call-log)))))))

(deftest core-integration-no-auth-flow-test
  (testing "Flow without authentication: bypasses token logic, resolves dynamic refs, preserves user headers"
    (let [call-log (atom [])
          mock-handler (make-mock-http-handler call-log)

          config {:domain :test/public-api
                  :base-url "https://public.api.com/v1"
                  :network {:request-handler mock-handler
                            :middlewares [mock-middleware]}
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
                  :network {:request-handler mock-handler
                            :middlewares [mock-middleware]}
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

(deftest core-integration-client-injection-test
  (testing "Network config :client is injected into every request"
    (let [call-log (atom [])
          mock-handler (make-mock-http-handler call-log)
          custom-client :my-custom-http-client

          config {:domain :test/client-injection
                  :base-url "https://api.test.com/v1"
                  :network {:request-handler mock-handler
                            :client custom-client
                            :middlewares [mock-middleware]}
                  :operations [{:name :get-status
                                :path "status"
                                :method :get}]}

          instance (core/initialize config)
          result (core/invoke instance :get-status)]

      (is (= 1 (count @call-log)))
      (let [api-req (first @call-log)]
        (is (= custom-client (:client api-req))
            "Request should have :client from network-config"))

      (is (= {:status "success"
              :message "Operation executed successfully"}
             (:body result)))))

  (testing "When :client is not specified, it is not injected into requests"
    (let [call-log (atom [])
          mock-handler (make-mock-http-handler call-log)

          config {:domain :test/no-client
                  :base-url "https://api.test.com/v1"
                  :network {:request-handler mock-handler
                            :middlewares [mock-middleware]}
                  :operations [{:name :get-status
                                :path "status"
                                :method :get}]}

          instance (core/initialize config)]
      (core/invoke instance :get-status)

      (is (= 1 (count @call-log)))
      (let [api-req (first @call-log)]
        (is (nil? (:client api-req))
            "Request should not have :client when not configured")))))

(deftest core-integration-transformers-test
  (testing "Operation with :in transformers injects values into context for reference resolution"
    (let [call-log (atom [])
          mock-handler (make-mock-http-handler call-log)

          config {:domain :test/in-transformers
                  :base-url "https://api.test.com/v1"
                  :network {:request-handler mock-handler
                            :middlewares [mock-middleware]}
                  :operations [{:name :get-data
                                :path "data"
                                :method :get
                                :request {:headers {"X-Injected" :ref/injected-value}}
                                :transformers {:in [(fn [ctx] (assoc ctx :injected-value "transformed-header"))]}}]}

          instance (core/initialize config)
          result (core/invoke instance :get-data)]

      ;; Verify the transformer injected the value into context
      (is (= 1 (count @call-log)))
      (let [api-req (first @call-log)]
        ;; The reference :ref/injected-value should be resolved to "transformed-header"
        (is (= "transformed-header" (get-in api-req [:headers "X-Injected"]))))

      (is (= {:status "success"
              :message "Operation executed successfully"}
             (:body result)))))

  (testing "Operation with :out transformers modifies response after execution"
    (let [call-log (atom [])
          mock-handler (make-mock-http-handler call-log)

          config {:domain :test/out-transformers
                  :base-url "https://api.test.com/v1"
                  :network {:request-handler mock-handler
                            :middlewares [mock-middleware]}
                  :operations [{:name :get-data
                                :path "data"
                                :method :get
                                :transformers {:out [(fn [resp] (assoc resp :transformed true))
                                                     (fn [resp] (update resp :status (fnil inc 0)))]}}]}

          instance (core/initialize config)
          result (core/invoke instance :get-data)]

      ;; Verify the request was made normally
      (is (= 1 (count @call-log)))
      (let [api-req (first @call-log)]
        (is (= "https://api.test.com/v1/data" (:url api-req))))

      ;; Verify the transformers modified the response
      (is (= true (:transformed result)))
      (is (= 201 (:status result)))
      (is (= {:status "success"
              :message "Operation executed successfully"}
             (:body result)))))

  (testing "Operation with both :in and :out transformers"
    (let [call-log (atom [])
          mock-handler (make-mock-http-handler call-log)

          config {:domain :test/full-transformers
                  :base-url "https://api.test.com/v1"
                  :network {:request-handler mock-handler
                            :middlewares [mock-middleware]}
                  :operations [{:name :transform-pipeline
                                :path "transform"
                                :method :post
                                :request {:body {:timestamp :ref/timestamp}}
                                :transformers {:in [(fn [ctx] (assoc ctx :timestamp "2024-01-01"))]
                                               :out [(fn [resp] (assoc resp :processed true))]}}]}

          instance (core/initialize config)
          result (core/invoke instance :transform-pipeline)]

      ;; Verify the :in transformer injected value into context
      (is (= 1 (count @call-log)))
      (let [api-req (first @call-log)]
        (is (= "2024-01-01" (get-in api-req [:body :timestamp]))))

      ;; Verify the :out transformer modified the response
      (is (= true (:processed result)))))

  (testing "Operation without transformers works normally (baseline)"
    (let [call-log (atom [])
          mock-handler (make-mock-http-handler call-log)

          config {:domain :test/no-transformers
                  :base-url "https://api.test.com/v1"
                  :network {:request-handler mock-handler
                            :middlewares [mock-middleware]}
                  :operations [{:name :plain-operation
                                :path "plain"
                                :method :get}]}

          instance (core/initialize config)
          result (core/invoke instance :plain-operation)]

      ;; Verify normal execution without transformers
      (is (= 1 (count @call-log)))
      (let [api-req (first @call-log)]
        (is (= "https://api.test.com/v1/plain" (:url api-req)))
        (is (= :get (:method api-req))))

      ;; Response should be unchanged
      (is (= {:status "success"
              :message "Operation executed successfully"}
             (:body result)))
      (is (nil? (:transformed result)))
      (is (nil? (:processed result))))))

(deftest initialize-typed-references-test
  (testing "Successfully initializes and resolves typed references in config and operations"
    (let [call-log (atom [])
          mock-handler (make-mock-http-handler call-log)
          config {:domain :test/typed-refs
                  :base-url :ref#string/base-url
                  :network {:request-handler mock-handler
                            :middlewares [mock-middleware]}
                  :operations [{:name :get-patient
                                :path ["patients" :ref#pos-int/patient-id]
                                :method :get}]}
          init-ctx {:base-url "https://api.typed-clinic.com/v1"
                    :patient-id 999}
          instance (core/initialize init-ctx config)]

      (is (map? instance))
      (is (contains? instance :ehr-adapter/operations))

      (core/invoke instance :get-patient)
      (let [api-req (first @call-log)]
        (is (= "https://api.typed-clinic.com/v1/patients/999" (:url api-req))))))

  (testing "Throws :invalid/type during initialize if typed reference in config has wrong type"
    (let [mock-handler (make-mock-http-handler (atom []))
          config {:domain :test/typed-refs-fail
                  :base-url :ref#string/base-url
                  :network {:request-handler mock-handler}
                  :operations [{:name :get-patient
                                :path ["patients" :ref#pos-int/patient-id]
                                :method :get}]}
          init-ctx {:base-url "https://api.test.com"
                    :patient-id "not-a-number"}]

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"must be of type: :pos-int"
                            (core/initialize init-ctx config))))))

(deftest initialize-incoherent-references-test
  (testing "Throws :invalid/reference fast when the same referent has conflicting specs"
    (let [mock-handler (make-mock-http-handler (atom []))
          config {:domain :test/incoherent
                  :base-url "https://api.test.com"
                  :network {:request-handler mock-handler}
                  :operations [{:name :get-patient
                                :path ["patients" :ref/patient-id]
                                :method :get}
                               {:name :delete-patient
                                :path ["patients" :ref?/patient-id] ;; Contradicts :ref/patient-id
                                :method :delete}
                               {:name :get-record
                                :path ["records" :ref#string/record-id]
                                :method :get}
                               {:name :update-record
                                :path ["records" :ref#pos-int/record-id] ;; Contradicts :ref#string/record-id
                                :method :put}]}
          init-ctx {}]

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"appears with different specs"
                            (core/initialize init-ctx config))))))
