(ns ehr-adapter.schema
  (:require [malli.core :as m]
            [malli.error :as me]
            [clojure.string :as str])
  (:import [org.apache.commons.validator.routines UrlValidator]))

(defn valid-url?
  [^String s]
  (and (string? s)
       (.isValid (UrlValidator/getInstance) s)))

(defn not-blank-str?
  [^String s]
  (and (string? s)
       (not (str/blank? s))))

(def basic-auth
  [:map
   [:token-url [:fn {:error/message "token-url must be a valid URL"} valid-url?]]
   [:username [:fn {:error/message "username must be a non-blank string"} not-blank-str?]]
   [:password [:fn {:error/message "password must be a non-blank string"} not-blank-str?]]
   [:payload {:optional true} [:map-of :keyword :any]]])

(def smart-auth
  [:and
   [:fn {:error/message "exactly one of :private-key or :private-key-path must be provided"} (fn [{:keys [private-key private-key-path]}] (= (nil? private-key) (not (nil? private-key-path))))]
   [:map
    [:client-id [:fn {:error/message "client-id must be a non-blank string"} not-blank-str?]]
    [:private-key-path {:optional true} [:fn {:error/message "private-key-path must be a non-blank string"} not-blank-str?]]
    [:private-key {:optional true} [:fn {:error/message "private-key must be a non-blank string"} not-blank-str?]]
    [:key-id [:fn {:error/message "key-id must be a non-blank string"} not-blank-str?]]
    [:algorithm [:fn {:error/message "algorithm must be a non-blank string"} not-blank-str?]]
    [:audience {:optional true} [:fn {:error/message "audience must be a valid URL"} valid-url?]]
    [:scopes [:vector [:fn {:error/message "each scope must be a non-blank string"} not-blank-str?]]]
    [:token-url [:fn {:error/message "token-url must be a valid URL"} valid-url?]]]])

(def oauth2-auth
  [:map
   [:token-url [:fn {:error/message "token-url must be a valid URL"} valid-url?]]
   [:grant-type [:fn {:error/message "grant-type must be a non-blank string"} not-blank-str?]]
   [:client-id [:fn {:error/message "client-id must be a non-blank string"} not-blank-str?]]
   [:client-secret [:fn {:error/message "client-secret must be a non-blank string"} not-blank-str?]]
   [:scope {:optional true} [:fn {:error/message "scope must be a non-blank string"} not-blank-str?]]
   [:payload {:optional true} [:map-of :keyword :any]]])

(def api-key-auth
  [:map
   [:api-key [:fn {:error/message "api-key must be a non-blank string"} not-blank-str?]]
   [:client-id {:optional true} [:fn {:error/message "client-id must be a non-blank string"} not-blank-str?]]])

(def custom-auth
  [:map
   [:handler [:fn {:error/message "custom-auth-handler should be a Clojure function"} fn?]]
   [:data [:map-of :any :any]]])

(def auth-schema
  [:and
   [:map
    [:type [:enum :basic-auth :smart-on-fhir/backend-services :oauth2 :api-key :custom]]
    [:bindings {:optional true} [:map-of :keyword [:vector [:or :keyword :string]]]]]
   [:multi {:dispatch :type}
    [:basic-auth #'basic-auth]
    [:smart-on-fhir/backend-services #'smart-auth]
    [:oauth2 #'oauth2-auth]
    [:api-key #'api-key-auth]
    [:custom #'custom-auth]]])

(def network-config-schema
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

(def operation-schema
  [:map
   [:name :keyword]
   [:path
    [:vector
     [:or :keyword [:fn {:error/message "each segment in operation-path must be a non-blank string"} not-blank-str?]]]]
   [:method [:enum :get :post :patch :delete :head :put :options :trace :connect]]
   [:expected-status {:optional true} [:vector [:int {:min 100 :max 599}]]]
   [:base-headers {:optional true} [:map-of :string [:or :keyword :string :int]]]
   [:description {:optional true} [:fn {:error/message "operation-description must be a non-blank string"} not-blank-str?]]])

(def adapter-schema
  [:map {:closed true}
   [:domain :qualified-keyword]
   [:base-url [:fn {:error/message "base-url must be a valid URL"} valid-url?]]
   [:http-client-fn [:fn {:error/message "http-client-fn must be a Clojure function"} fn?]]
   [:middlewares [:vector {:min 1} [:fn {:error/message "each middleware must be a Clojure function"} fn?]]]
   [:auth
    [:vector #'auth-schema]]
   [:network-config {:optional true} #'network-config-schema]
   [:operations {:optional true}
    [:vector #'operation-schema]]])

(defn valid-adapter-config?
  [m]
  (if-let [explain (m/explain adapter-schema m)]
    (throw (ex-info "Invalid Adapter configuration"
                    {:type :invalid/schema
                     :details (me/humanize explain)}))
    true))

