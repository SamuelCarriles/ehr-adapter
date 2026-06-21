(ns ehr-adapter.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.schema :as schema]
            [clojure.string :as str]))

(defn- mock-http-request-handler [_req]
  {:status 200 :body "OK"})

(defn- mock-translation-middleware [handler]
  (fn [req]
    (let [coerced-req (assoc req :coerced? true)
          response (handler coerced-req)]
      (assoc response :normalized? true))))

(defn- mock-custom-auth-handler [_layer _http-client]
  (fn [req] req))

(defn- mock-get-token-fn []
  "mock-bearer-token-123")

(defn- build-mock-instance [config]
  (cond-> {:ehr-adapter/domain           (:domain config)
           :ehr-adapter/base-url         (:base-url config)
           :ehr-adapter/request-handler  mock-http-request-handler
           :ehr-adapter/operations       (into {}
                                               (map (fn [op]
                                                      [(:name op)
                                                       {:handler (fn [ctx _]
                                                                   {:status 200
                                                                    :executed (:name op)
                                                                    :data ctx})}])
                                                    (:operations config)))}
    (:auth config) (assoc :ehr-adapter/auth {:state     (atom {:token "xyz"})
                                             :refresh-fn mock-get-token-fn})))

;; ========================================================================
;; URL validator
;; ========================================================================

(deftest validate-url-test
  (testing "Returns the URL unchanged if it is valid"
    (is (= "https://api.ehr.com/v1/Patient"
           (schema/validate-url "https://api.ehr.com/v1/Patient"))))

  (testing "Throws :invalid/format exception if the URL is invalid"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"The given URL is not valid"
                          (schema/validate-url "not-a-valid-url")))

    (testing "Error payload matches the domain error contract"
      (try
        (schema/validate-url "api.ehr.com/v1")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :invalid/format (:code data)))
            (is (= :ehr-adapter.schema (:scope data)))
            (is (= :validate (:operation data)))
            (is (= "Valid URL under RFC 2396" (get-in data [:details :expected])))))))))

;; =============================================================================
;; Valid Configurations Tests
;; =============================================================================

(deftest valid-adapter-config-test
  (testing "1. Pure connector adapter with the mandatory translation middleware, network config and basic auth"
    (let [config {:domain :eclinicalworks/tenant-alpha
                  :base-url "https://fhir.ecw.com/v1/fhir"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :basic-auth
                                    :username "integrator-user"
                                    :password "secret-pass-123"}]}}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "2. Multitenant adapter with standard OAuth2 credentials pipeline, full resilient network-config and dynamic operations"
    (let [config {:domain :eclinicalworks/tenant-beta
                  :base-url "https://api.eclinicalworks.com/v2"
                  :network-config {:retries 3
                                   :retry-delay-ms 200
                                   :retry-strategy :exponential
                                   :retry-on [500 502 503 504]
                                   :client :mock-babashka-http-client
                                   :request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type          :oauth2
                                    :token-url     "https://auth.eclinicalworks.com/oauth/token"
                                    :grant-type    "client_credentials"
                                    :client-id      "ecw-client-id-prod"
                                    :client-secret "ecw-client-secret-secure-123"}]}
                  :operations [{:name :search-patient
                                :path ["v1/Patient" :ref/patientId]
                                :method :get
                                :request {:headers {"Prefer" "respond-async"}}}]}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "3. Custom auth layer with live handler factory using options parameter"
    (let [config {:domain :epic/hospital-central-prod
                  :base-url "https://epic.hospital.org/api"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :custom
                                    :handler mock-custom-auth-handler
                                    :options {:request {:query-params {:sandbox true}}}}]}}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "4. OAuth2 configuration with a valid and complex declarative :normalize map (Sugar + ExtractionPath)"
    (let [config {:domain :eclinicalworks/tenant-normalize-valid
                  :base-url "https://api.eclinicalworks.com/v2"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type          :oauth2
                                    :token-url     "https://auth.eclinicalworks.com/oauth/token"
                                    :grant-type    "client_credentials"
                                    :client-id      "ecw-id"
                                    :client-secret "ecw-secret"}
                                   {:type          :normalize
                                    :token          :access_token
                                    :token-type    "token_type"
                                    :expires-in    [:body :meta :expires 0 :sec]
                                    :refresh-token [:refresh_key]}]}}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "5. Normalization layer completely implicit (Malli should accept empty map since all keys are optional)"
    (let [config {:domain :eclinicalworks/tenant-normalize-empty
                  :base-url "https://api.eclinicalworks.com/v2"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :oauth2
                                    :token-url "https://auth.eclinicalworks.com/oauth/token"
                                    :grant-type "client_credentials"
                                    :client-id "id"
                                    :client-secret "secret"}
                                   {:type :normalize}]}}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "6. Global :payload validation using heterogeneous structures ([:map-of :any :any])"
    (let [config {:domain :advancedmd/tenant-payload-heterogeneous
                  :base-url "https://api.advancedmd.com/v2"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type          :oauth2
                                    :token-url     "https://api.advancedmd.com/oauth2/token"
                                    :grant-type    "client_credentials"
                                    :client-id      "adv-id"
                                    :client-secret "adv-secret"
                                    :options       {:request {:content-type {:code :unsupported :raw-mime "custom-content-type"}
                                                              :form-params {"alg" "RS256"
                                                                            :custom-id 12345}
                                                              :headers {"X-Partner-Id" "partner-99"
                                                                        "X-Context-Id"  "ctx-abc"}
                                                              :query-params {:sandbox true}}}}]}}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "7. Dynamic/Hybrid :basic-auth supporting optional base :payload (AdvancedMD use-case)"
    (let [config {:domain :advancedmd/tenant-dynamic-basic
                  :base-url "https://api.advancedmd.com/v2"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type      :basic-auth
                                    :username  "adv-integrator"
                                    :password  "super-secure-pass"
                                    :options   {:request {:method :post
                                                          :url "https://api.advancedmd.com/oauth2/token"
                                                          :form-params {:alg "RS256"}}}}]}}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "8. SMART on FHIR Backend Services with explicit inline String PEM private key"
    (let [config {:domain :epic/sandbox-smart-pem
                  :base-url "https://fhir.epic.com/interconnect-fhir-oauth/api/FHIR/R4"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :smart-on-fhir/backend-services
                                    :client-id "epic-client-123"
                                    :key-id "key-prod-1"
                                    :algorithm :rs256
                                    :scopes ["system/Patient.read"]
                                    :audience "https://fhir.epic.com"
                                    :token-url "https://fhir.epic.com/interconnect-fhir-oauth/oauth2/token"
                                    :private-key "-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwgg..."}]
                         :refresh [{:type :oauth2
                                    :token-url "https://fhir.epic.com/interconnect-fhir-oauth/oauth2/token"
                                    :grant-type "refresh_token"
                                    :client-id "epic-client-123"
                                    :client-secret "client-secret"}]}}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "9. SMART on FHIR Backend Services with an inline parsed JWK Map (with cryptographic fields)"
    (let [config {:domain :cerner/sandbox-smart-jwk
                  :base-url "https://fhir.cerner.com/r4/ec246c2b"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :smart-on-fhir/backend-services
                                    :client-id "cerner-client-456"
                                    :key-id "key-cerner-2"
                                    :algorithm :rs256
                                    :scopes ["system/Observation.read"]
                                    :audience "https://fhir.cerner.com"
                                    :token-url "https://authorization.cerner.com/tenants/ec246c2b/protocols/oauth2/tokens"
                                    :private-key {:kty "RSA"
                                                  :alg "RS256"
                                                  :kid "key-cerner-2"
                                                  :n "u1WhN..."
                                                  :e "AQAB"
                                                  :d "G4n7X..."
                                                  :p "9_3A1..."
                                                  :q "8_mP4..."}}]}}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "10. No authentication configuration (Delegated entirely to a pre-configured HTTP client/mTLS)"
    (let [config {:domain :hapi-fhir/public-sandbox
                  :base-url "https://hapi.fhir.org/baseR4"
                  :middlewares [mock-translation-middleware]
                  :network-config {:client :my-pre-authorized-java-http-client
                                   :request-handler mock-http-request-handler}}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "11. Operations using plain static string paths without dynamic vector segments"
    (let [config {:domain :advancedmd/static-path-tenant
                  :base-url "https://api.advancedmd.com/v2"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :basic-auth :username "u" :password "p"}]}
                  :operations [{:name :get-api-status
                                :method :get
                                :path "v2/status/health"
                                :description "Retrieves the current static operational status of the upstream EHR service."}]}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "12. SMART on FHIR Backend Services with a JWKS (:private-key-set) containing multiple keys"
    (let [config {:domain :ecw/sandbox-smart-jwks
                  :base-url "https://staging-fhir.ecwcloud.com/fhir/r4/FFBJCD"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :smart-on-fhir/backend-services
                                    :client-id "ecw-client-789"
                                    :key-id "Fd5l9-DGsmXYOHu95FMZT4qG2WoyuLtklsEexkvXdzk"
                                    :algorithm :rs384
                                    :scopes ["system/Patient.read" "system/Coverage.read"]
                                    :audience "https://staging-oauthserver.ecwcloud.com/oauth/oauth2/token"
                                    :token-url "https://staging-oauthserver.ecwcloud.com/oauth/oauth2/token"
                                    :private-key-set {:keys [{:kty "RSA"
                                                              :kid "Fd5l9-DGsmXYOHu95FMZT4qG2WoyuLtklsEexkvXdzk"
                                                              :n "u1WhN..."
                                                              :e "AQAB"
                                                              :d "G4n7X..."
                                                              :p "9_3A1..."
                                                              :q "8_mP4..."}
                                                             {:kty "RSA"
                                                              :kid "other-key-id"
                                                              :n "abc..."
                                                              :e "AQAB"
                                                              :d "xyz..."}]}}]}}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "13. Operations with explicit :auth? flag (true and false)"
    (let [config {:domain :eclinicalworks/tenant-auth-flag
                  :base-url "https://api.eclinicalworks.com/v2"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :basic-auth :username "u" :password "p"}]}
                  :operations [{:name :get-metadata
                                :method :get
                                :path "metadata"
                                :auth? false
                                :description "Public endpoint without auth"}
                               {:name :get-patient
                                :method :get
                                :path "Patient/123"
                                :auth? true
                                :description "Protected endpoint with auth"}
                               {:name :get-observation
                                :method :get
                                :path "Observation/456"
                                :description "Endpoint without explicit auth? (should default to true)"}]}]
      (is (= config (schema/validate-adapter-config config))))))

;; =============================================================================
;; Invalid Configurations Tests (Schema Failures)
;; =============================================================================

(deftest invalid-adapter-config-test
  (testing "Missing mandatory translation middleware (empty vector)"
    (let [config {:domain :eclinicalworks/test-tenant
                  :base-url "https://api.com"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares []
                  :auth {:initial [{:type :basic-auth :username "u" :password "p"}]}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid Adapter configuration"
           (schema/validate-adapter-config config)))))

  (testing "Domain missing its namespace (violates multitenant routing design)"
    (let [config {:domain :flat-keyword-without-namespace
                  :base-url "https://api.com"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :basic-auth :username "u" :password "p"}]}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid Adapter configuration"
           (schema/validate-adapter-config config)))))

  (testing "Network configuration validation failures"
    (testing "Fails if :network-config is completely missing in root map"
      (let [config {:domain :eclinicalworks/test-tenant
                    :base-url "https://api.com"
                    :middlewares [mock-translation-middleware]
                    :auth {:initial [{:type :basic-auth :username "u" :password "p"}]}}]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Invalid Adapter configuration"
             (schema/validate-adapter-config config)))))

    (testing "Fails if :request-handler inside :network-config is missing"
      (let [config {:domain :eclinicalworks/test-tenant
                    :base-url "https://api.com"
                    :network-config {:retries 3 :retry-delay-ms 100}
                    :middlewares [mock-translation-middleware]
                    :auth {:initial [{:type :basic-auth :username "u" :password "p"}]}}]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Invalid Adapter configuration"
             (schema/validate-adapter-config config)))))

    (testing "Resiliency policy contradiction (:retries present without :retry-delay-ms)"
      (try
        (schema/validate-adapter-config
         {:domain :eclinicalworks/test-tenant
          :base-url "https://api.com"
          :middlewares [mock-translation-middleware]
          :auth {:initial [{:type :basic-auth :username "u" :password "p"}]}
          :network-config {:request-handler mock-http-request-handler
                           :retries 3
                           :retry-strategy :linear}})
        (is false "Expected ExceptionInfo to be thrown")
        (catch clojure.lang.ExceptionInfo ex
          (let [errors (:details (ex-data ex))]
            (is (some #(str/includes? % "If you configure :retries, you must provide :retry-delay-ms")
                      (:network-config errors)))))))

    (testing "Fails if HTTP status codes in :retry-on are out of range (100-599)"
      (let [config {:domain :eclinicalworks/test-tenant
                    :base-url "https://api.com"
                    :middlewares [mock-translation-middleware]
                    :auth {:initial [{:type :basic-auth :username "u" :password "p"}]}
                    :network-config {:request-handler mock-http-request-handler
                                     :retry-on [99 600]}}]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Invalid Adapter configuration"
             (schema/validate-adapter-config config))))))

  (testing "Fails when :private-key is completely omitted"
    (let [config {:domain :eclinicalworks/test-tenant
                  :base-url "https://api.com"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :smart-on-fhir/backend-services
                                    :client-id "client-123"
                                    :key-id "key-123"
                                    :algorithm :rs256
                                    :scopes ["system/*.read"]
                                    :audience "https://api.com"
                                    :token-url "https://auth.com"}]}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid Adapter configuration"
           (schema/validate-adapter-config config)))))

  (testing "SMART on FHIR: Rejects when both :private-key and :private-key-set are provided"
    (let [config {:domain :eclinicalworks/test-tenant
                  :base-url "https://api.com"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :smart-on-fhir/backend-services
                                    :client-id "client-123"
                                    :key-id "key-123"
                                    :algorithm :rs256
                                    :scopes ["system/*.read"]
                                    :audience "https://api.com"
                                    :token-url "https://auth.com"
                                    :private-key "-----BEGIN PRIVATE KEY-----\nMIIE..."
                                    :private-key-set {:keys [{:kty "RSA" :kid "key-123"}]}}]}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid Adapter configuration"
           (schema/validate-adapter-config config)))))

  (testing "SMART on FHIR: Rejects JWKS with a JWK missing mandatory :kid field"
    (let [config {:domain :eclinicalworks/test-tenant
                  :base-url "https://api.com"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :smart-on-fhir/backend-services
                                    :client-id "client-123"
                                    :key-id "key-123"
                                    :algorithm :rs256
                                    :scopes ["system/*.read"]
                                    :audience "https://api.com"
                                    :token-url "https://auth.com"
                                    :private-key-set {:keys [{:kty "RSA" :n "abc" :e "AQAB"}]}}]}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid Adapter configuration"
           (schema/validate-adapter-config config)))))

  (testing "Operation configuration contains a blank string path segment"
    (try
      (schema/validate-adapter-config
       {:domain :eclinicalworks/test-tenant
        :base-url "https://api.com"
        :network-config {:request-handler mock-http-request-handler}
        :middlewares [mock-translation-middleware]
        :auth {:initial [{:type :basic-auth :username "u" :password "p"}]}
        :operations [{:name :get-patient
                      :method :get
                      :path ["v1/Patient" "    " :id]}]})
      (is false "Expected ExceptionInfo due to blank string path segment")
      (catch clojure.lang.ExceptionInfo ex
        (let [errors (:details (ex-data ex))
              path-errors (get-in errors [:operations 0 :path])]
          (is (str/includes? (str path-errors) "operation-path must be a non-blank string or a vector of valid segments"))))))

  (testing "Operation configuration contains an invalid static string path (starts or ends with slash)"
    (try
      (schema/validate-adapter-config
       {:domain :eclinicalworks/test-tenant
        :base-url "https://api.com"
        :network-config {:request-handler mock-http-request-handler}
        :middlewares [mock-translation-middleware]
        :auth {:initial [{:type :basic-auth :username "u" :password "p"}]}
        :operations [{:name :get-invalid-static-path
                      :method :get
                      :path "/v1/Patient/"}]})
      (is false "Expected ExceptionInfo due to invalid static string path slashes")
      (catch clojure.lang.ExceptionInfo ex
        (let [errors (:details (ex-data ex))
              path-errors (get-in errors [:operations 0 :path])]
          (is (str/includes? (str path-errors) "operation-path must be a non-blank string or a vector of valid segments"))))))

  (testing "URL Validation: Reject trailing slashes in base-url and auth properties"
    (testing "Fails if base-url contains a trailing slash"
      (let [config {:domain :eclinicalworks/test-tenant
                    :base-url "https://api.com/v1/"
                    :network-config {:request-handler mock-http-request-handler}
                    :middlewares [mock-translation-middleware]
                    :auth {:initial [{:type :basic-auth :username "u" :password "p"}]}}]
        (try
          (schema/validate-adapter-config config)
          (is false "Expected ExceptionInfo due to trailing slash in base-url")
          (catch clojure.lang.ExceptionInfo ex
            (let [errors (:details (ex-data ex))]
              (is (some #(clojure.string/includes? % "base-url must be a valid URL without a trailing slash")
                        (:base-url errors))))))))

    (testing "Fails if auth token-url contains a trailing slash"
      (let [config {:domain :eclinicalworks/test-tenant
                    :base-url "https://api.com/v1"
                    :network-config {:request-handler mock-http-request-handler}
                    :middlewares [mock-translation-middleware]
                    :auth {:initial [{:type :oauth2
                                      :token-url "https://auth.com/token/"
                                      :grant-type "client_credentials"
                                      :client-id "id"
                                      :client-secret "secret"}]}}]
        (try
          (schema/validate-adapter-config config)
          (is false "Expected ExceptionInfo due to trailing slash in token-url")
          (catch clojure.lang.ExceptionInfo ex
            (let [errors (:details (ex-data ex))
                  auth-errors (get-in errors [:auth :initial 0])]
              (is (clojure.string/includes? (str auth-errors) "token-url must be a valid URL without a trailing slash"))))))))

  (testing "Operation configuration: Reject path segments starting or ending with \"/\""
    (let [config {:domain :eclinicalworks/test-tenant
                  :base-url "https://api.com/v1"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :basic-auth :username "u" :password "p"}]}
                  :operations [{:name :get-patient
                                :method :get
                                :path ["/v1" "Patient/"]}]}]
      (try
        (schema/validate-adapter-config config)
        (is false "Expected ExceptionInfo due to invalid slashes in path segments")
        (catch clojure.lang.ExceptionInfo ex
          (let [errors (:details (ex-data ex))
                path-errors (get-in errors [:operations 0 :path])]
            (is (some #(clojure.string/includes? % "operation-path must be a non-blank string or a vector of valid segments")
                      path-errors)))))))

  (testing "Independent :normalize layer validation failures"
    (testing "Fails if syntax type is not allowed (e.g. passing a raw number instead of path/key/vector)"
      (try
        (schema/validate-adapter-config
         {:domain :eclinicalworks/test-tenant
          :base-url "https://api.com/v1"
          :network-config {:request-handler mock-http-request-handler}
          :middlewares [mock-translation-middleware]
          :auth {:initial [{:type    :normalize
                            :token   12345}]}})
        (is false "Expected ExceptionInfo due to invalid token extraction value")
        (catch clojure.lang.ExceptionInfo ex
          (let [errors (get-in (ex-data ex) [:details :auth :initial 0])]
            (is (some? (:token errors)))))))

    (testing "Fails if :normalize fields are malformed structures (like unpermitted raw maps)"
      (try
        (schema/validate-adapter-config
         {:domain :eclinicalworks/test-tenant
          :base-url "https://api.com/v1"
          :network-config {:request-handler mock-http-request-handler}
          :middlewares [mock-translation-middleware]
          :auth {:initial [{:type    :normalize
                            :token   {:path [:body :token]}}]}})
        (is false "Expected ExceptionInfo due to map structure in normalize layer")
        (catch clojure.lang.ExceptionInfo ex
          (let [errors (get-in (ex-data ex) [:details :auth :initial 0])]
            (is (some? (:token errors))))))))

  (testing "Operation configuration: Reject non-boolean values for :auth?"
    (let [config {:domain :eclinicalworks/test-tenant
                  :base-url "https://api.com/v1"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :basic-auth :username "u" :password "p"}]}
                  :operations [{:name :get-patient
                                :method :get
                                :path "Patient/123"
                                :auth? "yes"}]}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid Adapter configuration"
           (schema/validate-adapter-config config))))))

;; =============================================================================
;; AdapterInstance Tests
;; =============================================================================

(deftest validate-adapter-instance-test
  (testing "1. Valid instance generated with authentication"
    (let [config {:domain :eclinicalworks/tenant-alpha
                  :base-url "https://fhir.ecw.com/v1/fhir"
                  :auth {:initial [{:type :basic-auth :username "u" :password "p"}]}
                  :operations [{:name :search-patient :path ["v1" :id] :method :get}]}
          instance (build-mock-instance config)]
      (is (= instance (schema/validate-adapter-instance instance)))))

  (testing "2. Valid instance generated without authentication (No :auth block)"
    (let [config {:domain :hapi-fhir/public-sandbox
                  :base-url "https://hapi.fhir.org/baseR4"
                  :operations [{:name :read-observation :path ["v1" :id] :method :get}]}
          instance (build-mock-instance config)]
      (is (= instance (schema/validate-adapter-instance instance)))))

  (testing "3. Error: Fails if :auth :state is not a real IAtom"
    (let [instance {:ehr-adapter/domain :eclinicalworks/tenant-fail
                    :ehr-adapter/base-url "https://fhir.ecw.com/v1/fhir"
                    :ehr-adapter/request-handler  mock-http-request-handler
                    :ehr-adapter/auth {:state {:not-an-atom true}
                                       :refresh-fn mock-get-token-fn}
                    :ehr-adapter/operations {:search-patient {:handler (fn [_ _] {})}}}]
      (try
        (schema/validate-adapter-instance instance)
        (is false "Expected ExceptionInfo to be thrown because :state is not an Atom")
        (catch clojure.lang.ExceptionInfo ex
          (let [errors (:details (ex-data ex))]
            (is (some #(str/includes? % "auth state must be a Clojure Atom (IAtom)")
                      (get-in errors [:ehr-adapter/auth :state]))))))))

  (testing "4. Error: Fails if an operation member is not an executable function"
    (let [instance {:ehr-adapter/domain :eclinicalworks/tenant-fail
                    :ehr-adapter/base-url "https://fhir.ecw.com/v1/fhir"
                    :ehr-adapter/request-handler  mock-http-request-handler
                    :ehr-adapter/auth {:state (atom {})
                                       :refresh-fn mock-get-token-fn}
                    :ehr-adapter/operations {:search-patient {:handler {:this-is-not "a function"}}}}]
      (try
        (schema/validate-adapter-instance instance)
        (is false "Expected ExceptionInfo due to uncompiled operation map")
        (catch clojure.lang.ExceptionInfo ex
          (let [errors (:details (ex-data ex))
                op-errors (get-in errors [:ehr-adapter/operations :search-patient :handler])]
            (is (str/includes? (str op-errors) "the handler must be a compiled Clojure function"))))))))

;; =============================================================================
;; HttpRequest Schema Tests
;; =============================================================================

(deftest valid-http-request-test
  (testing "1. Minimal valid request (only required fields)"
    (let [req {:method :get
               :url "https://api.example.com/v1/Patient"}]
      (is (= req (schema/validate-http-request req)))))

  (testing "2. Full request adjusted to the new minimal, synchronous schema"
    (let [req {:method :post
               :url "https://api.example.com/v1/Patient"
               :headers {"Authorization" "Bearer token-123"
                         "X-Custom" "value"}
               :body {:name "John" :id 123}
               :content-type {:code :json :properties {"charset" "utf-8"}}
               :accept       {:code :json}
               :query-params {"page" 1 "limit" 10}
               :timeout-ms 5000}]
      (is (= req (schema/validate-http-request req)))))

  (testing "3. Request using sugar syntax (plain keywords) for media types"
    (let [req {:method :post
               :url "https://api.example.com/v1/Patient"
               :body {:name "John" :id 123}
               :content-type :json
               :accept       :fhir/json}]
      (is (= req (schema/validate-http-request req)))))

  (testing "4. POST with form-params using plain keyword"
    (let [req {:method :post
               :url "https://auth.example.com/token"
               :form-params {"grant_type" "client_credentials"
                             "client_id" "abc"}
               :content-type :form-url-encoded}]
      (is (= req (schema/validate-http-request req)))))

  (testing "5. Headers accept heterogeneous values"
    (let [req {:method :get
               :url "https://api.example.com/v1/Group"
               :headers {:Authorization "Bearer xyz"
                         "X-Int-Header" 42}}]
      (is (= req (schema/validate-http-request req))))))

;; =============================================================================
;; JWK Schemas Tests
;; =============================================================================

(deftest jwk-schemas-test
  (testing "1. Valid JWK with minimal required fields (kty and kid)"
    (let [jwk {:kty "RSA" :kid "key-1"}]
      (is (= jwk (schema/validate-jwk jwk)))))

  (testing "2. Valid JWK with additional cryptographic fields (open schema)"
    (let [jwk {:kty "RSA"
               :kid "key-1"
               :alg "RS256"
               :n "u1WhN..."
               :e "AQAB"
               :d "G4n7X..."
               :p "9_3A1..."
               :q "8_mP4..."}]
      (is (= jwk (schema/validate-jwk jwk)))))

  (testing "3. Valid JWKS with multiple keys"
    (let [jwks {:keys [{:kty "RSA" :kid "key-1" :n "abc" :e "AQAB"}
                       {:kty "RSA" :kid "key-2" :n "xyz" :e "AQAB" :d "def"}]}]
      (is (= jwks (schema/validate-jwks jwks)))))

  (testing "4. Invalid JWK structures (Expected ExceptionInfo)"
    (testing "Fails JWK if :kid is missing"
      (let [bad-jwk {:kty "RSA" :n "u1WhN" :e "AQAB"}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (schema/validate-jwk bad-jwk)))))

    (testing "Fails JWK if :kty is blank"
      (let [bad-jwk {:kty "   " :kid "key-1"}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (schema/validate-jwk bad-jwk)))))

    (testing "Fails JWKS if :keys is not a vector"
      (let [bad-jwks {:keys {:kty "RSA" :kid "key-1"}}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (schema/validate-jwks bad-jwks)))))

    (testing "Fails JWKS if any key inside :keys is invalid"
      (let [bad-jwks {:keys [{:kty "RSA" :kid "key-1"}
                             {:kty "RSA"}]}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (schema/validate-jwks bad-jwks)))))))
