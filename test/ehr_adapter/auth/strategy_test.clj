(ns ehr-adapter.auth.strategy-test
  (:require [clojure.test :refer [deftest is testing]]
            [ehr-adapter.auth.strategy :refer [->base64 format-scopes execute]]
            [ehr-adapter.auth.sign :as sign]))

;; =============================================================================
;; 1. UTILS TESTS
;; =============================================================================

(deftest utils-test
  (testing "->base64 encodes standard ASCII strings correctly"
    (is (= "YWxhZGRpbjpvcGVuc2VzYW1l" (->base64 "aladdin:opensesame")))
    (is (= "YnJvb29vbw==" (->base64 "brooooo"))))

  (testing "format-scopes handles collections and edge cases"
    (is (= "patient/*.read user/Practitioner.write" (format-scopes ["patient/*.read" "user/Practitioner.write"])))
    (is (= "launch" (format-scopes '("launch"))))
    (is (nil? (format-scopes nil)))
    (is (nil? (format-scopes [])))))

;; =============================================================================
;; 2. BASIC AUTH STRATEGY TESTS
;; =============================================================================

(deftest basic-auth-strategy-test
  (testing "Dual behavior: returns raw auth data map if no :request key is in options"
    (let [layer  {:type :basic-auth
                  :username "admin"
                  :password "secret123"
                  :options  {:other-context "data"}}
          result (execute layer nil)]
      (is (= "YWRtaW46c2VjcmV0MTIz" (:token result)))
      (is (= "Basic" (:token-type result)))))

  (testing "Dual behavior: injects headers and runs client if :request is present"
    (let [layer       {:type :basic-auth
                       :username "admin"
                       :password "secret123"
                       :options  {:request {:method :get
                                            :url "https://api.ehr.com/Patient"
                                            :headers {"Accept" "application/fhir+json"}}}}
          mock-client (fn [req] req)
          result      (execute layer mock-client)]

      (is (= :get (:method result)))
      (is (= "https://api.ehr.com/Patient" (:url result)))
      (is (= "application/fhir+json" (get-in result [:headers "Accept"])))
      (is (= "Basic YWRtaW46c2VjcmV0MTIz" (get-in result [:headers "Authorization"]))))))

;; =============================================================================
;; 3. CUSTOM AUTH STRATEGY TESTS
;; =============================================================================

(deftest custom-auth-strategy-test
  (testing "Executes user-defined handler passing options and client intact"
    (let [custom-handler (fn [opts client]
                           (client (assoc opts :custom-auth-applied? true)))
          layer          {:type :custom
                          :handler custom-handler
                          :options {:custom-prop "hello" :request {:timeout 1000}}}
          mock-client    (fn [req] req)
          result         (execute layer mock-client)]

      (is (= {:custom-prop "hello" :request {:timeout 1000} :custom-auth-applied? true}
             result)))))

;; =============================================================================
;; 4. OAUTH2 STRATEGY TESTS
;; =============================================================================

(deftest oauth2-strategy-test
  (let [base-layer {:type          :oauth2
                    :token-url     "https://ehr.example.com/oauth2/token"
                    :grant-type    "client_credentials"
                    :client-id     "my-client-id"
                    :client-secret "my-client-secret"
                    :scopes        ["patient/*.read"]}]

    (testing "Standard OAuth2 request formulation with formatted scopes"
      (let [mock-client (fn [req] req)
            result      (execute base-layer mock-client)]
        (is (= :post (:method result)))
        (is (= :form-url-encoded (:content-type result)))
        (is (= "https://ehr.example.com/oauth2/token" (:url result)))
        (is (= {"grant_type"    "client_credentials"
                "client_id"     "my-client-id"
                "client_secret" "my-client-secret"
                "scope"         "patient/*.read"}
               (:form-params result)))))

    (testing "Right-biased merge shields infrastructure and handles empty/nil scopes cleanly"
      (let [layer-with-opts (-> base-layer
                                (dissoc :scopes)
                                (assoc :options {:request {:timeout 5000
                                                           :method :get
                                                           :form-params {"client_id" "override-id"
                                                                         "custom_field" "extra-data"}}}))
            mock-client     (fn [req] req)
            result          (execute layer-with-opts mock-client)]

        (is (= :post (:method result)))
        (is (= 5000 (:timeout result)))
        (is (nil? (get-in result [:form-params "scope"])))
        (is (= "override-id" (get-in result [:form-params "client_id"])))
        (is (= "extra-data" (get-in result [:form-params "custom_field"])))))))

;; =============================================================================
;; 5. SMART ON FHIR BACKEND SERVICES TESTS
;; =============================================================================

(deftest smart-on-fhir-backend-services-test
  (let [base-layer {:type      :smart-on-fhir/backend-services
                    :token-url "https://ehr.example.com/smart/token"
                    :scopes    ["system/Patient.read"]
                    :options   {:request {:timeout 3000}}}]

    (with-redefs [sign/client-assertion (fn [layer] (str "mocked-jwt-for-" (:type layer)))]

      (testing "SMART request generation binds strict specification parameters"
        (let [mock-client (fn [req] req)
              result      (execute base-layer mock-client)]

          (is (= :post (:method result)))
          (is (= "https://ehr.example.com/smart/token" (:url result)))
          (is (= 3000 (:timeout result)))
          (is (= :form-url-encoded (:content-type result)))

          (is (= {"grant_type"            "client_credentials"
                  "client_assertion_type" "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
                  "client_assertion"      "mocked-jwt-for-:smart-on-fhir/backend-services"
                  "scope"                 "system/Patient.read"}
                 (:form-params result)))))

      (testing "SMART Flexibility: Merges developer-defined form-params flawlessly"
        (let [layer-with-extras (update-in base-layer [:options :request] assoc :form-params {"tenant_id" "ehr-7"})
              mock-client       (fn [req] req)
              result            (execute layer-with-extras mock-client)]

          (is (= "ehr-7" (get-in result [:form-params "tenant_id"])))
          (is (= "client_credentials" (get-in result [:form-params "grant_type"]))))))))
