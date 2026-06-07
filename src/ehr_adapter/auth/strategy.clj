(ns ehr-adapter.auth.strategy
  (:require [clojure.string :as str]
            [ehr-adapter.http.header :as header]
            [ehr-adapter.auth.sign :as sign])
  (:import [java.util Base64]
           [java.nio.charset StandardCharsets]))

(defn ->base64
  [^String s]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes s StandardCharsets/UTF_8)))

(defn format-scopes
  [scopes]
  (when (seq scopes)
    (str/join " " scopes)))

(defmulti execute
  "Executes the specific authentication strategy based on the layer's :type.

  Takes a fully resolved authentication layer map and an http-client function.
  Returns the direct, raw result of the authentication operation (such as immediate 
  HTTP headers or raw network response payloads).

  It does not normalize or transform response formats; varying structures and 
  dynamic data extraction must be handled upstream using context bindings."
  (fn [layer _http-client] (:type layer)))

;; BasicAuth Strategy
(defn basic-auth
  "Executes the Basic Authentication strategy.
  Returns a map with the Base64 token if no active request is specified in options, 
  otherwise injects the Authorization header into options and executes the request."
  [{:keys [username password options]} http-client]
  (let [req-opts (:request options)
        token (->base64 (format "%s:%s" username password))
        auth-data {:token token
                   :token-type "Basic"}
        auth-header (header/authorization token "Basic")]
    (if-not req-opts
      auth-data
      (-> req-opts
          (update :headers merge auth-header)
          http-client))))

(defmethod execute :basic-auth
  [layer http-client]
  (basic-auth layer http-client))

;; CustomAuth Strategy
(defn custom-auth
  "Executes a user-defined custom authentication strategy.
  Invokes the provided handler function passing the options map and the http-client."
  [{:keys [handler options]} http-client]
  (handler options http-client))

(defmethod execute :custom
  [layer http-client]
  (custom-auth layer http-client))

;; OAuth2 Strategy
(defn oauth2
  "Executes the OAuth2 client credentials strategy.
  Builds a POST request using strictly URL-encoded form parameters as per RFC 6749,
  merging optional headers or extra form parameters from options."
  [{:keys [token-url grant-type client-id client-secret scopes options]} http-client]
  (let [req-opts (:request options)
        new-form-params (:form-params req-opts)
        scope (format-scopes scopes)
        base-req {:method :post
                  :url token-url
                  :form-params (cond-> {"grant_type" grant-type
                                        "client_id" client-id
                                        "client_secret" client-secret}

                                 scope (assoc "scope" scope)

                                 new-form-params
                                 (merge new-form-params))
                  :content-type :form-url-encoded}

        request (merge req-opts base-req)]
    (http-client request)))

(defmethod execute :oauth2
  [layer http-client]
  (oauth2 layer http-client))

;; Smart On FHIR Strategy
(defn smart-on-fhir-backend-services
  "Executes the SMART on FHIR Backend Services authorization flow (asymmetric OAuth2 / RFC 7523)."
  [{:keys [scopes token-url options] :as layer} http-client]
  (let [req-opts (:request options)
        new-form-params (:form-params req-opts)
        client-assertion (sign/client-assertion layer)
        scope (format-scopes scopes)
        base-req {:method :post
                  :url token-url
                  :form-params (cond-> {"grant_type" "client_credentials"
                                        "client_assertion_type" "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
                                        "client_assertion" client-assertion
                                        "scope" scope}

                                 new-form-params
                                 (merge new-form-params))
                  :content-type :form-url-encoded}
        request (merge req-opts base-req)]
    (http-client request)))

(defmethod execute :smart-on-fhir/backend-services
  [layer http-client]
  (smart-on-fhir-backend-services layer http-client))
