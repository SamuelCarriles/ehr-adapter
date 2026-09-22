(ns ehr-adapter.http.header-test
  (:require [clojure.test :refer [deftest is are testing]]
            [ehr-adapter.http.header :as header]))

(deftest parse-mime-test
  (testing "Parsing registered and unsupported MIME types"
    (are [expected input] (= expected (header/parse-mime input))

      {:code :json} "application/json"

      {:code :fhir/json} "application/fhir+json"

      {:code :json :properties {"charset" "utf-8"}} "application/json; charset=utf-8"

      {:code :fhir/json :properties {"charset" "utf-8" "version" "1.0"}}
      "application/fhir+json;charset=utf-8;version=1.0"

      {:code :unsupported :raw-mime "application/custom"}
      "application/custom"

      {:code :unsupported
       :raw-mime "application/x-unknown"
       :properties {"boundary" "xyz"}}
      "application/x-unknown; boundary=xyz"))

  (testing "Parsing custom MIME types"
    (are [expected input mime-codes]
         (= expected (header/parse-mime input mime-codes))

      {:code :json+fhir}
      "application/json+fhir"
      {:json+fhir "application/json+fhir"}

      {:code :json+fhir
       :properties {"charset" "utf-8"}}
      "application/json+fhir; charset=utf-8"
      {:json+fhir "application/json+fhir"}))

  (testing "Custom MIME types take precedence over default mappings"
    (is (= {:code :custom-json}
           (header/parse-mime
            "application/json"
            {:custom-json "application/json"})))))

(deftest ->mime-test
  (testing "Successful transformations from keywords and maps"
    (are [expected input] (= expected (header/->mime input))

      "application/json" :json

      "application/fhir+json" :fhir/json

      "text/plain" :text

      "application/json" {:code :json}

      "application/fhir+json; charset=utf-8"
      {:code :fhir/json :properties {"charset" "utf-8"}}

      "application/json; charset=utf-8; version=2"
      {:code :json :properties {"charset" "utf-8" "version" "2"}}

      "application/x-custom"
      {:code :unsupported :raw-mime "application/x-custom"}

      "application/x-custom; boundary=12"
      {:code :unsupported
       :raw-mime "application/x-custom"
       :properties {"boundary" "12"}}))

  (testing "Successful transformations with custom MIME types"
    (are [expected input mime-codes]
         (= expected (header/->mime input mime-codes))

      "application/json+fhir"
      :json+fhir
      {:json+fhir "application/json+fhir"}

      "application/json+fhir"
      {:code :json+fhir}
      {:json+fhir "application/json+fhir"}

      "application/json+fhir; charset=utf-8"
      {:code :json+fhir :properties {"charset" "utf-8"}}
      {:json+fhir "application/json+fhir"}))

  (testing "Threw exceptions for unsupported keywords or invalid structures"

    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported MIME code"
                          (header/->mime :invalid-format)))

    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid MIME code map"
                          (header/->mime {:code :unsupported :properties {"boundary" "12"}})))

    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid MIME code format"
                          (header/->mime "application/json")))))

(deftest content-type-and-accept-test
  (testing "Successful header injection using are"
    (are [expected-header input]
         (and (= {"Content-Type" expected-header} (header/content-type input))
              (= {"Accept" expected-header} (header/accept input)))

      "application/json" :json

      "application/fhir+json" :fhir/json

      "application/json" {:code :json}

      "text/plain; charset=1"
      {:code :text :properties {"charset" "1"}}

      "application/x-custom"
      {:code :unsupported :raw-mime "application/x-custom"}))

  (testing "Successful header injection with custom MIME types"
    (let [mime-codes {:json+fhir "application/json+fhir"}]
      (is (= {"Content-Type" "application/json+fhir"}
             (header/content-type :json+fhir mime-codes)))

      (is (= {"Accept" "application/json+fhir"}
             (header/accept :json+fhir mime-codes)))

      (is (= {"Content-Type" "application/json+fhir; charset=utf-8"}
             (header/content-type
              {:code :json+fhir
               :properties {"charset" "utf-8"}}
              mime-codes)))))

  (testing "Handling nil values safely"
    (is (nil? (header/content-type nil)))
    (is (nil? (header/accept nil))))

  (testing "Propagation of exceptions for invalid formats"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported MIME code"
                          (header/content-type :unknown-format)))

    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid MIME code format"
                          (header/accept "application/json")))))

(deftest json-media-type?-test
  (testing "Returns true for JSON keywords"
    (is (header/json-media-type? :json))
    (is (header/json-media-type? :fhir/json)))

  (testing "Returns true for structured maps with JSON code"
    (is (header/json-media-type? {:code :json}))
    (is (header/json-media-type?
         {:code :fhir/json :properties {"charset" "utf-8"}})))

  (testing "Returns false for non-JSON types"
    (is (not (header/json-media-type? :xml)))
    (is (not (header/json-media-type? :text)))
    (is (not (header/json-media-type? {:code :xml}))))

  (testing "Supports additional JSON media types through :includes"
    (is (header/json-media-type?
         :json+fhir
         {:includes #{:json+fhir}}))

    (is (header/json-media-type?
         {:code :json+fhir}
         {:includes #{:json+fhir}})))

  (testing "Default JSON media types are always included"
    (is (header/json-media-type?
         :json
         {:includes #{:json+fhir}}))

    (is (header/json-media-type?
         :fhir/json
         {:includes #{:json+fhir}})))

  (testing "Additional JSON media types are not included by default"
    (is (not (header/json-media-type? :json+fhir)))
    (is (not (header/json-media-type? {:code :json+fhir}))))

  (testing "Returns false for nil"
    (is (not (header/json-media-type? nil)))))
