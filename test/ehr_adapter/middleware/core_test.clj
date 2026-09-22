(ns ehr-adapter.middleware.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.middleware.core :as middleware]))

(deftest normalize-test
  (testing "Leaves middleware functions unchanged"
    (let [middleware (fn [handler] handler)]
      (is (= [middleware]
             (middleware/normalize [middleware])))))

  (testing "Applies middleware options"
    (let [middleware (fn [option]
                       (fn [handler]
                         (fn [req]
                           (handler (assoc req :option option)))))
          normalized (middleware/normalize [[middleware :value]])
          middleware' (first normalized)
          handler (middleware' identity)]
      (is (= {:option :value}
             (handler {}))))))

(deftest wrap-handler-test
  (testing "Applies middleware to the request handler"
    (let [handler (fn [req]
                    (assoc req :handled true))
          middleware (fn [handler]
                       (fn [req]
                         (handler (assoc req :middleware true))))
          wrapped (middleware/wrap-handler
                   {:request-handler handler
                    :middlewares [middleware]})]
      (is (= {:middleware true
              :handled true}
             (wrapped {})))))

  (testing "Supports middleware with options"
    (let [handler (fn [req]
                    (assoc req :handled true))
          middleware (fn [option]
                       (fn [handler]
                         (fn [req]
                           (handler (assoc req :option option)))))
          wrapped (middleware/wrap-handler
                   {:request-handler handler
                    :middlewares [[middleware :value]]})]
      (is (= {:option :value
              :handled true}
             (wrapped {})))))

  (testing "Applies middlewares in order"
    (let [handler (fn [req]
                    (update req :trace conj :handler))
          middleware-a (fn [handler]
                         (fn [req]
                           (handler (update req :trace conj :a))))
          middleware-b (fn [handler]
                         (fn [req]
                           (handler (update req :trace conj :b))))
          wrapped (middleware/wrap-handler
                   {:request-handler handler
                    :middlewares [middleware-a middleware-b]})]
      (is (= [:b :a :handler]
             (:trace (wrapped {:trace []})))))))
