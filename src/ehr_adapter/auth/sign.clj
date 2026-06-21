(ns ehr-adapter.auth.sign
  (:require
   [buddy.core.keys :as bk]
   [buddy.sign.jwt :as jwt]
   [ehr-adapter.error :as error]
   [ehr-adapter.time :refer [now]]))

(defn- get-jwk
  [kid jwks]
  (let [jwk-coll (:keys jwks)]
    (if-let [jwk (some #(when (= kid (:kid %)) %) jwk-coll)]
      jwk
      (throw (error/info :invalid/key-id
                         {:message (format "The given JWKS doesn't contain a JWK with kid: %s" kid)
                          :scope :ehr-adapter.auth.sign
                          :value kid
                          :operation :resolve-private-key
                          :expected (into [] (keep :kid (:keys jwks)))})))))

(defn- coerce-private-key
  "Coerces a private key configuration into a java.security.PrivateKey instance.

  Accepts either:
  - A Clojure map representing a valid Private JSON Web Key (JWK).
  - A PEM-encoded string containing the private key blocks.

  Returns the initialized java.security.PrivateKey object ready for cryptographic operations."
  [pk]
  (when-not (or (map? pk) (string? pk))
    (throw (error/info :invalid/type
                       {:message "Private Key must be a Clojure map or string"
                        :scope :ehr-adapter.auth.sign
                        :value pk
                        :operation :resolve-private-key
                        :expected [:map :string]})))

  (try
    (if (map? pk)
      (bk/jwk->private-key pk)
      (bk/str->private-key pk))
    (catch Exception e
      (throw (error/info :invalid/private-key
                         {:message (.getMessage e)
                          :scope :ehr-adapter.auth.sign
                          :operation :resolve-private-key
                          :private-key pk})))))

(defmulti client-assertion (fn [auth-strategy] (:type auth-strategy)))

(defmethod client-assertion :default
  [auth-strategy]
  (throw (error/info :unsupported/assertion-type
                     {:message "Unsupported assertion type is provided"
                      :scope :ehr-adapter.auth.sign
                      :operation :client-assertion
                      :assertion-type (:type auth-strategy)
                      :expected [:smart-on-fhir/backend-services]})))

(defmethod client-assertion  :smart-on-fhir/backend-services
  [{:keys [client-id audience algorithm key-id private-key private-key-set]}]
  (let [pk (or private-key (get-jwk key-id private-key-set))
        private-key-obj (coerce-private-key pk)
        claims {:iss client-id
                :sub client-id
                :aud audience
                :exp (+ (now) 300)
                :jti (str (random-uuid))}
        opts {:alg algorithm
              :header {:kid key-id
                       :typ "JWT"}}]
    (jwt/sign claims private-key-obj opts)))
