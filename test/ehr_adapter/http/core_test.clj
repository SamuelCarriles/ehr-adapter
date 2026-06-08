(ns ehr-adapter.http.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.http.core :as http]))

(deftest success?-test
  (testing "Standard HTTP 2xx success range"
    (is (true? (http/success? 200)))
    (is (true? (http/success? 201)))
    (is (true? (http/success? 299)))
    (is (false? (http/success? 302)))
    (is (false? (http/success? 400)))
    (is (false? (http/success? 500))))

  (testing "Custom expected status overrides"
    (is (true? (http/success? 404 [200 404])))
    (is (true? (http/success? 302 [302 307])))
    (is (false? (http/success? 500 [200 404]))))

  (testing "Defensive behavior against nil or empty expected-status"
    (is (true? (http/success? 200 nil)))
    (is (false? (http/success? 401 nil)))
    (is (false? (http/success? 401 [])))))
