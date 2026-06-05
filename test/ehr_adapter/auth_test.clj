(ns ehr-adapter.auth-test
  (:require [clojure.test :refer [is testing deftest]]
            [ehr-adapter.auth :as auth]))

(deftest bindings-test
  (let [mock-context {:tenant-data {:client-id "ecw-prod-99"
                                    :client-secret "super-secret-xyz"
                                    :api-version 2}
                      :session {:token "current-session-token-123"
                                :expires 3600}
                      :config {:sandbox? true}}]

    (testing "1. Extracts multiple bindings from different nesting levels of the context"
      (let [paths {:client-id     [:tenant-data :client-id]
                   :client-secret [:tenant-data :client-secret]
                   :session-token [:session :token]}]
        (is (= {:client-id     "ecw-prod-99"
                :client-secret "super-secret-xyz"
                :session-token "current-session-token-123"}
               (auth/bindings mock-context paths)))))

    (testing "2. Safely ignores paths that do not exist in the context without throwing"
      (let [paths {:client-id   [:tenant-data :client-id]
                   :missing-key [:garbage :path :here]
                   :sandbox      [:config :sandbox?]}]
        (is (= {:client-id "ecw-prod-99"
                :sandbox   true}
               (auth/bindings mock-context paths)))))

    (testing "3. Returns an empty map without altering or breaking anything"
      (is (= {} (auth/bindings mock-context {}))))))
