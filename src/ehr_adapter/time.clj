(ns ehr-adapter.time)

(defn now
  "Returns the current Unix timestamp in seconds."
  []
  (quot (System/currentTimeMillis) 1000))
