(ns ehr-adapter.middleware.jsonista-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.middleware.jsonista :refer [wrap]]))

(deftest wrap-jsonista-test
  (testing "Serializes body when content-type is :json"
    (let [wrapped (wrap (fn [req]
                          {:status 200
                           :body nil
                           :headers {}
                           :request req}))
          result (wrapped {:method :post
                           :url "https://api.ehr.com/Patient"
                           :content-type :json
                           :body {:name "John" :age 30}})]
      (is (string? (get-in result [:request :body])))))

  (testing "Serializes body when content-type is :fhir/json"
    (let [wrapped (wrap (fn [req]
                          {:status 200
                           :body nil
                           :headers {}
                           :request req}))
          result (wrapped {:method :post
                           :url "https://api.ehr.com/Patient"
                           :content-type :fhir/json
                           :body {:resourceType "Patient"}})]
      (is (string? (get-in result [:request :body])))))

  (testing "Does not serialize body when content-type is not JSON"
    (let [wrapped (wrap (fn [req]
                          {:status 200
                           :body nil
                           :headers {}
                           :request req}))
          result (wrapped {:method :post
                           :url "https://api.ehr.com/Patient"
                           :content-type :xml
                           :body "<Patient/>"})]
      (is (= "<Patient/>" (get-in result [:request :body])))))

  (testing "Does not serialize body when content-type is absent"
    (let [wrapped (wrap (fn [req]
                          {:status 200
                           :body nil
                           :headers {}
                           :request req}))
          result (wrapped {:method :get
                           :url "https://api.ehr.com/Patient"})]
      (is (nil? (get-in result [:request :body])))))

  (testing "Deserializes response body when content-type is :json"
    (let [wrapped (wrap (fn [_]
                          {:status 200
                           :body "{\"name\":\"John\",\"age\":30}"
                           :content-type :json
                           :headers {}}))
          result (wrapped {:method :get :url "https://api.ehr.com/Patient"})]
      (is (map? (:body result)))
      (is (= "John" (get-in result [:body :name])))))

  (testing "Deserializes response body when content-type is :fhir/json"
    (let [wrapped (wrap (fn [_]
                          {:status 200
                           :body "{\"resourceType\":\"Patient\"}"
                           :content-type :fhir/json
                           :headers {}}))
          result (wrapped {:method :get :url "https://api.ehr.com/Patient"})]
      (is (map? (:body result)))
      (is (= "Patient" (get-in result [:body :resourceType])))))

  (testing "Does not deserialize response body when content-type is not JSON"
    (let [wrapped (wrap (fn [_]
                          {:status 200
                           :body "<Patient/>"
                           :content-type :xml
                           :headers {}}))
          result (wrapped {:method :get :url "https://api.ehr.com/Patient"})]
      (is (= "<Patient/>" (:body result)))))
  (testing "Serializes the request body correctly"
    (let [wrapped (wrap (fn [req]
                          {:status 200
                           :body nil
                           :headers {}
                           :request req}))
          result (wrapped {:method :post
                           :url "https://api.ehr.com/Patient"
                           :content-type :json
                           :body {:name "John"
                                  :age 30}})
          body (get-in result [:request :body])]
      (is (= {:name "John"
              :age 30}
             ((requiring-resolve 'jsonista.core/read-value)
              body
              @(requiring-resolve
                'jsonista.core/keyword-keys-object-mapper))))))

  (testing "Does not serialize excluded JSON media types"
    (let [wrapped (wrap
                   (fn [req]
                     {:status 200
                      :body nil
                      :headers {}
                      :request req})
                   {:includes #{:custom/json}})
          body {:name "John"}
          result (wrapped {:method :post
                           :url "https://api.ehr.com/Patient"
                           :content-type :other/json
                           :body body})]
      (is (= body
             (get-in result [:request :body])))))
  (testing "Uses the configured object mapper"
    (let [object-mapper (requiring-resolve 'jsonista.core/object-mapper)
          mapper (object-mapper {:encode-key-fn name})
          wrapped (wrap
                   (fn [req]
                     {:status 200
                      :body nil
                      :headers {}
                      :request req})
                   {:mapper mapper})
          result (wrapped {:method :post
                           :url "https://api.ehr.com/Patient"
                           :content-type :json
                           :body {:foo-bar "value"}})]
      (is (= "{\"foo-bar\":\"value\"}"
             (get-in result [:request :body]))))))
