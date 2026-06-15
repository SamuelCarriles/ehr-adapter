(ns ehr-adapter.http.network-config
  (:require [clojure.tools.logging :as log]))

(defn needs-retry?
  [response retry-on attempt max-retries]
  (let [status (:status response)
        retry-on (set retry-on)]
    (and
     (<= attempt max-retries)
     (or
      (instance? Throwable response)
      (retry-on status)))))

(defn calculate-delay
  [strategy base-delay-ms attempt]
  (case strategy
    :linear (* base-delay-ms attempt)

    :exponential
    (let [exponent (dec attempt)
          factor (long (Math/pow 2 exponent))]
      (* base-delay-ms factor))

    base-delay-ms))

(defn with-retries
  "Wraps a handler with a synchronous retry policy."
  [handler {:keys [retries retry-on retry-delay-ms before-retry retry-strategy]}]
  (fn [req]
    (if (or (nil? retries) (not (pos-int? retries)))
      (handler req)

      (loop [attempt 1]
        (let [response (try
                         (handler req)
                         (catch Throwable e
                           e))]
          (if (needs-retry? response retry-on attempt retries)
            (let [delay-ms (calculate-delay retry-strategy retry-delay-ms attempt)]
              (log/warnf "HTTP request failed (Attempt %d of %d). Retrying in %d ms. Detail: %s"
                         attempt
                         retries
                         delay-ms
                         (if (instance? Throwable response)
                           (.getMessage response)
                           (str "Status " (:status response))))

              (when before-retry
                (try
                  (before-retry response req attempt delay-ms)
                  (catch Throwable t
                    (log/error t "Critical error executing user's :before-retry callback"))))

              (Thread/sleep delay-ms)

              (recur (inc attempt)))
            (if (instance? Throwable response)
              (throw response)
              response)))))))




