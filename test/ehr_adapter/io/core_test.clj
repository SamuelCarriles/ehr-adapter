(ns ehr-adapter.io.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.io.core :as io]))

(defn- mock-http-request-handler [_req]
  {:status 200 :body "OK"})

(defn- mock-translation-middleware [handler]
  (fn [req] (handler req)))

(defn- mock-before-retry [ctx] ctx)

(defn- mock-custom-auth-handler [_layer _http-client]
  (fn [req] req))

;; =============================================================================
;; path->ref Tests
;; =============================================================================

(deftest path->ref-test
  (testing "Builds a :ref keyword from a simple path of two keywords"
    (is (= :ref/network-config.request-handler
           (io/path->ref [:network-config :request-handler]))))

  (testing "Builds a :ref keyword from a path containing a numeric index"
    (is (= :ref/auth.initial.1.handler
           (io/path->ref [:auth :initial 1 :handler]))))

  (testing "Builds a :ref keyword from a single-element path"
    (is (= :ref/middlewares
           (io/path->ref [:middlewares]))))

  (testing "Returns nil when given an empty path"
    (is (nil? (io/path->ref [])))))

;; =============================================================================
;; walk Tests
;; =============================================================================

(deftest walk-test
  (testing "A bare function is replaced by its corresponding :ref"
    (is (= :ref/network-config.request-handler
           (io/walk mock-http-request-handler [:network-config :request-handler]))))

  (testing "A map without functions is returned unchanged"
    (let [m {:username "u" :password "p"}]
      (is (= m (io/walk m [:auth :initial 0])))))

  (testing "A map with a function nested inside is replaced by its :ref, other keys untouched"
    (let [m {:type :custom
             :handler mock-custom-auth-handler
             :options {:request {:query-params {:sandbox true}}}}
          result (io/walk m [:auth :initial 0])]
      (is (= :custom (:type result)))
      (is (= :ref/auth.initial.0.handler (:handler result)))
      (is (= {:request {:query-params {:sandbox true}}} (:options result)))))

  (testing "A vector with a function at a specific index produces the correct indexed :ref"
    (let [v [{:type :basic-auth :username "u" :password "p"}
             {:type :custom :handler mock-custom-auth-handler}]
          result (io/walk v [:auth :initial])]
      (is (vector? result))
      (is (= {:type :basic-auth :username "u" :password "p"} (first result)))
      (is (= :ref/auth.initial.1.handler (get-in result [1 :handler])))))

  (testing "A vector with no functions inside is returned as a real vector, unchanged"
    (let [v [{:type :basic-auth :username "u" :password "p"}]
          result (io/walk v [:auth :initial])]
      (is (vector? result))
      (is (= v result))))

  (testing "Scalars (strings, numbers, keywords, nil) pass through untouched"
    (is (= "https://api.ehr.com" (io/walk "https://api.ehr.com" [:base-url])))
    (is (= 5000 (io/walk 5000 [:network-config :timeout-ms])))
    (is (= :basic-auth (io/walk :basic-auth [:auth :initial 0 :type])))
    (is (nil? (io/walk nil [:anything])))))

;; =============================================================================
;; ->serializable Tests
;; =============================================================================

(deftest ->serializable-test
  (testing "1. :middlewares is replaced as a single, whole :ref (not recursed into)"
    (let [config {:domain :eclinicalworks/tenant-alpha
                  :base-url "https://fhir.ecw.com/v1/fhir"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :basic-auth
                                    :username "integrator-user"
                                    :password "secret-pass-123"}]}}
          result (io/->serializable config)]
      (is (= :ref/middlewares (:middlewares result)))
      (is (= :eclinicalworks/tenant-alpha (:domain result)))
      (is (= "https://fhir.ecw.com/v1/fhir" (:base-url result)))))

  (testing "2. :network-config handlers are replaced by their :ref, scalar options untouched"
    (let [config {:domain :eclinicalworks/tenant-beta
                  :base-url "https://api.eclinicalworks.com/v2"
                  :network-config {:retries 3
                                   :retry-delay-ms 200
                                   :retry-strategy :exponential
                                   :retry-on [500 502 503 504]
                                   :before-retry mock-before-retry
                                   :request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :basic-auth :username "u" :password "p"}]}}
          result (io/->serializable config)]
      (is (= :ref/network-config.request-handler (get-in result [:network-config :request-handler])))
      (is (= :ref/network-config.before-retry (get-in result [:network-config :before-retry])))
      (is (= 3 (get-in result [:network-config :retries])))
      (is (= [500 502 503 504] (get-in result [:network-config :retry-on])))))

  (testing "3. Custom auth handler inside :auth :initial is replaced by an indexed :ref"
    (let [config {:domain :epic/hospital-central-prod
                  :base-url "https://epic.hospital.org/api"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :custom
                                    :handler mock-custom-auth-handler
                                    :options {:request {:query-params {:sandbox true}}}}]}}
          result (io/->serializable config)]
      (is (= :ref/auth.initial.0.handler (get-in result [:auth :initial 0 :handler])))
      (is (= :custom (get-in result [:auth :initial 0 :type])))
      (is (= {:request {:query-params {:sandbox true}}} (get-in result [:auth :initial 0 :options])))))

  (testing "4. Custom auth handler inside :auth :refresh is replaced by its own indexed :ref, independent of :initial"
    (let [config {:domain :epic/sandbox-smart-pem
                  :base-url "https://fhir.epic.com/interconnect-fhir-oauth/api/FHIR/R4"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :basic-auth :username "u" :password "p"}]
                         :refresh [{:type :custom :handler mock-custom-auth-handler}]}}
          result (io/->serializable config)]
      (is (= {:type :basic-auth :username "u" :password "p"}
             (get-in result [:auth :initial 0])))
      (is (= :ref/auth.refresh.0.handler (get-in result [:auth :refresh 0 :handler])))))

  (testing "5. A non-custom auth layer (no functions inside) is left completely untouched"
    (let [config {:domain :eclinicalworks/tenant-beta
                  :base-url "https://api.eclinicalworks.com/v2"
                  :network-config {:request-handler mock-http-request-handler}
                  :middlewares [mock-translation-middleware]
                  :auth {:initial [{:type :oauth2
                                    :token-url "https://auth.eclinicalworks.com/oauth/token"
                                    :grant-type "client_credentials"
                                    :client-id "ecw-client-id-prod"
                                    :client-secret "ecw-client-secret-secure-123"}]}}
          result (io/->serializable config)]
      (is (= (get-in config [:auth :initial]) (get-in result [:auth :initial])))))

  (testing "6. Config without :auth or :network-config keys is left unaffected by their cond-> clauses"
    (let [config {:domain :hapi-fhir/public-sandbox
                  :base-url "https://hapi.fhir.org/baseR4"
                  :middlewares [mock-translation-middleware]}
          result (io/->serializable config)]
      (is (= :ref/middlewares (:middlewares result)))
      (is (nil? (:auth result)))
      (is (nil? (:network-config result)))))

  (testing "7. Full integration: middlewares, network-config and auth (initial + refresh) all transformed together"
    (let [config {:domain :epic/sandbox-smart-pem
                  :base-url "https://fhir.epic.com/interconnect-fhir-oauth/api/FHIR/R4"
                  :network-config {:request-handler mock-http-request-handler
                                   :before-retry mock-before-retry}
                  :middlewares [mock-translation-middleware mock-translation-middleware]
                  :auth {:initial [{:type :smart-on-fhir/backend-services
                                    :client-id "epic-client-123"
                                    :key-id "key-prod-1"
                                    :algorithm :rs256
                                    :scopes ["system/Patient.read"]
                                    :audience "https://fhir.epic.com"
                                    :token-url "https://fhir.epic.com/interconnect-fhir-oauth/oauth2/token"
                                    :private-key "-----BEGIN PRIVATE KEY-----..."}
                                   {:type :custom :handler mock-custom-auth-handler}]
                         :refresh [{:type :oauth2
                                    :token-url "https://fhir.epic.com/interconnect-fhir-oauth/oauth2/token"
                                    :grant-type "refresh_token"
                                    :client-id "epic-client-123"
                                    :client-secret "client-secret"}]}}
          result (io/->serializable config)]
      (is (= :ref/middlewares (:middlewares result)))
      (is (= :ref/network-config.request-handler (get-in result [:network-config :request-handler])))
      (is (= :ref/network-config.before-retry (get-in result [:network-config :before-retry])))
      (is (= "epic-client-123" (get-in result [:auth :initial 0 :client-id])))
      (is (= :ref/auth.initial.1.handler (get-in result [:auth :initial 1 :handler])))
      (is (= "refresh_token" (get-in result [:auth :refresh 0 :grant-type]))))))
