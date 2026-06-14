(ns ehr-adapter.http.core)

(defn success?
  [response]
  (if-some [status (get response :status)]
    (<= 200 status 299)
    false))

