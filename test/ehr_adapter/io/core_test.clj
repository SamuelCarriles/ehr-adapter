(ns ehr-adapter.io.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [ehr-adapter.io.core :as core]
            [ehr-adapter.io.transit :as transit]))

;; =============================================================================
;; Mocks & Fixtures
;; =============================================================================

(defn- mock-http-request-handler [_req]
  {:status 200 :body "OK"})

(defn- mock-translation-middleware [handler]
  (fn [req] (handler req)))

(defn- mock-before-retry [ctx] ctx)

(defn- mock-custom-auth-handler [_layer _http-client]
  (fn [req] req))

(def ^:private test-dir "test/test-exports")

(defn- clean-test-dir-fixture [f]
  (f)
  (when (.exists (io/file test-dir))
    (doseq [file (reverse (file-seq (io/file test-dir)))]
      (io/delete-file file true))))

(use-fixtures :each clean-test-dir-fixture)

(def ^:private base-config
  {:domain :eclinicalworks/tenant-alpha
   :base-url "https://fhir.ecw.com/v1/fhir"
   :network-config {:request-handler mock-http-request-handler}
   :middlewares [mock-translation-middleware]
   :auth {:initial [{:type :custom :handler mock-custom-auth-handler}]}})

;; =============================================================================
;; path->ref Tests
;; =============================================================================

(deftest path->ref-test
  (testing "Builds a :ref keyword from a simple path of two keywords"
    (is (= :ref/network-config.request-handler
           (core/path->ref [:network-config :request-handler]))))

  (testing "Builds a :ref keyword from a path containing a numeric index"
    (is (= :ref/auth.initial.1.handler
           (core/path->ref [:auth :initial 1 :handler]))))

  (testing "Builds a :ref keyword from a single-element path"
    (is (= :ref/middlewares
           (core/path->ref [:middlewares]))))

  (testing "Returns nil when given an empty path"
    (is (nil? (core/path->ref [])))))

;; =============================================================================
;; walk Tests
;; =============================================================================

(deftest walk-test
  (testing "A bare function is replaced by its corresponding :ref"
    (is (= :ref/network-config.request-handler
           (core/walk mock-http-request-handler [:network-config :request-handler]))))

  (testing "A map without functions is returned unchanged"
    (let [m {:username "u" :password "p"}]
      (is (= m (core/walk m [:auth :initial 0])))))

  (testing "A map with a function nested inside is replaced by its :ref, other keys untouched"
    (let [m {:type :custom
             :handler mock-custom-auth-handler
             :options {:request {:query-params {:sandbox true}}}}
          result (core/walk m [:auth :initial 0])]
      (is (= :custom (:type result)))
      (is (= :ref/auth.initial.0.handler (:handler result)))
      (is (= {:request {:query-params {:sandbox true}}} (:options result)))))

  (testing "A vector with a function at a specific index produces the correct indexed :ref"
    (let [v [{:type :basic-auth :username "u" :password "p"}
             {:type :custom :handler mock-custom-auth-handler}]
          result (core/walk v [:auth :initial])]
      (is (vector? result))
      (is (= {:type :basic-auth :username "u" :password "p"} (first result)))
      (is (= :ref/auth.initial.1.handler (get-in result [1 :handler])))))

  (testing "A vector with no functions inside is returned as a real vector, unchanged"
    (let [v [{:type :basic-auth :username "u" :password "p"}]
          result (core/walk v [:auth :initial])]
      (is (vector? result))
      (is (= v result))))

  (testing "Scalars (strings, numbers, keywords, nil) pass through untouched"
    (is (= "https://api.ehr.com" (core/walk "https://api.ehr.com" [:base-url])))
    (is (= 5000 (core/walk 5000 [:network-config :timeout-ms])))
    (is (= :basic-auth (core/walk :basic-auth [:auth :initial 0 :type])))
    (is (nil? (core/walk nil [:anything])))))

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
          result (core/->serializable config)]
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
          result (core/->serializable config)]
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
          result (core/->serializable config)]
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
          result (core/->serializable config)]
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
          result (core/->serializable config)]
      (is (= (get-in config [:auth :initial]) (get-in result [:auth :initial])))))

  (testing "6. Config without :auth or :network-config keys is left unaffected by their cond-> clauses"
    (let [config {:domain :hapi-fhir/public-sandbox
                  :base-url "https://hapi.fhir.org/baseR4"
                  :middlewares [mock-translation-middleware]}
          result (core/->serializable config)]
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
          result (core/->serializable config)]
      (is (= :ref/middlewares (:middlewares result)))
      (is (= :ref/network-config.request-handler (get-in result [:network-config :request-handler])))
      (is (= :ref/network-config.before-retry (get-in result [:network-config :before-retry])))
      (is (= "epic-client-123" (get-in result [:auth :initial 0 :client-id])))
      (is (= :ref/auth.initial.1.handler (get-in result [:auth :initial 1 :handler])))
      (is (= "refresh_token" (get-in result [:auth :refresh 0 :grant-type]))))))

;; =============================================================================
;; export! Tests (in-memory, no :dir)
;; =============================================================================

(deftest export!-in-memory-test
  (testing "Without :dir, :edn format returns an EDN string with refs in place of functions"
    (let [result (core/export! base-config {:format :edn})
          parsed (edn/read-string result)]
      (is (string? result))
      (is (= :ref/middlewares (:middlewares parsed)))
      (is (= :ref/auth.initial.0.handler (get-in parsed [:auth :initial 0 :handler])))
      (is (= :ref/network-config.request-handler (get-in parsed [:network-config :request-handler])))))

  (testing "Without :dir, :transit format returns a transit+json string with refs in place of functions"
    (let [result (core/export! base-config {:format :transit})
          parsed (transit/<-string result)]
      (is (string? result))
      (is (= :ref/middlewares (:middlewares parsed)))
      (is (= :ref/auth.initial.0.handler (get-in parsed [:auth :initial 0 :handler])))))

  (testing "Throws when the config is invalid (e.g. missing :network-config)"
    (let [invalid-config (dissoc base-config :network-config)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid Adapter configuration"
                            (core/export! invalid-config {:format :edn}))))))

;; =============================================================================
;; export! Tests (writing to disk)
;; =============================================================================

(deftest export!-to-disk-test
  (testing "With :dir but no :file-name, generates a domain+timestamp file and creates the directory"
    (let [file (core/export! base-config {:format :edn :dir test-dir})]
      (is (instance? java.io.File file))
      (is (.exists file))
      (is (.exists (io/file test-dir)))
      (is (re-find #"^eclinicalworks\.tenant-alpha-\d{8}-\d{6}\.edn$" (.getName file)))))

  (testing "With only :file-name (no :dir), writes to current directory"
    (let [file-name "test-current-dir.edn"
          file (core/export! base-config {:format :edn :file-name file-name})]
      (try
        (is (instance? java.io.File file))
        (is (.exists file))
        (is (= file-name (.getName file)))
        (finally
          (io/delete-file file true)))))

  (testing "With explicit :file-name, the file is written with that exact name"
    (let [file (core/export! base-config {:format :edn :dir test-dir :file-name "custom.edn"})]
      (is (= "custom.edn" (.getName file)))
      (is (.exists file))))

  (testing "Written file content can be read back and matches the in-memory export"
    (let [file (core/export! base-config {:format :edn :dir test-dir :file-name "roundtrip.edn"})
          file-content (slurp file)
          parsed (edn/read-string file-content)]
      (is (= :ref/middlewares (:middlewares parsed)))
      (is (= :ref/auth.initial.0.handler (get-in parsed [:auth :initial 0 :handler])))))

  (testing "Transit format written to disk produces a valid transit+json file"
    (let [file (core/export! base-config {:format :transit :dir test-dir :file-name "roundtrip.json"})
          parsed (transit/<-string (slurp file))]
      (is (= :ref/middlewares (:middlewares parsed))))))

;; =============================================================================
;; ->file-name Tests (via the public surface, indirectly through export!)
;; =============================================================================

(deftest file-name-generation-test
  (testing "Default generated file name uses domain namespace and name, separated by a dot"
    (let [file (core/export! base-config {:format :edn :dir test-dir})]
      (is (str/starts-with? (.getName file) "eclinicalworks.tenant-alpha-"))))

  (testing "Default extension matches the chosen format"
    (let [edn-file (core/export! base-config {:format :edn :dir test-dir})
          transit-file (core/export! base-config {:format :transit :dir test-dir})]
      (is (str/ends-with? (.getName edn-file) ".edn"))
      (is (str/ends-with? (.getName transit-file) ".json")))))

;; =============================================================================
;; import Tests
;; =============================================================================

(deftest import-with-file-test
  (testing "Reads back an EDN file when a java.io.File is passed directly via :file"
    (let [written (core/export! base-config {:format :edn :dir test-dir :file-name "via-file.edn"})
          result (core/import {:format :edn :file written})]
      (is (= :ref/middlewares (:middlewares result)))
      (is (= :ref/auth.initial.0.handler (get-in result [:auth :initial 0 :handler])))))

  (testing "Reads back a transit+json file when a java.io.File is passed directly via :file"
    (let [written (core/export! base-config {:format :transit :dir test-dir :file-name "via-file.json"})
          result (core/import {:format :transit :file written})]
      (is (= :ref/middlewares (:middlewares result))))))

(deftest import-with-dir-and-file-name-test
  (testing "Reads back a file located by :dir + :file-name"
    (core/export! base-config {:format :edn :dir test-dir :file-name "via-dir.edn"})
    (let [result (core/import {:format :edn :dir test-dir :file-name "via-dir.edn"})]
      (is (= :ref/middlewares (:middlewares result)))
      (is (= :ref/network-config.request-handler (get-in result [:network-config :request-handler]))))))

(deftest import-with-file-name-only-test
  (testing "Reads back a file located by :file-name alone, looked up in the current working directory"
    (let [file-name "via-cwd.edn"]
      (core/export! base-config {:format :edn :file-name file-name})
      (let [result (core/import {:format :edn :file-name file-name})]
        (is (= :ref/middlewares (:middlewares result))))
      (io/delete-file (io/file file-name) true))))

(deftest import-missing-file-test
  (testing "Throws :missing/file when the resolved file doesn't exist"
    (try
      (core/import {:format :edn :dir test-dir :file-name "does-not-exist.edn"})
      (is false "Expected ExceptionInfo to be thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :missing/file (:code data)))
          (is (= :ehr-adapter.io.core (:scope data)))
          (is (= :io-flow (:operation data)))
          (is (= "does-not-exist.edn" (get-in data [:details :file-name]))))))))

(deftest import-unsupported-format-test
  (testing "Throws :invalid/format when :format has no registered reader, even if the file exists"
    (let [written (core/export! base-config {:format :edn :dir test-dir :file-name "wrong-format.edn"})]
      (try
        (core/import {:format :yaml :file written})
        (is false "Expected ExceptionInfo to be thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :invalid/format (:code data)))
            (is (= :ehr-adapter.io.core (:scope data)))
            (is (= :io-flow (:operation data)))
            (is (= :yaml (get-in data [:details :format])))
            (is (= #{:edn :transit} (set (get-in data [:details :expected]))))))))))

(deftest export-import-roundtrip-test
  (testing "Full roundtrip: export! to disk, then import gives back the same serializable shape"
    (let [file (core/export! base-config {:format :transit :dir test-dir :file-name "roundtrip.json"})
          exported-str (slurp file)
          imported (core/import {:format :transit :file file})]
      (is (= imported (transit/<-string exported-str))))))
