(ns ehr-adapter.auth.sign-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.auth.sign :as sign]
            [buddy.sign.jwt :as jwt]
            [buddy.core.keys :as bk])
  (:import (java.security KeyPairGenerator)
           (java.util Base64)))

(defn generate-test-keypair
  "Generates a real RSA KeyPair in memory for test isolation."
  []
  (let [kpg (KeyPairGenerator/getInstance "RSA")]
    (.initialize kpg 2048)
    (.generateKeyPair kpg)))

(defn key->pem-string
  "Converts a Java Key object into a Base64 encoded PEM format string."
  [key type]
  (let [encoder (Base64/getMimeEncoder 64 (byte-array [10]))
        encoded (.encodeToString encoder (.getEncoded key))]
    (str "-----BEGIN " type " KEY-----\n"
         encoded
         "\n-----END " type " KEY-----")))

;; =============================================================================
;; Specifications & Integration Tests
;; =============================================================================

(deftest coerce-private-key-real-crypto-test
  (let [keypair (generate-test-keypair)
        java-private-key (.getPrivate keypair)
        java-public-key (.getPublic keypair)]

    (testing "Coercion using a real RSA PEM String"
      (let [pem-string (key->pem-string java-private-key "PRIVATE")
            coerced-key (@#'sign/coerce-private-key pem-string)]
        (is (instance? java.security.PrivateKey coerced-key))
        (let [token (jwt/sign {:foo "bar"} coerced-key {:alg :rs256})]
          (is (= {:foo "bar"} (jwt/unsign token java-public-key {:alg :rs256}))))))

    (testing "Coercion using a real Clojure JWK Map"
      (let [jwk-map (bk/jwk java-private-key java-public-key)
            coerced-key (@#'sign/coerce-private-key jwk-map)]
        (is (map? jwk-map))
        (is (= "RSA" (:kty jwk-map)))
        (is (contains? jwk-map :d))
        (is (instance? java.security.PrivateKey coerced-key))
        (let [token (jwt/sign {:baz "qux"} coerced-key {:alg :rs256})]
          (is (= {:baz "qux"} (jwt/unsign token java-public-key {:alg :rs256}))))))

    (testing "Defensive exceptions for invalid formats"
      (try
        (@#'sign/coerce-private-key :invalid-type-keyword)
        (is false "Should have thrown an exception for an invalid type")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :invalid/type (:code data)))
            (is (= :resolve-private-key (:operation data)))
            (is (= clojure.lang.Keyword (get-in data [:details :type])))
            (is (= [:map :string] (get-in data [:details :expected]))))))

      (try
        (@#'sign/coerce-private-key "-----BEGIN PRIVATE KEY-----\nCORRUPT_DATA\n-----END PRIVATE KEY-----")
        (is false "Should have thrown an exception for corrupt PEM bytes")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :invalid/private-key (:code data)))
            (is (= :resolve-private-key (:operation data)))
            (is (= "-----BEGIN PRIVATE KEY-----\nCORRUPT_DATA\n-----END PRIVATE KEY-----"
                   (get-in data [:details :private-key])))))))))

(deftest client-assertion-integration-test
  (let [keypair (generate-test-keypair)
        private-key (.getPrivate keypair)
        public-key (.getPublic keypair)
        pem-string (key->pem-string private-key "PRIVATE")

        strategy {:type        :smart-on-fhir/backend-services
                  :client-id   "ehr-connector-app"
                  :audience    "https://ehr.hospital.org/oauth2/token"
                  :algorithm   :rs256
                  :key-id      "key-production-v1"
                  :private-key pem-string}]

    (testing "Full JWT generation and cryptographic verification without mocks"
      (let [jwt-token (sign/client-assertion strategy)]
        (is (string? jwt-token))
        (let [decoded-claims (jwt/unsign jwt-token public-key {:alg :rs256})]
          (is (= "ehr-connector-app" (:iss decoded-claims)))
          (is (= "ehr-connector-app" (:sub decoded-claims)))
          (is (= "https://ehr.hospital.org/oauth2/token" (:aud decoded-claims)))
          (is (integer? (:exp decoded-claims)))
          (is (string? (:jti decoded-claims))))))

    (testing "Multimethod fallback behavior for unsupported assertion types"
      (let [unsupported-strategy {:type :unsupported/legacy-auth}]
        (try
          (sign/client-assertion unsupported-strategy)
          (is false "Should have thrown an exception for an unsupported type")
          (catch clojure.lang.ExceptionInfo e
            (let [data (ex-data e)]
              (is (= :unsupported/assertion-type (:code data)))
              (is (= :client-assertion (:operation data)))
              (is (= :unsupported/legacy-auth (get-in data [:details :assertion-type])))
              (is (= [:smart-on-fhir/backend-services] (get-in data [:details :expected]))))))))))

(deftest get-jwk-test
  (let [jwks {:keys [{:kty "RSA" :kid "key-1" :n "abc" :e "AQAB" :d "xyz"}
                     {:kty "RSA" :kid "key-2" :n "def" :e "AQAB" :d "uvw"}
                     {:kty "RSA" :kid "key-3" :n "ghi" :e "AQAB" :d "rst"}]}]

    (testing "Extracts the correct JWK by kid from a JWKS"
      (let [jwk (@#'sign/get-jwk "key-2" jwks)]
        (is (= "key-2" (:kid jwk)))
        (is (= "def" (:n jwk)))))

    (testing "Extracts the first key when kid matches"
      (let [jwk (@#'sign/get-jwk "key-1" jwks)]
        (is (= "key-1" (:kid jwk)))
        (is (= "abc" (:n jwk)))))

    (testing "Extracts the last key when kid matches"
      (let [jwk (@#'sign/get-jwk "key-3" jwks)]
        (is (= "key-3" (:kid jwk)))
        (is (= "ghi" (:n jwk)))))

    (testing "Throws :invalid/key-id when kid is not found in JWKS"
      (try
        (@#'sign/get-jwk "non-existent-key" jwks)
        (is false "Should have thrown an exception")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :invalid/key-id (:code data)))
            (is (= :ehr-adapter.auth.sign (:scope data)))
            (is (= :resolve-private-key (:operation data)))
            (is (= "non-existent-key" (get-in data [:details :key-id])))
            (is (= ["key-1" "key-2" "key-3"] (get-in data [:details :expected])))))))))

(deftest client-assertion-with-jwks-test
  (let [keypair (generate-test-keypair)
        private-key (.getPrivate keypair)
        public-key (.getPublic keypair)
        jwk (bk/jwk private-key public-key)
        jwks {:keys [(assoc jwk :kid "key-jwks-1")
                     (assoc jwk :kid "key-jwks-2")]}

        strategy {:type :smart-on-fhir/backend-services
                  :client-id "ehr-connector-app"
                  :audience "https://ehr.hospital.org/oauth2/token"
                  :algorithm :rs256
                  :key-id "key-jwks-2"
                  :private-key-set jwks}]

    (testing "Full JWT generation using :private-key-set (JWKS) instead of :private-key"
      (let [jwt-token (sign/client-assertion strategy)]
        (is (string? jwt-token))
        (let [decoded-claims (jwt/unsign jwt-token public-key {:alg :rs256})]
          (is (= "ehr-connector-app" (:iss decoded-claims)))
          (is (= "ehr-connector-app" (:sub decoded-claims)))
          (is (= "https://ehr.hospital.org/oauth2/token" (:aud decoded-claims)))
          (is (integer? (:exp decoded-claims)))
          (is (string? (:jti decoded-claims))))))))
