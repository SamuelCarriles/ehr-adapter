(ns ehr-adapter.middleware.clj-http
  (:require
   [clojure.set :as set]
   [ehr-adapter.http.header :as h]
   [ehr-adapter.error :as error]))

(def motor-keys->clj-http-keys
  {:timeout-ms :connection-timeout})

(def clj-http-keys->motor-keys (set/map-invert motor-keys->clj-http-keys))

(defn ->clj-http-req
  [request]
  (let [{:keys [content-type accept timeout-ms]} request]
    (-> (set/rename-keys request motor-keys->clj-http-keys)
        (assoc :throw-exceptions? false)
        (cond-> timeout-ms (assoc :socket-timeout timeout-ms))
        (dissoc :content-type :accept)
        (cond->
         (and content-type
              (not= :form-url-encoded content-type)
              (not= :form-url-encoded (:code content-type)))
          (update :headers merge (h/content-type content-type))
          accept (update :headers merge (h/accept accept))))))

(defn <-clj-http-response
  [response]
  (let [content-type (get-in response [:headers "Content-Type"])]
    (cond-> (set/rename-keys response clj-http-keys->motor-keys)
      content-type (assoc :content-type (h/parse-mime content-type)))))

(defn wrap
  "Middleware that adapts internal engine requests to clj-http format and vice versa.

   It translates the incoming request, executes the wrapped handler, unifies any network
   exceptions under the `:http/failure` error code, and transforms the response back
   to the engine's expected format.
   Args:
   - handler : A function that executes a clj-http-compatible HTTP request.
   Returns:
   A new function that accepts an internal engine request map and returns an internal
   engine response map."
  [handler]
  (fn [req]
    (let [clj-http-req (->clj-http-req req)
          response (try
                     (handler clj-http-req)
                     (catch Exception e
                       (throw (error/info :http/failure
                                          {:message (.getMessage e)
                                           :scope :ehr-adapter.middleware.clj-http
                                           :operation :http-request
                                           :exception e}))))]
      (<-clj-http-response response))))


