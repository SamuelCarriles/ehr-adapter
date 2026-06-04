(ns ehr-adapter.middleware.bb-http-client
  (:require
   [clojure.set :as set]
   [ehr-adapter.http.header :as h]))

(def motor-keys->bb-keys
  {:url :uri
   :timeout-ms :timeout
   :throw-exceptions :throw})

(def bb-keys->motor-keys (set/map-invert motor-keys->bb-keys))

(defn ->bb-req
  [request]
  (let [{:keys [content-type accept]} request]
    (-> (set/rename-keys request motor-keys->bb-keys)
        (dissoc :content-type :accept)
        (cond->
         content-type (update :headers merge (h/content-type content-type))
         accept (update :headers merge (h/accept accept))))))

(defn <-bb-response
  [response]
  (let [content-type (get-in response [:headers "content-type"])]
    (-> (set/rename-keys response bb-keys->motor-keys)
        (update :headers dissoc "content-type")
        (cond->
         content-type (assoc :content-type (h/parse-mime content-type))))))

(defn wrap-bb-http-client

  [handler]
  (fn [req]
    (-> req
        ->bb-req
        handler
        <-bb-response)))

