(ns ehr-adapter.schema-test
  (:require [clojure.test :refer [deftest testing is]]
            [ehr-adapter.schema :as schema]))

(defn valid-config []
  {:domain :test/provider
   :base-url "https://fhir.example.com"
   :auth {:grant-type :client-secret
          :client-id "app-id"
          :client-secret "app-secret"}
   :http-client-fn fn?
   :middlewares [fn?]})

(deftest valid-configs
  (testing "minimal valid config passes"
    (let [cfg (valid-config)]
      (is (= cfg (schema/validate cfg)))))

  (testing "full config with operations and policies passes"
    (let [cfg (assoc (valid-config)
                     :operations [{:name :$export
                                   :path ["/Group" :id "$export"]
                                   :method :post
                                   :expected-status [202]
                                   :base-headers {"Prefer" "respond-async"}
                                   :description "Test export"}]
                     :policies {:timeout-ms 5000 :retries 3 :retry-on [429]})]
      (is (= cfg (schema/validate cfg)))))

  (testing "auth with JWT + extras + custom algorithm passes"
    (let [cfg (assoc-in (valid-config) [:auth]
                        {:grant-type :jwt
                         :client-id "app-id"
                         :private-key "-----BEGIN KEY-----"
                         :key-id "kid-1"
                         :algorithm "RS384"
                         :extras {:username "u" :password "p" :officekey "123"}})]
      (is (= cfg (schema/validate cfg))))))

(deftest missing-required-fields
  (doseq [field [:domain :base-url :auth :http-client-fn :middlewares]]
    (testing (str "missing " field " → throws ex-info with structured error")
      (try
        (schema/validate (dissoc (valid-config) field))
        (is false "Should have thrown an exception")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :validation/error (:type data)))
            (is (some? (get-in data [:errors field]))))))))

  (testing ":domain without namespace → fails"
    (try
      (schema/validate (assoc (valid-config) :domain :without-namespace))
      (is false "Should have thrown an exception")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :validation/error (:type data)))
          (is (some? (get-in data [:errors :domain]))))))))

(deftest invalid-types-and-enums
  (testing ":base-url is not a string"
    (try
      (schema/validate (assoc (valid-config) :base-url 123))
      (is false)
      (catch clojure.lang.ExceptionInfo e
        (is (= :validation/error (:type (ex-data e)))))))

  (testing ":path is not a vector"
    (let [cfg (assoc (valid-config) :operations [{:name :test :path "/wrong" :method :get}])]
      (try
        (schema/validate cfg)
        (is false)
        (catch clojure.lang.ExceptionInfo e
          (is (= :validation/error (:type (ex-data e))))))))

  (testing ":method outside allowed enum"
    (let [cfg (assoc (valid-config) :operations [{:name :test :path ["/test"] :method :options}])]
      (try
        (schema/validate cfg)
        (is false)
        (catch clojure.lang.ExceptionInfo e
          (is (= :validation/error (:type (ex-data e))))))))

  (testing "invalid :grant-type"
    (try
      (schema/validate (assoc-in (valid-config) [:auth :grant-type] :bearer))
      (is false)
      (catch clojure.lang.ExceptionInfo e
        (is (= :validation/error (:type (ex-data e))))))))

(deftest closed-maps-behavior
  (testing "extra key in :auth → fails"
    (try
      (schema/validate (assoc-in (valid-config) [:auth :unexpected-field] "value"))
      (is false)
      (catch clojure.lang.ExceptionInfo e
        (is (= :validation/error (:type (ex-data e)))))))

  (testing "extra key in operation → fails"
    (let [cfg (assoc (valid-config) :operations [{:name :test :path ["/x"] :method :get :extra "no"}])]
      (try
        (schema/validate cfg)
        (is false)
        (catch clojure.lang.ExceptionInfo e
          (is (= :validation/error (:type (ex-data e)))))))))

(deftest policies-and-operations
  (testing ":policies with invalid types → fails"
    (try
      (schema/validate (assoc (valid-config) :policies {:timeout-ms "fast"}))
      (is false)
      (catch clojure.lang.ExceptionInfo e
        (is (= :validation/error (:type (ex-data e)))))))

  (testing ":retries negative → fails"
    (try
      (schema/validate (assoc (valid-config) :policies {:retries -1}))
      (is false)
      (catch clojure.lang.ExceptionInfo e
        (is (= :validation/error (:type (ex-data e)))))))

  (testing ":base-headers with keyword → passes (resolved at runtime)"
    (let [cfg (assoc (valid-config)
                     :operations [{:name :test
                                   :path ["/x"]
                                   :method :get
                                   :base-headers {"X-Provider" :provider-id}}])]
      (is (= cfg (schema/validate cfg)))))

  (testing ":operations empty → passes"
    (let [cfg (assoc (valid-config) :operations [])]
      (is (= cfg (schema/validate cfg))))))
