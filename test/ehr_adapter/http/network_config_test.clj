(ns ehr-adapter.http.network-config-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.http.network-config :refer [with-client with-retries]]))

(deftest with-retries-test

  (testing "1. Successful request on first attempt should not retry"
    (let [calls-count (atom 0)

          mock-handler (fn [_]
                         (swap! calls-count inc)
                         {:status 200 :body "Success"})

          wrapped-handler (with-retries mock-handler {:retries 3
                                                      :retry-on [500 503]
                                                      :retry-delay-ms 1})
          response (wrapped-handler {:uri "/fhir/Patient"})]
      (is (= 200 (:status response)))
      (is (= 1 @calls-count) "Should only be called exactly once")))

  (testing "2. Transient HTTP error (e.g., 503) should retry until exhausted"
    (let [calls-count (atom 0)

          mock-handler (fn [_]
                         (swap! calls-count inc)
                         {:status 503 :body "Service Unavailable"})
          wrapped-handler (with-retries mock-handler {:retries 2
                                                      :retry-on [503]
                                                      :retry-strategy :constant
                                                      :retry-delay-ms 1})
          response (wrapped-handler {:uri "/fhir/Patient"})]
      (is (= 503 (:status response)))
      (is (= 3 @calls-count) "Should attempt a total of 3 times (1 initial + 2 retries)")))

  (testing "3. Hard network exception (Throwable) should retry and finally throw"
    (let [calls-count (atom 0)

          mock-handler (fn [_]
                         (swap! calls-count inc)
                         (throw (RuntimeException. "Connection timed out")))
          wrapped-handler (with-retries mock-handler {:retries 2
                                                      :retry-delay-ms 1})]

      (is (thrown-with-msg? RuntimeException #"Connection timed out"
                            (wrapped-handler {:uri "/fhir/Patient"})))
      (is (= 3 @calls-count) "Should retry exceptions until limit is reached")))

  (testing "4. Status code not in retry-on should exit immediately without retrying"
    (let [calls-count (atom 0)

          mock-handler (fn [_]
                         (swap! calls-count inc)
                         {:status 404 :body "Not Found"})
          wrapped-handler (with-retries mock-handler {:retries 3
                                                      :retry-on [500 503]
                                                      :retry-delay-ms 1})

          response (wrapped-handler {:uri "/fhir/Patient"})]
      (is (= 404 (:status response)))
      (is (= 1 @calls-count) "Should not retry a 404 status error")))

  (testing "5. Before-retry callback execution"
    (let [callback-data (atom [])
          mock-handler  (fn [_] {:status 500})
          my-callback   (fn [_res _req attempt delay-ms]
                          (swap! callback-data conj {:attempt attempt :delay delay-ms}))
          wrapped-handler (with-retries mock-handler {:retries 2
                                                      :retry-on [500]
                                                      :retry-strategy :linear
                                                      :retry-delay-ms 10
                                                      :before-retry my-callback})]
      (wrapped-handler {:uri "/fhir/Patient"})

      (is (= [{:attempt 1 :delay 10} {:attempt 2 :delay 20}] @callback-data)
          "Callback should catch correct attempt indices and linear delay calculations"))))

(deftest with-client-test
  (testing "1. When :client is provided, it should be injected into the request"
    (let [captured-req (atom nil)
          mock-handler (fn [req]
                         (reset! captured-req req)
                         {:status 200 :body "OK"})
          custom-client :my-custom-http-client
          wrapped-handler (with-client mock-handler custom-client)
          response (wrapped-handler {:uri "/fhir/Patient" :method :get})]

      (is (= 200 (:status response)))
      (is (= custom-client (:client @captured-req))
          "Request should have :client injected")
      (is (= "/fhir/Patient" (:uri @captured-req))
          "Original request fields should be preserved")))

  (testing "2. When :client is nil, it should not be injected into the request"
    (let [captured-req (atom nil)
          mock-handler (fn [req]
                         (reset! captured-req req)
                         {:status 200 :body "OK"})
          wrapped-handler (with-client mock-handler nil)
          response (wrapped-handler {:uri "/fhir/Patient" :method :get})]

      (is (= 200 (:status response)))
      (is (nil? (:client @captured-req))
          "Request should not have :client when nil is provided")
      (is (= "/fhir/Patient" (:uri @captured-req))
          "Original request fields should be preserved")))

  (testing "3. When request already has :client, it should be overridden"
    (let [captured-req (atom nil)
          mock-handler (fn [req]
                         (reset! captured-req req)
                         {:status 200 :body "OK"})
          original-client :original-client
          new-client :new-client
          wrapped-handler (with-client mock-handler new-client)
          response (wrapped-handler {:uri "/fhir/Patient" :method :get :client original-client})]

      (is (= 200 (:status response)))
      (is (= new-client (:client @captured-req))
          "Request :client should be overridden with new value"))))
