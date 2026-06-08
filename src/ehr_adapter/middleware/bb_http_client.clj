(ns ehr-adapter.middleware.bb-http-client
  (:require
   [clojure.set :as set]
   [ehr-adapter.http.header :as h]
   [ehr-adapter.http.core :refer [success?]]
   [ehr-adapter.error :as error]))

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

(defn wrap-request-handler

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
                                           :exception e}))))
          {:keys [status body]} response
          expected-status (:expected-status req)]
      (if-not (success? status expected-status)
        (throw (error/info :http/failure
                           {:message "EHR HTTP communication failed"
                            :scope :ehr-adapter.middleware.bb-http-client
                            :operation :http-request
                            :status status
                            :error-body body
                            :expected-status (cond-> [:standard-2xx]
                                               expected-status
                                               (into expected-status))}))
        (<-bb-response response)))))

