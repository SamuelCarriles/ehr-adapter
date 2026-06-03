(ns ehr-adapter.middleware.bb-http-client
  (:require [ehr-adapter.middleware.header :as h]))

(defn ->bb-req
  [m]
  (let [{:keys [url timeout-ms throw-exceptions content-type accept]} m]
    (-> (dissoc m :url :timeout-ms :throw-exceptions)
        (assoc :uri url :timeout timeout-ms :throw throw-exceptions)
        (update :headers #(merge % (h/content-type content-type) (h/accept accept))))))

(defn wrap-bb-http-client
  [client-fn]
  (fn [req]
    (let [r (->bb-req req)]
      (client-fn r))))

