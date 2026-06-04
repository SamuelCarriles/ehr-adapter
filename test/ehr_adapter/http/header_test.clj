(ns ehr-adapter.http.header-test
  (:require [clojure.test :refer [deftest is are testing]]
            [ehr-adapter.http.header :as header]))

(deftest parse-mime-test
  (testing "Parsing registered and unsupported MIME types"
    (are [expected input] (= expected (header/parse-mime input))

      {:code :json} "application/json"

      {:code :fhir/json} "application/fhir+json"

      {:code :json :properties {"charset" "utf-8"}} "application/json; charset=utf-8"

      {:code :fhir/json :properties {"charset" "utf-8" "version" "1.0"}} "application/fhir+json;charset=utf-8;   version=1.0"

      {:code :unsupported :raw-mime "application/custom"} "application/custom"

      {:code :unsupported :raw-mime "application/x-unknown" :properties {"boundary" "xyz"}} "application/x-unknown; boundary=xyz")))

(deftest ->mime-test
  (testing "Successful transformations from keywords and maps"
    (are [expected input] (= expected (header/->mime input))

      "application/json"                  :json

      "application/fhir+json"             :fhir/json

      "text/plain"                        :text

      "application/json"                  {:code :json}

      "application/fhir+json; charset=utf-8" {:code :fhir/json :properties {"charset" "utf-8"}}

      "application/json; charset=utf-8; version=2" {:code :json :properties {"charset" "utf-8" "version" "2"}}

      "application/x-custom"              {:code :unsupported :raw-mime "application/x-custom"}

      "application/x-custom; boundary=12" {:code :unsupported :raw-mime "application/x-custom" :properties {"boundary" "12"}}))

  (testing "Threw exceptions for unsupported keywords or invalid structures"

    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported mime-code"
                          (header/->mime :invalid-format)))

    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid Content-Type map structure"
                          (header/->mime {:code :unsupported :properties {"boundary" "12"}})))

    ;; Tipo de dato totalmente inválido
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid mime-code format"
                          (header/->mime "application/json")))))

(deftest content-type-and-accept-test
  (testing "Successful header injection using are"
    (are [expected-header input] (and (= {"Content-Type" expected-header} (header/content-type input))
                                      (= {"Accept" expected-header}       (header/accept input)))

      "application/json"       :json

      "application/fhir+json"  :fhir/json

      "application/json"       {:code :json}

      "text/plain; charset=1"  {:code :text :properties {"charset" "1"}}

      "application/x-custom"   {:code :unsupported :raw-mime "application/x-custom"}))

  (testing "Handling nil values safely"
    (is (nil? (header/content-type nil)))
    (is (nil? (header/accept nil))))

  (testing "Propagation of exceptions for invalid formats"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported mime-code"
                          (header/content-type :unknown-format)))

    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid mime-code format"
                          (header/accept "application/json")))))
