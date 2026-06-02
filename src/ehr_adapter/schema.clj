(ns ehr-adapter.schema
  (:require [malli.core :as m]
            [malli.error :as me]
            [clojure.string :as str]
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
  [^String s]
  (or (keyword? s)
      (and
       (not-blank-str? s)
       (not (or (str/ends-with? s "/")
                (str/starts-with? s "/"))))))

;; ===========================================================
;; Auth Strategies or Layers (sub-schemas for Authentication)

(def BasicAuth
  [:map
   [:username [:fn {:error/message "username must be a non-blank string"} not-blank-str?]]
   [:password [:fn {:error/message "password must be a non-blank string"} not-blank-str?]]
   [:token-url {:optional true} [:fn {:error/message "token-url must be a valid URL without a trailing slash"} no-trailing-slash-url?]]])

(def SmartOnFHIR
  [:and
   [:fn {:error/message "exactly one of :private-key or :private-key-path must be provided"} (fn [{:keys [private-key private-key-path]}] (= (nil? private-key) (not (nil? private-key-path))))]
   [:map
    [:client-id [:fn {:error/message "client-id must be a non-blank string"} not-blank-str?]]
    [:private-key-path {:optional true} [:fn {:error/message "private-key-path must be a non-blank string"} not-blank-str?]]
    [:private-key {:optional true} [:fn {:error/message "private-key must be a non-blank string"} not-blank-str?]]
    [:key-id [:fn {:error/message "key-id must be a non-blank string"} not-blank-str?]]
    [:algorithm [:fn {:error/message "algorithm must be a non-blank string"} not-blank-str?]]
    [:audience [:fn {:error/message "audience must be a valid URL without a trailing slash"} no-trailing-slash-url?]]
    [:scopes [:vector [:fn {:error/message "each scope must be a non-blank string"} not-blank-str?]]]
    [:token-url [:fn {:error/message "token-url must be a valid URL without a trailing slash"} no-trailing-slash-url?]]]])

(def OAuth2
  [:map
   [:token-url [:fn {:error/message "token-url must be a valid URL without a trailing slash"} no-trailing-slash-url?]]
   [:grant-type [:fn {:error/message "grant-type must be a non-blank string"} not-blank-str?]]
   [:client-id [:fn {:error/message "client-id must be a non-blank string"} not-blank-str?]]
   [:client-secret [:fn {:error/message "client-secret must be a non-blank string"} not-blank-str?]]
   [:scopes {:optional true} [:vector [:fn {:error/message "each scope must be a non-blank string"} not-blank-str?]]]])

(def ApiKey
  [:map
   [:api-key [:fn {:error/message "api-key must be a non-blank string"} not-blank-str?]]
   [:client-id {:optional true} [:fn {:error/message "client-id must be a non-blank string"} not-blank-str?]]])

(def CustomAuth
  [:map
   [:handler [:fn {:error/message "custom-auth-handler should be a Clojure function"} fn?]]
   [:data {:optional true} [:map-of :any :any]]])

(def ExtractionPath
  [:vector [:or :string :keyword :int]])

(def Payload
  [:map
   [:body {:optional true} [:map-of :any :any]]
   [:headers {:optional true} [:map-of :any :any]]
   [:query {:optional true} [:map-of :any :any]]])

(def NormalizeMap
  [:map
   [:token {:optional true} [:or :keyword :string #'ExtractionPath]]
   [:token-type {:optional true} [:or :keyword :string #'ExtractionPath]]
   [:expires-in {:optional true} [:or :keyword :string #'ExtractionPath]]
   [:refresh-token {:optional true} [:or :keyword :string #'ExtractionPath]]])

;; ===========================================================

(def Authentication
  [:and
   [:map
    [:type [:enum :basic-auth :smart-on-fhir/backend-services :oauth2 :api-key :normalize :custom]]
    [:bindings {:optional true} [:map-of :keyword [:vector [:or :keyword :string]]]]
    [:payload {:optional true} #'Payload]]
   [:multi {:dispatch :type}
    [:basic-auth #'BasicAuth]
    [:smart-on-fhir/backend-services #'SmartOnFHIR]
    [:oauth2 #'OAuth2]
    [:api-key #'ApiKey]
    [:normalize #'NormalizeMap]
    [:custom #'CustomAuth]]])

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
    [:vector #'Authentication]]
   [:network-config {:optional true} #'NetworkConfiguration]
   [:operations {:optional true}
    [:vector #'Operation]]])

;; =================================================================
;; AdapterInstance

(def RunTimeAuth
  [:map
   [:state [:fn {:error/message "auth state must be a Clojure Atom (IAtom)"}
            (fn [s] (instance? clojure.lang.IAtom s))]]

   [:get-token [:fn {:error/message "get-token must be a compiled Clojure function"} fn?]]

   [:config [:vector #'Authentication]]])

(def AdapterInstance
  [:map {:closed true}
   [:domain :qualified-keyword]
   [:base-url [:fn {:error/message "base-url must be a valid URL without a trailing slash"} no-trailing-slash-url?]]
   [:auth #'RunTimeAuth]
   [:operations {:optional true} [:map-of :keyword [:fn {:error/message "each operation must be a compiled Clojure function"} fn?]]]])

(defn validate-adapter-config
  [config]
  (if-let [explain (m/explain AdapterConfiguration config)]
    (throw (error/info :invalid/schema {:message "Invalid Adapter configuration"
                                        :scope :ehr-adapter.schema
                                        :operation :validate
                                        :details (me/humanize explain)}))
    config))

(defn validate-adapter-instance
  [instance]
  (if-let [explain (m/explain AdapterInstance instance)]
    (throw (error/info :invalid/schema {:message "Invalid Adapter instance"
                                        :scope :ehr-adapter.schema
                                        :operation :validate
                                        :details (me/humanize explain)}))
    instance))

;; =======================================
;; Http Request

(def HttpRequest
  [:map {:closed true}
   [:method [:enum :get :post :patch :delete :head :put :options :trace :connect]]
   [:url [:fn {:error/message "the url must be a valid URL without a trailing slash"} no-trailing-slash-url?]]
   [:headers {:optional true} [:map-of :any :any]]
   [:body {:optional true} :any]
   [:form-params {:optional true} [:map-of :any :any]]
   [:query-params {:optional true} [:map-of :any :any]]
   [:timeout-ms {:optional true} :int]
   [:content-type {:optional true} :keyword]
   [:accept {:optional true} :keyword]
   [:async {:optional true} :boolean]
   [:throw-exceptions {:optional true} :boolean]])

(defn validate-http-request
  [m]
  (if-let [explain (m/explain HttpRequest m)]
    (throw (error/info :invalid/schema {:message "Invalid HTTP Request map"
                                        :scope :ehr-adapter.schema
                                        :operation :validate
                                        :details (me/humanize explain)}))
    m))
