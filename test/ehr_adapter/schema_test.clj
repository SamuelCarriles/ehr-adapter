(ns ehr-adapter.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.schema :as schema]
            [clojure.string :as str]))

(defn- mock-http-client [_req]
  {:status 200 :body "OK"})

(defn- mock-translation-middleware [handler]
  (fn [req]
    (let [coerced-req (assoc req :coerced? true)
          response (handler coerced-req)]
      (assoc response :normalized? true))))

(defn- mock-custom-auth-handler [_data]
  (fn [req] req))

(defn- mock-get-token-fn []
  "mock-bearer-token-123")

(defn- build-mock-instance [config]
  {:domain    (:domain config)
   :base-url  (:base-url config)
   :auth      {:state     (atom {:token "xyz"})
               :get-token mock-get-token-fn
               :config    (:auth config)}
   :operations (into {} (map (fn [op] [(:name op) (fn [data] {:status 200 :executed (:name op) :data data})])
                             (:operations config)))})

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
                          :client-id      "ecw-client-id-prod"
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
      (is (= config (schema/validate-adapter-config config)))))

  (testing "4. OAuth2 configuration with a valid and complex declarative :normalize map (Sugar + ExtractionPath)"
    (let [config {:domain :eclinicalworks/tenant-normalize-valid
                  :base-url "https://api.eclinicalworks.com/v2"
                  :http-client-fn mock-http-client
                  :middlewares [mock-translation-middleware]
                  :auth [{:type          :oauth2
                          :token-url     "https://auth.eclinicalworks.com/oauth/token"
                          :grant-type    "client_credentials"
                          :client-id      "ecw-id"
                          :client-secret "ecw-secret"}
                         {:type          :normalize
                          :token          :access_token
                          :token-type    "token_type"
                          :expires-in    [:body :meta :expires 0 :sec]
                          :refresh-token [:refresh_key]}]}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "5. Normalization layer completely implicit (Malli should accept empty map since all keys are optional)"
    (let [config {:domain :eclinicalworks/tenant-normalize-empty
                  :base-url "https://api.eclinicalworks.com/v2"
                  :http-client-fn mock-http-client
                  :middlewares [mock-translation-middleware]
                  :auth [{:type :oauth2
                          :token-url "https://auth.eclinicalworks.com/oauth/token"
                          :grant-type "client_credentials"
                          :client-id "id"
                          :client-secret "secret"}
                         {:type :normalize}]}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "6. Global :payload validation using heterogeneous structures ([:map-of :any :any])"
    (let [config {:domain :advancedmd/tenant-payload-heterogeneous
                  :base-url "https://api.advancedmd.com/v2"
                  :http-client-fn mock-http-client
                  :middlewares [mock-translation-middleware]
                  :auth [{:type          :oauth2
                          :token-url     "https://api.advancedmd.com/oauth2/token"
                          :grant-type    "client_credentials"
                          :client-id      "adv-id"
                          :client-secret "adv-secret"
                          :options       {:request {:content-type {:code :unsupported :raw-mime "custom-content-type"}
                                                    :form-params {"alg" "RS256"
                                                                  :custom-id 12345}
                                                    :headers {"X-Partner-Id" "partner-99"
                                                              :X-Context-Id  "ctx-abc"}
                                                    :query-params {:sandbox true}}}}]}]
      (is (= config (schema/validate-adapter-config config)))))

  (testing "7. Dynamic/Hybrid :basic-auth supporting optional :token-url and base :payload (AdvancedMD use-case)"
    (let [config {:domain :advancedmd/tenant-dynamic-basic
                  :base-url "https://api.advancedmd.com/v2"
                  :http-client-fn mock-http-client
                  :middlewares [mock-translation-middleware]
                  :auth [{:type      :basic-auth
                          :username  "adv-integrator"
                          :password  "super-secure-pass"
                          :options   {:request {:method :post
                                                :url "https://api.advancedmd.com/oauth2/token"
                                                :form-params {:alg "RS256"}}}}]}]
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
          (is (str/includes? (str path-errors) "each segment in operation-path must be a keyword or a non-blank string"))))))

  (testing "URL Validation: Reject trailing slashes in base-url and auth properties"
    (testing "Fails if base-url contains a trailing slash"
      (let [config {:domain :eclinicalworks/test-tenant
                    :base-url "https://api.com/v1/"
                    :http-client-fn mock-http-client
                    :middlewares [mock-translation-middleware]
                    :auth [{:type :api-key :api-key "123"}]}]
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
                    :http-client-fn mock-http-client
                    :middlewares [mock-translation-middleware]
                    :auth [{:type :oauth2
                            :token-url "https://auth.com/token/"
                            :grant-type "client_credentials"
                            :client-id "id"
                            :client-secret "secret"}]}]
        (try
          (schema/validate-adapter-config config)
          (is false "Expected ExceptionInfo due to trailing slash in token-url")
          (catch clojure.lang.ExceptionInfo ex
            (let [errors (:details (ex-data ex))
                  auth-errors (first (:auth errors))]
              (is (clojure.string/includes? (str auth-errors) "token-url must be a valid URL without a trailing slash"))))))))

  (testing "Operation configuration: Reject path segments starting or ending with \"/\""
    (let [config {:domain :eclinicalworks/test-tenant
                  :base-url "https://api.com/v1"
                  :http-client-fn mock-http-client
                  :middlewares [mock-translation-middleware]
                  :auth [{:type :api-key :api-key "123"}]
                  :operations [{:name :get-patient
                                :method :get
                                :path ["/v1" "Patient/"]}]}]
      (try
        (schema/validate-adapter-config config)
        (is false "Expected ExceptionInfo due to invalid slashes in path segments")
        (catch clojure.lang.ExceptionInfo ex
          (let [errors (:details (ex-data ex))
                path-errors (get-in errors [:operations 0 :path])]
            (is (some #(clojure.string/includes? % "each segment in operation-path must be a keyword or a non-blank string, and can not start or end with")
                      path-errors)))))))

  (testing "Independent :normalize layer validation failures"
    (testing "Fails if syntax type is not allowed (e.g. passing a raw number instead of path/key/vector)"
      (try
        (schema/validate-adapter-config
         {:domain :eclinicalworks/test-tenant
          :base-url "https://api.com/v1"
          :http-client-fn mock-http-client
          :middlewares [mock-translation-middleware]
          :auth [{:type    :normalize
                  :token   12345}]})
        (is false "Expected ExceptionInfo due to invalid token extraction value")
        (catch clojure.lang.ExceptionInfo ex
          (let [errors (-> ex ex-data :details :auth first)]
            (is (some? (:token errors)))))))

    (testing "Fails if :normalize fields are malformed structures (like unpermitted raw maps)"
      (try
        (schema/validate-adapter-config
         {:domain :eclinicalworks/test-tenant
          :base-url "https://api.com/v1"
          :http-client-fn mock-http-client
          :middlewares [mock-translation-middleware]
          :auth [{:type    :normalize
                  :token   {:path [:body :token]}}]})
        (is false "Expected ExceptionInfo due to map structure in normalize layer")
        (catch clojure.lang.ExceptionInfo ex
          (let [errors (-> ex ex-data :details :auth first)]
            (is (some? (:token errors)))))))))

;; =============================================================================
;; AdapterInstance Tests
;; =============================================================================

(deftest validate-adapter-instance-test
  (testing "1. Valid instance generated"
    (let [config {:domain :eclinicalworks/tenant-alpha
                  :base-url "https://fhir.ecw.com/v1/fhir"
                  :auth [{:type :api-key :api-key "secret-123"}]
                  :operations [{:name :search-patient :path ["v1" :id] :method :get}]}
          instance (build-mock-instance config)]
      (is (= instance (schema/validate-adapter-instance instance)))))

  (testing "2. Error: Fails if :auth :state is not a real IAtom"
    (let [instance {:domain    :eclinicalworks/tenant-fail
                    :base-url  "https://fhir.ecw.com/v1/fhir"
                    :auth      {:state     {:not-an-atom true}
                                :get-token mock-get-token-fn
                                :config    [{:type :api-key :api-key "123"}]}
                    :operations {:search-patient (fn [_] {})}}]
      (try
        (schema/validate-adapter-instance instance)
        (is false "Expected ExceptionInfo to be thrown because :state is not an Atom")
        (catch clojure.lang.ExceptionInfo ex
          (let [errors (:details (ex-data ex))]
            (is (some #(str/includes? % "auth state must be a Clojure Atom (IAtom)")
                      (get-in errors [:auth :state]))))))))

  (testing "3. Error: Fails if an operation member is not an executable function"
    (let [instance {:domain    :eclinicalworks/tenant-fail
                    :base-url  "https://fhir.ecw.com/v1/fhir"
                    :auth      {:state     (atom {})
                                :get-token mock-get-token-fn
                                :config    [{:type :api-key :api-key "123"}]}
                    :operations {:search-patient {:this-is-not "a function"}}}]
      (try
        (schema/validate-adapter-instance instance)
        (is false "Expected ExceptionInfo due to uncompiled operation map")
        (catch clojure.lang.ExceptionInfo ex
          (let [errors (:details (ex-data ex))
                op-errors (get-in errors [:operations :search-patient])]
            (is (str/includes? (str op-errors) "each operation must be a compiled Clojure function"))))))))

;; =============================================================================
;; HttpRequest Schema Tests
;; =============================================================================

(deftest valid-http-request-test
  (testing "1. Minimal valid request (only required fields)"
    (let [req {:method :get
               :url "https://api.example.com/v1/Patient"}]
      (is (= req (schema/validate-http-request req)))))

  (testing "2. Full request using explicit structured maps for media types"
    (let [req {:method :post
               :url "https://api.example.com/v1/Patient"
               :headers {"Authorization" "Bearer token-123"
                         "X-Custom" "value"}
               :body {:name "John" :id 123}
               :content-type {:code :json :properties {"charset" "utf-8"}}
               :accept       {:code :json}
               :query-params {"page" 1 "limit" 10}
               :timeout-ms 5000
               :async false
               :throw-exceptions false}]
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
