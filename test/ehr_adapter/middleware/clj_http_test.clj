(ns ehr-adapter.middleware.clj-http-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.middleware.clj-http :refer [->clj-http-req <-clj-http-response wrap]]))

;; =============================================================================
;; ->clj-http-req Tests
;; =============================================================================

(deftest ->clj-http-req-test
  (testing "Renames :timeout-ms to both :connection-timeout and :socket-timeout"
    (let [req {:method :get
               :url "https://api.ehr.com/Patient"
               :timeout-ms 5000}
          result (->clj-http-req req)]
      (is (= 5000 (:connection-timeout result)))
      (is (= 5000 (:socket-timeout result)))
      (is (= false (:throw-exceptions? result)))
      (is (nil? (:timeout-ms result)))))

  (testing "Does not add timeout keys when :timeout-ms is absent"
    (let [req {:method :get
               :url "https://api.ehr.com/Patient"}
          result (->clj-http-req req)]
      (is (nil? (:connection-timeout result)))
      (is (nil? (:socket-timeout result)))))

  (testing ":url stays as :url (clj-http accepts it natively)"
    (let [req {:method :get
               :url "https://api.ehr.com/Patient"}
          result (->clj-http-req req)]
      (is (= "https://api.ehr.com/Patient" (:url result)))))

  (testing "Removes :content-type and :accept from root and injects them into :headers"
    (let [req {:method :get
               :url "https://api.ehr.com/Patient"
               :content-type :json
               :accept :fhir/json}
          result (->clj-http-req req)]
      (is (nil? (:content-type result)))
      (is (nil? (:accept result)))
      (is (= "application/json" (get-in result [:headers "Content-Type"])))
      (is (= "application/fhir+json" (get-in result [:headers "Accept"])))))

  (testing "Merges injected headers with existing ones without losing them"
    (let [req {:method :get
               :url "https://api.ehr.com/Patient"
               :headers {"Authorization" "Bearer token-123"}
               :content-type :json}
          result (->clj-http-req req)]
      (is (= "Bearer token-123" (get-in result [:headers "Authorization"])))
      (is (= "application/json" (get-in result [:headers "Content-Type"])))))

  (testing "Does not inject Content-Type header when :content-type is :form-url-encoded (keyword)"
    (let [req {:method :post
               :url "https://api.ehr.com/Patient"
               :content-type :form-url-encoded
               :form-params {:a 1}}
          result (->clj-http-req req)]
      (is (nil? (get-in result [:headers "Content-Type"])))
      (is (nil? (:content-type result)))))

  (testing "Does not inject Content-Type header when :content-type is a map with :code :form-url-encoded"
    (let [req {:method :post
               :url "https://api.ehr.com/Patient"
               :content-type {:code :form-url-encoded}
               :form-params {:a 1}}
          result (->clj-http-req req)]
      (is (nil? (get-in result [:headers "Content-Type"])))
      (is (nil? (:content-type result)))))

  (testing "Does not inject headers when :content-type and :accept are absent"
    (let [req {:method :get
               :url "https://api.ehr.com/Patient"}
          result (->clj-http-req req)]
      (is (nil? (:headers result))))))

;; =============================================================================
;; <-clj-http-response Tests
;; =============================================================================

(deftest <-clj-http-response-test
  (testing "Parses Content-Type header into structured engine map AND keeps it in headers"
    (let [response {:status 200
                    :body "{}"
                    :headers {"Content-Type" "application/fhir+json; charset=utf-8"
                              "Server" "nginx"}}
          result (<-clj-http-response response)]
      (is (= {:code :fhir/json :properties {"charset" "utf-8"}}
             (:content-type result)))
      (is (= "application/fhir+json; charset=utf-8" (get-in result [:headers "Content-Type"])))
      (is (= "nginx" (get-in result [:headers "Server"])))))

  (testing "No Content-Type in response leaves headers untouched"
    (let [response {:status 204
                    :body nil
                    :headers {"Server" "nginx"}}
          result (<-clj-http-response response)]
      (is (nil? (:content-type result)))
      (is (= "nginx" (get-in result [:headers "Server"])))))

  (testing "Plain JSON Content-Type parses correctly and remains in headers"
    (let [response {:status 200
                    :body "{}"
                    :headers {"Content-Type" "application/json"}}
          result (<-clj-http-response response)]
      (is (= {:code :json} (:content-type result)))
      (is (= "application/json" (get-in result [:headers "Content-Type"]))))))

;; =============================================================================
;; wrap Tests
;; =============================================================================

(deftest wrap-test
  (testing "Full roundtrip: motor request goes in, motor response comes out"
    (let [handler (fn [clj-req]
                    {:status 200
                     :body "{}"
                     :headers {"Content-Type" "application/json"
                               "Server" "nginx"}
                     :request clj-req})
          wrapped (wrap handler)
          result (wrapped {:method :get
                           :url "https://api.ehr.com/Patient"
                           :timeout-ms 3000
                           :content-type :json
                           :accept :json})]
      (is (= 200 (:status result)))
      (is (= {:code :json} (:content-type result)))
      (is (= "nginx" (get-in result [:headers "Server"])))))

  (testing "Handler receives a valid clj-http request map"
    (let [handler (fn [clj-req]
                    {:status 200
                     :body ""
                     :headers {}
                     :request clj-req})
          wrapped (wrap handler)
          result (wrapped {:method :post
                           :url "https://api.ehr.com/Patient"
                           :timeout-ms 5000
                           :content-type :fhir/json})
          clj-req (:request result)]
      (is (= :post (:method clj-req)))
      (is (= "https://api.ehr.com/Patient" (:url clj-req)))
      (is (= 5000 (:connection-timeout clj-req)))
      (is (= 5000 (:socket-timeout clj-req)))
      (is (= false (:throw-exceptions? clj-req)))
      (is (= "application/fhir+json" (get-in clj-req [:headers "Content-Type"])))
      (is (nil? (:timeout-ms clj-req))))))

(deftest wrap-errors-test
  (testing "Unsuccessful HTTP status flows normally (no longer short-circuited by middleware)"
    (let [handler (fn [clj-req]
                    {:status 401
                     :body "{\"error\": \"unauthorized\"}"
                     :headers {"Content-Type" "application/json"}
                     :request clj-req})
          wrapped (wrap handler)
          result (wrapped {:method :get :url "https://api.ehr.com/Patient"})]
      (is (= 401 (:status result)))
      (is (= "{\"error\": \"unauthorized\"}" (:body result)))
      (is (= {:code :json} (:content-type result)))))

  (testing "HTTP status marked as :expected-status flows normally"
    (let [handler (fn [clj-req]
                    {:status 404
                     :body "{\"issue\": \"not found\"}"
                     :headers {"Content-Type" "application/json"}
                     :request clj-req})
          wrapped (wrap handler)
          result (wrapped {:method :get
                           :url "https://api.ehr.com/Patient"
                           :expected-status [404]})]
      (is (= 404 (:status result)))
      (is (= "{\"issue\": \"not found\"}" (:body result)))
      (is (= "https://api.ehr.com/Patient" (get-in result [:request :url])))))

  (testing "Hard JVM network exception is unified under :http/failure"
    (let [handler (fn [_]
                    (throw (java.net.ConnectException. "Connection refused")))
          wrapped (wrap handler)]
      (try
        (wrapped {:method :get :url "https://api.ehr.com/Patient"})
        (is false "Should have thrown an exception")
        (catch clojure.lang.ExceptionInfo e
          (let [err-data (ex-data e)
                exception (get-in err-data [:details :exception])]
            (is (= :http/failure (:code err-data)))
            (is (= :ehr-adapter.middleware.clj-http (:scope err-data)))
            (is (= :http-request (:operation err-data)))
            (is (instance? java.net.ConnectException exception))
            (is (= "Connection refused" (.getMessage e)))))))))
