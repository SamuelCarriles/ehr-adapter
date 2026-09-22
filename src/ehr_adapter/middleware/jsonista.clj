(ns ehr-adapter.middleware.jsonista
  (:require [ehr-adapter.http.header :refer [json-media-type?]]))

(def default-mapper
  @(requiring-resolve 'jsonista.core/keyword-keys-object-mapper))

(defn wrap
  ([handler]
   (wrap handler {:mapper default-mapper}))

  ([handler options]
   (let [json-media-opts (select-keys options [:includes])
         object-mapper (or (:mapper options) default-mapper)
         write-string (requiring-resolve 'jsonista.core/write-value-as-string)
         read-value (requiring-resolve 'jsonista.core/read-value)]
     (fn [request]
       (let [req (if (json-media-type? (:content-type request) json-media-opts)
                   (update request :body write-string  object-mapper)
                   request)
             response (handler req)]
         (if (json-media-type? (:content-type response) json-media-opts)
           (update response :body read-value object-mapper)
           response))))))
