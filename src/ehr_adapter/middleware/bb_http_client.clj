(ns ehr-adapter.middleware.bb-http-client
  (:require
   [clojure.set :as set]
   [ehr-adapter.http.header :as h]
   [ehr-adapter.error :as error]))

(def motor-keys->bb-keys
  {:url :uri
   :timeout-ms :timeout})

(def bb-keys->motor-keys (set/map-invert motor-keys->bb-keys))

(defn ->bb-req
  [request]
  (let [{:keys [content-type accept]} request]
    (-> (set/rename-keys request motor-keys->bb-keys)
        (assoc :throw false)
        (dissoc :content-type :accept)
        (cond->
         content-type (update :headers merge (h/content-type content-type))
         accept (update :headers merge (h/accept accept))))))

(defn <-bb-response
  [response]
  (let [content-type (get-in response [:headers "content-type"])]
    (cond-> (set/rename-keys response bb-keys->motor-keys)

      content-type (assoc :content-type (h/parse-mime content-type)))))

(defn wrap-request-handler
  "Middleware that adapts internal engine requests to Babashka HTTP client format and vice versa.
   
   It translates the incoming request, executes the wrapped handler, unifies any network 
   exceptions under the `:http/failure` error code, and transforms the response back 
   to the engine's expected format.

   Args:
   - handler : A function that executes a Babashka-compatible HTTP request.

   Returns:
   A new function that accepts an internal engine request map and returns an internal 
   engine response map."
  [handler]
  (fn [req]
    (let [bb-req (->bb-req req)
          response (try
                     (handler bb-req)
                     (catch Exception e
                       (throw (error/info :http/failure
                                          {:message (.getMessage e)
                                           :scope :ehr-adapter.middleware.bb-http-client
                                           :operation :http-request
                                           :exception e}))))]
      (<-bb-response response))))

