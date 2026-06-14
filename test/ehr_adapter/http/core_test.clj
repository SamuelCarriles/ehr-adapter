(ns ehr-adapter.http.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.http.core :as http]))

(deftest success?-test
  (testing "Standard HTTP 2xx success range"
    (is (true? (http/success? {:status 200})))
    (is (true? (http/success? {:status 201})))
    (is (true? (http/success? {:status 299})))
    (is (false? (http/success? {:status 302})))
    (is (false? (http/success? {:status 400})))
    (is (false? (http/success? {:status 500}))))

  (testing "Defensive behavior against invalid or missing values"
    (is (false? (http/success? nil)))
    (is (false? (http/success? [])))
    (is (false? (http/success? "a")))
    (is (false? (http/success? {:body "No status key"})))
    (is (false? (http/success? {:status nil})))))
