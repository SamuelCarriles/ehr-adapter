(ns ehr-adapter.schema
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.util :as mu]
            [clojure.string :as str]
            [buddy.sign.jws :as jws]
            [ehr-adapter.error :as error])
  (:import [org.apache.commons.validator.routines UrlValidator]))

(defn absolute-url?
  [^String s]
  (and (string? s)
       (.isValid (UrlValidator/getInstance) s)))

(defn no-trailing-slash-url?
  "Returns true if s is a valid absolute URL and does not end with a slash"
  [^String s]
  (and (absolute-url? s)
       (not (str/ends-with? s "/"))))

(defn not-blank-str?
  [^String s]
  (and (string? s)
       (not (str/blank? s))))

(defn path-segment?
  [segment]
  (or (keyword? segment)
      (and
       (not-blank-str? segment)
       (not (or (str/ends-with? segment "/")
                (str/starts-with? segment "/"))))))

;; =======================================
;; Http Request

(def MimeCodeMap
  [:and
   [:fn {:error/message "If code is :unsupported, you must provide a :raw-mime field"}
    (fn [{:keys [code raw-mime]}]
      (if (= :unsupported code) (not (nil? raw-mime)) true))]
   [:map
    [:code :keyword]
    [:raw-mime {:optional true} [:fn {:error/message "raw-mime must be a non-blank string"} not-blank-str?]]
    [:properties {:optional true} [:map-of :string :string]]]])

(def HttpRequest
  [:map {:closed true}
   [:method [:enum :get :post :patch :delete :head :put :options :trace :connect]]
   [:url [:fn {:error/message "the url must be a valid URL without a trailing slash"} no-trailing-slash-url?]]
   [:headers {:optional true} [:map-of :any :any]]
   [:body {:optional true} :any]
   [:form-params {:optional true} [:map-of :any :any]]
   [:query-params {:optional true} [:map-of :any :any]]
   [:timeout-ms {:optional true} :int]
   [:content-type {:optional true} [:or :keyword MimeCodeMap]]
   [:accept {:optional true} [:or :keyword MimeCodeMap]]
   [:async {:optional true} :boolean]
   [:throw-exceptions {:optional true} :boolean]])

(def HttpRequestOption
  (-> HttpRequest
      (mu/optional-keys [:method :url])
      (mu/merge
       [:fn {:error/message "if :method exists, :url must exist, and vice versa"}
        (fn [{:keys [method url]}]
          (= (nil? method) (nil? url)))])))

;; =================================================================
;; JWK 
(def PublicJWK
  [:map
   [:kty [:fn {:error/message "The field \"kty\" in the JWK must be a non-blank string"} not-blank-str?]]
   [:alg [:fn {:error/message "The field \"alg\" in the JWK must be a non-blank string"} not-blank-str?]]
   [:n [:fn {:error/message "The field \"n\" in the JWK must be a non-blank string"} not-blank-str?]]
   [:e [:fn {:error/message "The field \"e\" in the JWK must be a non-blank string"} not-blank-str?]]])

(def PrivateJWK
  (mu/merge
   PublicJWK
   [:map
    [:d [:fn {:error/message "The field \"d\" in the JWK must be a non-blank string"} not-blank-str?]]
    [:p {:optional true} [:fn {:error/message "The field \"p\" in the JWK must be a non-blank string"} not-blank-str?]]
    [:q {:optional true} [:fn {:error/message "The field \"q\" in the JWK must be a non-blank string"} not-blank-str?]]
    [:dp {:optional true} [:fn {:error/message "The field \"dp\" in the JWK must be a non-blank string"} not-blank-str?]]
    [:dq {:optional true} [:fn {:error/message "The field \"dq\" in the JWK must be a non-blank string"} not-blank-str?]]
    [:qi {:optional true} [:fn {:error/message "The field \"qi\" in the JWK must be a non-blank string"} not-blank-str?]]]))

;; ===========================================================
;; Auth Strategies or Layers (sub-schemas for Authentication)

(defn supported-alg?
  [alg]
  (and (keyword? alg)
       (contains? jws/+signers-map+ alg)))

(def BasicAuth
  [:map
   [:username [:fn {:error/message "username must be a non-blank string"} not-blank-str?]]
   [:password [:fn {:error/message "password must be a non-blank string"} not-blank-str?]]])

(def SmartOnFHIR
  [:map
   [:client-id [:fn {:error/message "client-id must be a non-blank string"} not-blank-str?]]
   [:private-key [:or
                  [:fn {:error/message "private-key must be a non-blank string"} not-blank-str?]
                  PrivateJWK]]
   [:key-id [:fn {:error/message "key-id must be a non-blank string"} not-blank-str?]]
   [:algorithm [:fn {:error/message "algorithm must be a keyword and a supported algorithm by buddy-sign library"} supported-alg?]]
   [:audience [:fn {:error/message "audience must be a valid URL without a trailing slash"} no-trailing-slash-url?]]
   [:scopes [:vector [:fn {:error/message "each scope must be a non-blank string"} not-blank-str?]]]
   [:token-url [:fn {:error/message "token-url must be a valid URL without a trailing slash"} no-trailing-slash-url?]]])

(def OAuth2
  [:map
   [:token-url [:fn {:error/message "token-url must be a valid URL without a trailing slash"} no-trailing-slash-url?]]
   [:grant-type [:fn {:error/message "grant-type must be a non-blank string"} not-blank-str?]]
   [:client-id [:fn {:error/message "client-id must be a non-blank string"} not-blank-str?]]
   [:client-secret [:fn {:error/message "client-secret must be a non-blank string"} not-blank-str?]]
   [:scopes {:optional true} [:vector [:fn {:error/message "each scope must be a non-blank string"} not-blank-str?]]]])

(def CustomAuth
  [:map
   [:handler [:fn {:error/message "custom-auth-handler should be a Clojure function"} fn?]]])

(def ExtractionPath
  [:vector [:or :string :keyword :int]])

(def NormalizeMap
  [:map
   [:token {:optional true} [:or :keyword :string ExtractionPath]]
   [:token-type {:optional true} [:or :keyword :string ExtractionPath]]
   [:expires-in {:optional true} [:or :keyword :string ExtractionPath]]
   [:refresh-token {:optional true} [:or :keyword :string ExtractionPath]]])

;; ===========================================================

(def Authentication
  [:and
   [:map
    [:type [:enum :basic-auth :smart-on-fhir/backend-services :oauth2 :normalize :custom]]
    [:bindings {:optional true} [:map-of :keyword [:vector [:or :keyword :string]]]]
    [:options {:optional true}
     [:map
      [:request {:optional true} HttpRequestOption]]]]

   [:multi {:dispatch :type}
    [:basic-auth BasicAuth]
    [:smart-on-fhir/backend-services SmartOnFHIR]
    [:oauth2 OAuth2]
    [:normalize NormalizeMap]
    [:custom CustomAuth]]])

(def NetworkConfiguration
  [:and
   [:fn {:error/message "If you configure :retries, you must provide :retry-delay-ms"}
    (fn [{:keys [retries retry-delay-ms]}]
      (= (nil? retries) (nil? retry-delay-ms)))]

   [:map
    [:timeout-ms {:optional true} [:int {:min 1}]]
    [:retries {:optional true} [:int {:min 1}]]
    [:retry-delay-ms {:optional true} [:int {:min 1}]]
    [:retry-on {:optional true} [:vector {:error/message "retry-on must be a vector of valid HTTP status codes (100-599)"}
                                 [:int {:min 100 :max 599}]]]
    [:retry-strategy {:optional true} [:enum {:error/message "retry-strategy must be either :linear or :exponential"}
                                       :linear :exponential]]
    [:refresh-token-on {:optional true}
     [:vector {:error/message "refresh-token-on must be a vector of valid HTTP status codes (100-599)"}
      [:int {:min 100 :max 599}]]]]])

(def Operation
  [:map
   [:name :keyword]
   [:path
    [:vector
     [:fn {:error/message "each segment in operation-path must be a keyword or a non-blank string, and can not start or end with \"/\""} path-segment?]]]
   [:method [:enum :get :post :patch :delete :head :put :options :trace :connect]]
   [:expected-status {:optional true} [:vector [:int {:min 100 :max 599}]]]
   [:base-headers {:optional true} [:map-of :string [:or :keyword :string :int]]]
   [:description {:optional true} [:fn {:error/message "operation-description must be a non-blank string"} not-blank-str?]]])

(def AdapterConfiguration
  [:map {:closed true}
   [:domain :qualified-keyword]
   [:base-url [:fn {:error/message "base-url must be a valid URL without a trailing slash"} no-trailing-slash-url?]]
   [:http-client-fn [:fn {:error/message "http-client-fn must be a Clojure function"} fn?]]
   [:middlewares [:vector {:min 1} [:fn {:error/message "each middleware must be a Clojure function"} fn?]]]
   [:auth
    [:vector Authentication]]
   [:network-config {:optional true} NetworkConfiguration]
   [:operations {:optional true}
    [:vector Operation]]])

;; =================================================================
;; AdapterInstance

(def RunTimeAuth
  [:map
   [:state [:fn {:error/message "auth state must be a Clojure Atom (IAtom)"}
            (fn [s] (instance? clojure.lang.IAtom s))]]

   [:get-token [:fn {:error/message "get-token must be a compiled Clojure function"} fn?]]

   [:config [:vector Authentication]]])

(def AdapterInstance
  [:map {:closed true}
   [:domain :qualified-keyword]
   [:base-url [:fn {:error/message "base-url must be a valid URL without a trailing slash"} no-trailing-slash-url?]]
   [:auth RunTimeAuth]
   [:operations {:optional true} [:map-of :keyword [:fn {:error/message "each operation must be a compiled Clojure function"} fn?]]]])

;; =================================================================
;; Validators

(defn validate-schema
  [schema form error-message]
  (if-let [explain (m/explain schema form)]
    (throw (error/info :invalid/schema {:message error-message
                                        :scope :ehr-adapter.schema
                                        :operation :validate
                                        :details (me/humanize explain)}))
    form))

(defn validate-adapter-config
  [config]
  (validate-schema AdapterConfiguration config "Invalid Adapter configuration"))

(defn validate-adapter-instance
  [instance]
  (validate-schema AdapterInstance instance "Invalid Adapter instance"))

(defn validate-http-request
  [req-map]
  (validate-schema HttpRequest req-map "Invalid HTTP Request map"))

(defn validate-mime-code-map
  [mime-code-map]
  (validate-schema MimeCodeMap mime-code-map "Invalid Mime code map"))

(defn validate-public-jwk
  [jwk-map]
  (validate-schema PublicJWK jwk-map "Invalid public JWK"))

(defn validate-private-jwk
  [jwk-map]
  (validate-schema PrivateJWK jwk-map "Invalid private JWK"))
