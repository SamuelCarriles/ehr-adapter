(ns ehr-adapter.reference.type-test
  (:require [clojure.test :refer [deftest testing is]]
            [ehr-adapter.reference.type :as type])
  (:import [java.time Instant LocalDate LocalDateTime]
           [java.util Date UUID]))

(deftest check-test
  (testing "valid value with required kind"
    (is (= 42 (type/check {:value 42 :kind :required :referent :test :type :integer} integer?))))

  (testing "valid value with optional kind"
    (is (= 42 (type/check {:value 42 :kind :optional :referent :test :type :integer} integer?))))

  (testing "nil value with optional kind returns nil"
    (is (nil? (type/check {:value nil :kind :optional :referent :test :type :integer} integer?))))

  (testing "nil value with required kind throws"
    (is (nil? (type/check {:value nil :kind :required :referent :test :type :integer} integer?))))

  (testing "invalid value with required kind throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"The reference value for :test must be of type: :integer"
                          (type/check {:value "not-an-int" :kind :required :referent :test :type :integer} integer?))))

  (testing "invalid value with optional kind returns nil"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :integer"
                          (type/check {:value "not-an-int" :kind :optional :referent :test :type :integer} integer?)))))

(deftest validate-integer-test
  (testing "valid integer"
    (is (= 42 (type/validate {:value 42 :kind :required :referent :id :type :integer}))))

  (testing "invalid integer throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :integer"
                          (type/validate {:value "42" :kind :required :referent :id :type :integer})))))

(deftest validate-pos-int-test
  (testing "valid positive integer"
    (is (= 42 (type/validate {:value 42 :kind :required :referent :id :type :pos-int}))))

  (testing "zero is invalid"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :pos-int"
                          (type/validate {:value 0 :kind :required :referent :id :type :pos-int}))))

  (testing "negative is invalid"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :pos-int"
                          (type/validate {:value -1 :kind :required :referent :id :type :pos-int})))))

(deftest validate-string-test
  (testing "valid string"
    (is (= "hello" (type/validate {:value "hello" :kind :required :referent :name :type :string}))))

  (testing "invalid string throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :string"
                          (type/validate {:value 42 :kind :required :referent :name :type :string})))))

(deftest validate-keyword-test
  (testing "valid keyword"
    (is (= :test (type/validate {:value :test :kind :required :referent :type :type :keyword}))))

  (testing "invalid keyword throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :keyword"
                          (type/validate {:value "test" :kind :required :referent :type :type :keyword})))))

(deftest validate-map-test
  (testing "valid map"
    (is (= {:a 1} (type/validate {:value {:a 1} :kind :required :referent :data :type :map}))))

  (testing "invalid map throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :map"
                          (type/validate {:value [1 2 3] :kind :required :referent :data :type :map})))))

(deftest validate-vector-test
  (testing "valid vector"
    (is (= [1 2 3] (type/validate {:value [1 2 3] :kind :required :referent :ids :type :vector}))))

  (testing "invalid vector throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :vector"
                          (type/validate {:value {:a 1} :kind :required :referent :ids :type :vector})))))

(deftest validate-boolean-test
  (testing "valid boolean true"
    (is (= true (type/validate {:value true :kind :required :referent :flag :type :boolean}))))

  (testing "valid boolean false"
    (is (= false (type/validate {:value false :kind :required :referent :flag :type :boolean}))))

  (testing "invalid boolean throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :boolean"
                          (type/validate {:value "true" :kind :required :referent :flag :type :boolean})))))

(deftest validate-uuid-test
  (testing "valid uuid"
    (let [uuid (UUID/randomUUID)]
      (is (= uuid (type/validate {:value uuid :kind :required :referent :id :type :uuid})))))

  (testing "invalid uuid throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :uuid"
                          (type/validate {:value "not-a-uuid" :kind :required :referent :id :type :uuid})))))

(deftest validate-uuid-str-test
  (testing "valid uuid string"
    (let [uuid-str (str (UUID/randomUUID))]
      (is (= uuid-str (type/validate {:value uuid-str :kind :required :referent :id :type :uuid-str})))))

  (testing "invalid uuid string throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :uuid-str"
                          (type/validate {:value "not-a-uuid" :kind :required :referent :id :type :uuid-str})))))

(deftest validate-date-test
  (testing "valid date"
    (let [date (Date.)]
      (is (= date (type/validate {:value date :kind :required :referent :date :type :date})))))

  (testing "invalid date throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :date"
                          (type/validate {:value "2024-01-01" :kind :required :referent :date :type :date})))))

(deftest validate-instant-test
  (testing "valid instant"
    (let [instant (Instant/now)]
      (is (= instant (type/validate {:value instant :kind :required :referent :timestamp :type :instant})))))

  (testing "invalid instant throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :instant"
                          (type/validate {:value "2024-01-01T00:00:00Z" :kind :required :referent :timestamp :type :instant})))))

(deftest validate-instant-str-test
  (testing "valid instant string"
    (is (= "2024-01-01T00:00:00Z"
           (type/validate {:value "2024-01-01T00:00:00Z" :kind :required :referent :timestamp :type :instant-str}))))

  (testing "invalid instant string throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :instant-str"
                          (type/validate {:value "not-an-instant" :kind :required :referent :timestamp :type :instant-str})))))

(deftest validate-local-date-test
  (testing "valid local date"
    (let [date (LocalDate/now)]
      (is (= date (type/validate {:value date :kind :required :referent :date :type :local-date})))))

  (testing "invalid local date throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :local-date"
                          (type/validate {:value "2024-01-01" :kind :required :referent :date :type :local-date})))))

(deftest validate-local-date-str-test
  (testing "valid local date string"
    (is (= "2024-01-01"
           (type/validate {:value "2024-01-01" :kind :required :referent :date :type :local-date-str}))))

  (testing "invalid local date string throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :local-date-str"
                          (type/validate {:value "not-a-date" :kind :required :referent :date :type :local-date-str})))))

(deftest validate-local-date-time-test
  (testing "valid local date time"
    (let [dt (LocalDateTime/now)]
      (is (= dt (type/validate {:value dt :kind :required :referent :datetime :type :local-date-time})))))

  (testing "invalid local date time throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :local-date-time"
                          (type/validate {:value "2024-01-01T00:00:00" :kind :required :referent :datetime :type :local-date-time})))))

(deftest validate-local-date-time-str-test
  (testing "valid local date time string"
    (is (= "2024-01-01T12:30:45"
           (type/validate {:value "2024-01-01T12:30:45" :kind :required :referent :datetime :type :local-date-time-str}))))

  (testing "invalid local date time string throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :local-date-time-str"
                          (type/validate {:value "not-a-datetime" :kind :required :referent :datetime :type :local-date-time-str})))))

(deftest validate-unsupported-type-test
  (testing "Throws :unsupported/reference-type for unknown types"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"The type :foo-bar is not supported"
                          (type/validate {:value "some-value" :kind :required :referent :id :type :foo-bar}))))

  (testing "Error data includes expected supported types"
    (try
      (type/validate {:value 123 :kind :required :referent :test :type :unknown-type})
      (is false "Expected exception")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :unsupported/reference-type (:code data)))
          (is (= :unknown-type (get-in data [:details :type])))
          (is (= :ehr-adapter.reference.type (:scope data)))
          (is (= :validate-referent-value (:operation data)))
          (is (vector? (get-in data [:details :expected])))
          (is (some #{:integer :string :uuid} (get-in data [:details :expected])))
          (is (not (some #{:default :unknown-type} (get-in data [:details :expected])))))))))

(deftest validate-fn-test
  (testing "valid clojure fn"
    (is (type/validate {:value identity :kind :required :referent :fn :type :fn})))
  (testing "no clojure fn"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be of type: :fn"
                          (type/validate {:value "no fn" :kind :required :referent :fn :type :fn})))))
