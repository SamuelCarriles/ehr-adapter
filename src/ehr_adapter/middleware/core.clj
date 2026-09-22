(ns ehr-adapter.middleware.core)

(defn normalize
  "Normalizes middleware definitions into middleware functions."
  [middlewares]
  (map
   (fn [middleware]
     (if (vector? middleware)
       (let [[f & args] middleware]
         (fn [handler]
           (apply f handler args)))
       middleware))
   middlewares))

(defn wrap-handler
  "Applies the configured middleware to the request handler."
  [{:keys [request-handler middlewares]}]
  (let [wrapper (->>
                 middlewares
                 normalize
                 reverse
                 (apply comp))]
    (wrapper request-handler)))
