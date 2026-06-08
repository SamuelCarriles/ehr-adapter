(ns ehr-adapter.http.core)

(defn success?
  ([status] (success? status nil))
  ([status expected-status]
   (if-not (some? status)
     false
     (or (<= 200 status 299)
         (contains? (set expected-status) status)))))
