(ns ehr-adapter.middleware.jsonista
  (:require [ehr-adapter.http.header :refer [json-media-type?]]))

(defn wrap
  ([handler] (wrap handler @(requiring-resolve 'jsonista.core/keyword-keys-object-mapper)))
  ([handler object-mapper]
   (let [write-string (requiring-resolve 'jsonista.core/write-value-as-string)
         read-value (requiring-resolve 'jsonista.core/read-value)]
     (fn [request]
       (let [req (if (json-media-type? (:content-type request))
                   (update request :body #(write-string % object-mapper))
                   request)
             response (handler req)]
         (if (json-media-type? (:content-type response))
           (update response :body #(read-value % object-mapper))
           response))))))


