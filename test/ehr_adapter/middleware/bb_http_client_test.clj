(ns ehr-adapter.middleware.bb-http-client-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.middleware.bb-http-client :refer [->bb-req <-bb-response wrap-bb-http-client]]))

;; =============================================================================
;; ->bb-req Tests
;; =============================================================================

(deftest ->bb-req-test
  (testing "Renames motor keys to bb keys"
    (let [req {:method :get
               :url "https://api.ehr.com/Patient"
               :timeout-ms 5000
               :throw-exceptions false}
          result (->bb-req req)]
      (is (= "https://api.ehr.com/Patient" (:uri result)))
      (is (= 5000 (:timeout result)))
      (is (= false (:throw result)))
      (is (nil? (:url result)))
      (is (nil? (:timeout-ms result)))
      (is (nil? (:throw-exceptions result)))))

  (testing "Removes :content-type and :accept from root and injects them into :headers"
    (let [req {:method :get
               :url "https://api.ehr.com/Patient"
               :content-type :json
               :accept :fhir/json}
          result (->bb-req req)]
      (is (nil? (:content-type result)))
      (is (nil? (:accept result)))
      (is (= "application/json" (get-in result [:headers "Content-Type"])))
      (is (= "application/fhir+json" (get-in result [:headers "Accept"])))))

  (testing "Merges injected headers with existing ones without losing them"
    (let [req {:method :get
               :url "https://api.ehr.com/Patient"
               :headers {"Authorization" "Bearer token-123"}
               :content-type :json}
          result (->bb-req req)]
      (is (= "Bearer token-123" (get-in result [:headers "Authorization"])))
      (is (= "application/json" (get-in result [:headers "Content-Type"])))))

  (testing "Does not inject headers when :content-type and :accept are absent"
    (let [req {:method :get
               :url "https://api.ehr.com/Patient"}
          result (->bb-req req)]
      (is (nil? (:headers result))))))

;; =============================================================================
;; <-bb-response Tests
;; =============================================================================

(deftest <-bb-response-test
  (testing "Parses content-type header into structured engine map"
    (let [response {:status 200
                    :body "{}"
                    :headers {"content-type" "application/fhir+json; charset=utf-8"
                              "server" "nginx"}}
          result (<-bb-response response)]
      (is (= {:code :fhir/json :properties {"charset" "utf-8"}}
             (:content-type result)))
      (is (nil? (get-in result [:headers "content-type"])))
      (is (= "nginx" (get-in result [:headers "server"])))))

  (testing "No :content-type in response leaves headers untouched"
    (let [response {:status 204
                    :body nil
                    :headers {"server" "nginx"}}
          result (<-bb-response response)]
      (is (nil? (:content-type result)))
      (is (= "nginx" (get-in result [:headers "server"])))))

  (testing "Plain JSON content-type parses correctly"
    (let [response {:status 200
                    :body "{}"
                    :headers {"content-type" "application/json"}}
          result (<-bb-response response)]
      (is (= {:code :json} (:content-type result))))))

;; =============================================================================
;; wrap-bb-http-client Tests
;; =============================================================================

(deftest wrap-bb-http-client-test
  (testing "Full roundtrip: motor request goes in, motor response comes out"
    (let [handler (fn [bb-req]
                    {:status 200
                     :body "{}"
                     :headers {"content-type" "application/json"
                               "server" "nginx"}
                     :request bb-req})
          wrapped (wrap-bb-http-client handler)
          result (wrapped {:method :get
                           :url "https://api.ehr.com/Patient"
                           :timeout-ms 3000
                           :content-type :json
                           :accept :json})]
      (is (= 200 (:status result)))
      (is (= {:code :json} (:content-type result)))
      (is (= "nginx" (get-in result [:headers "server"])))))

  (testing "Handler receives a valid bb request map"
    (let [handler (fn [bb-req]
                    {:status 200
                     :body ""
                     :headers {}
                     :request bb-req})
          wrapped (wrap-bb-http-client handler)
          result (wrapped {:method :post
                           :url "https://api.ehr.com/Patient"
                           :timeout-ms 5000
                           :throw-exceptions false
                           :content-type :fhir/json})
          bb-req (:request result)]
      (is (= :post (:method bb-req)))
      (is (= "https://api.ehr.com/Patient" (:uri bb-req)))
      (is (= 5000 (:timeout bb-req)))
      (is (= false (:throw bb-req)))
      (is (= "application/fhir+json" (get-in bb-req [:headers "Content-Type"])))
      (is (nil? (:url bb-req)))
      (is (nil? (:timeout-ms bb-req))))))
