(ns ehr-adapter.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.schema :as schema]
            [clojure.string :as str]))

(defn- mock-http-client [_req]
  {:status 200 :body "OK"})

(defn- mock-translation-middleware [handler]
  (fn [req]
    ;; Acts as a coercion/translation layer before and after execution
    (let [coerced-req (assoc req :coerced? true)
          response (handler coerced-req)]
      (assoc response :normalized? true))))

(defn- mock-custom-auth-handler [_data]
  (fn [req] req))

;; =============================================================================
;; Valid Configurations Tests
;; =============================================================================

(deftest valid-adapter-config-test
  (testing "1. Pure connector adapter with the mandatory translation middleware"
    (let [config {:domain :eclinicalworks/tenant-alpha
                  :base-url "https://fhir.ecw.com/v1/fhir"
                  :http-client-fn mock-http-client
                  :middlewares [mock-translation-middleware]
                  :auth [{:type :api-key
                          :api-key "secret-key-123"}]}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "2. Multitenant adapter with standard OAuth2 credentials pipeline and dynamic operations"
    (let [config {:domain :eclinicalworks/tenant-beta
                  :base-url "https://api.eclinicalworks.com/v2"
                  :http-client-fn mock-http-client
                  :middlewares [mock-translation-middleware]
                  :auth [{:type          :oauth2
                          :token-url     "https://auth.eclinicalworks.com/oauth/token"
                          :grant-type    "client_credentials"
                          :client-id     "ecw-client-id-prod"
                          :client-secret "ecw-client-secret-secure-123"}]
                  :network-config {:timeout-ms 5000
                                   :retries 3
                                   :retry-delay-ms 200
                                   :retry-strategy :exponential
                                   :refresh-token-on [401]}
                  :operations [{:name :search-patient
                                :path ["v1/Patient" :ref/patientId]
                                :method :get
                                :expected-status [200 206]
                                :base-headers {"Prefer" "respond-async"}}]}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "3. Custom auth layer with live handler factory and unstructured data map"
    (let [config {:domain :epic/hospital-central-prod
                  :base-url "https://epic.hospital.org/api"
                  :http-client-fn mock-http-client
                  :middlewares [mock-translation-middleware]
                  :auth [{:type :custom
                          :handler mock-custom-auth-handler
                          :data {:session-id "sess_9901"
                                 :sandbox? true
                                 :arbitrary-nested-meta {:nested "value"}}}]}]
      (is (= config (schema/validate-adapter-config config))))))

;; =============================================================================
;; Invalid Configurations Tests (Schema Failures)
;; =============================================================================

(deftest invalid-adapter-config-test
  (testing "Missing mandatory translation middleware (empty vector)"
    (let [config {:domain :eclinicalworks/test-tenant
                  :base-url "https://api.com"
                  :http-client-fn mock-http-client
                  :middlewares []
                  :auth [{:type :api-key :api-key "123"}]}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid Adapter configuration"
           (schema/validate-adapter-config config)))))

  (testing "Domain missing its namespace (violates multitenant routing design)"
    (let [config {:domain :flat-keyword-without-namespace
                  :base-url "https://api.com"
                  :http-client-fn mock-http-client
                  :middlewares [mock-translation-middleware]
                  :auth [{:type :api-key :api-key "123"}]}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid Adapter configuration"
           (schema/validate-adapter-config config)))))

  (testing "Resiliency policy contradiction (:retries present without :retry-delay-ms)"
    (try
      (schema/validate-adapter-config
       {:domain :eclinicalworks/test-tenant
        :base-url "https://api.com"
        :http-client-fn mock-http-client
        :middlewares [mock-translation-middleware]
        :auth [{:type :api-key :api-key "123"}]
        :network-config {:timeout-ms 1000
                         :retries 3
                         :retry-strategy :linear}})
      (is false "Expected ExceptionInfo to be thrown")
      (catch clojure.lang.ExceptionInfo ex
        (let [errors (:details (ex-data ex))]
          (is (some #(str/includes? % "If you configure :retries, you must provide :retry-delay-ms")
                    (:network-config errors)))))))

  (testing "SMART on FHIR logical error (mutual exclusion violation)"
    (try
      (schema/validate-adapter-config
       {:domain :eclinicalworks/test-tenant
        :base-url "https://api.com"
        :http-client-fn mock-http-client
        :middlewares [mock-translation-middleware]
        :auth [{:type :smart-on-fhir/backend-services
                :client-id "client-123"
                :key-id "key-123"
                :algorithm "RS256"
                :scopes ["system/*.read"]
                :token-url "https://auth.com"
                :private-key-path "/etc/keys/fhir.pem"
                :private-key "-----BEGIN PRIVATE KEY-----\n..."}]})
      (is false "Expected ExceptionInfo due to exclusive key constraint")
      (catch clojure.lang.ExceptionInfo ex
        (let [errors (:details (ex-data ex))]
          (is (some? errors))))))

  (testing "Operation configuration contains a blank string path segment"
    (try
      (schema/validate-adapter-config
       {:domain :eclinicalworks/test-tenant
        :base-url "https://api.com"
        :http-client-fn mock-http-client
        :middlewares [mock-translation-middleware]
        :auth [{:type :api-key :api-key "123"}]
        :operations [{:name :get-patient
                      :method :get
                      :path ["v1/Patient" "    " :id]}]})
      (is false "Expected ExceptionInfo due to blank string path segment")
      (catch clojure.lang.ExceptionInfo ex
        (let [errors (:details (ex-data ex))
              path-errors (get-in errors [:operations 0 :path])]
          (is (str/includes? (str path-errors) "each segment in operation-path must be a non-blank string")))))))
