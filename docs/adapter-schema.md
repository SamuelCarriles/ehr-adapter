# Adapter Schema

The adapter works in a straightforward way: it receives a configuration map
and builds a functional adapter based on it.

## Schema

|               Key                | Value Type |                                            Format                                            | Required? |                                                                                                                                                         Description |
| :------------------------------: | :--------: | :------------------------------------------------------------------------------------------: | :-------: | ------------------------------------------------------------------------------------------------------------------------------------------------------------------: |
|            `:domain`             |  keyword   |                                   `:provider/customer-id`                                    |   true    |                                                                                                                                    Unique identifier of the adapter |
|           `:base-url`            |   string   |                                       valid url format                                       |   true    |                                                                                                                                    Base endpoint of the FHIR server |
|             `:auth`              |    map     |                               See `:auth` field details below                                |   true    |                                                                                                                                     Required configuration for auth |
|      `[:auth :grant-type]`       |  keyword   |                                  `:client-secret` or `:jwt`                                  |   true    |                                                                                                                         Defines the token acquisition flow for auth |
|       `[:auth :client-id]`       |   string   |                                         valid string                                         |   true    |                                                                                                                        `client_id` registered with the EHR provider |
|     `[:auth :client-secret]`     |   string   |                                         valid string                                         |   cond.   |                                                                                         Required if `:grant-type :client-secret`. Optional for `:jwt` or `:api-key` |
|      `[:auth :private-key]`      |   string   |                                 PEM/JWK string or file path                                  |   cond.   |                                                                                                  Required if `:grant-type :jwt`. Private key for signing assertions |
|        `[:auth :key-id]`         |   string   |                                         valid string                                         |   cond.   |                                                                              Key identifier (`kid`) for the public key registered with the provider (JWT flow only) |
|       `[:auth :algorithm]`       |   string   |                               `"RS256"`, `"ES256"`, `"HS256"`                                |   cond.   |                                                                                               Signing algorithm for JWT assertions (required if `:grant-type :jwt`) |
|       `[:auth :audience]`        |   string   |                                       valid url format                                       |   false   |                                                                                                 `aud` claim for JWT assertions. Defaults to `:token-url` if omitted |
|        `[:auth :scopes]`         |   vector   |                                    `["scope1" "scope2"]`                                     |   false   |                                                                                                                               List of OAuth/SMART scopes to request |
|        `[:auth :api-key]`        |   string   |                                         valid string                                         |   cond.   |                                     API key value. Required if `:grant-type :api-key`. The middleware is responsible for injecting it into the corresponding header |
|        `[:auth :extras]`         |    map     |                                      `{key value ...}`                                       |   false   | Open map for vendor-specific credentials/params (e.g., `:username`, `:password`, `:officekey`, `:tenant-id`). Consumed by the auth middleware according to the flow |
|       `[:auth :token-url]`       |   string   |                                       valid url format                                       |   false   |                                                                                                                                    Custom endpoint to obtain tokens |
|        `:http-client-fn`         |  function  |                                     `(fn [req] => resp)`                                     |   true    |                                                                                                                         Function that executes the raw HTTP request |
|          `:middlewares`          |   vector   |                                   `[mid-fn1 mid-fn2 ...]`                                    |   true    |                                                                       Vector of functions chained with `->`. Each receives and returns a single map. Order matters. |
|           `:policies`            |    map     |          `keys [:timeout-ms :retries :retry-delay-ms :retry-on :refresh-token-on]`           |   false   |                                                                                                                                  Resilience and retry configuration |
|    `[:policies :timeout-ms]`     |    int     |                                      positive int (ms)                                       |   false   |                                                                                                                                     Maximum wait time per operation |
|      `[:policies :retries]`      |    int     |                                       non-negative int                                       |   false   |                                                                                                                                           Maximum number of retries |
|  `[:policies :retry-delay-ms]`   |    int     |                                      positive int (ms)                                       |   false   |                                                                                                                                       Initial delay between retries |
|     `[:policies :retry-on]`      |   vector   |                                       `[429 500 503]`                                        |   false   |                                                                                                                                     HTTP codes that trigger a retry |
| `[:policies :refresh-token-on]`  |   vector   |                                           `[401]`                                            |   false   |                                                                                                                             HTTP codes that trigger a token refresh |
|          `:operations`           |   vector   | Vector of maps with keys `[:name :path :method :expected-status :base-headers :description]` |   false   |                                                                                                                   Definition of operations supported by the adapter |
|      `[:operations :name]`       |  keyword   |                             `:$export`, `:search-patient`, etc.                              |   true    |                                                                                                  Unique identifier of the operation. Used in `:execute` for lookup. |
|      `[:operations :path]`       |   vector   |                             `["/static" :dynamic "$operation"]`                              |   true    |                                                        Path segments. Keywords are resolved from `:params`. Strings are kept literal. Segments are joined with `/`. |
|     `[:operations :method]`      |  keyword   |                               `:post`, `:get`, `:patch`, etc.                                |   true    |                                                                                                                                       HTTP verb for this operation. |
| `[:operations :expected-status]` |   vector   |                                        integer vector                                        |   false   |                                                                                                                HTTP codes considered successful. Default is `[200]` |
|  `[:operations :base-headers]`   |    map     |                      `{"Prefer" "respond-async", "X-Api-Version" "2"}`                       |   false   |                                      Static headers merged into every request of this operation. If a header value is a keyword, it will be resolved from `:params` |
|   `[:operations :description]`   |   string   |                              `"Initiates bulk patient export"`                               |   false   |                                                                                                               For logging, metrics, and documentation purposes only |

---

## Examples

### Full Adapter Example (SMART on FHIR with JWT)

```clj
{:domain :eclinicalworks/FFDAC
 :base-url "https://base-url.com/api/v2"
 :auth {:grant-type :jwt
        :client-id "healow-backend-app"
        :private-key "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA...\n-----END RSA PRIVATE KEY-----"
        :key-id "kid-ecw-2024"
        :algorithm "RS256"
        :audience "https://auth.healow.com/oauth2/token"
        :scopes ["system/Patient.read" "system/Appointment.write"]}
 :http-client-fn clj-http.client/request
 :middlewares [ehr-adapter.middleware/clj-http]
 :policies {:timeout-ms 5000
            :retries 2
            :retry-delay-ms 500
            :retry-on [429 503]
            :refresh-token-on [401]}
 :operations [{:name :$export
               :path ["/fhir" :group-id "$export"]
               :method :post
               :expected-status [201 202]
               :base-headers {"Prefer" "respond-async"}
               :description "FHIR $export operation"}]}
```

---

### `:auth` Examples by Type

#### SMART on FHIR with `client_secret` (simpler flow)

```clj
:auth {:grant-type :client-secret
       :client-id "my-app-id"
       :client-secret "my-app-secret"
       :scopes ["patient/Appointment.write" "user/Patient.read"]
       :token-url "https://fhir.example.com/oauth2/token"}
```

#### SMART on FHIR with JWT (enterprise/secure flow)

```clj
:auth {:grant-type :jwt
       :client-id "backend-service-123"
       :private-key "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----"
       :key-id "kid-prod-2024"
       :algorithm "RS256"
       :audience "https://auth.epic.com/oauth2/token"
       :scopes ["system/Encounter.read" "system/Patient.read"]
       :token-url "https://auth.epic.com/oauth2/token"}
```

#### OAuth2 generic (non-SMART)

```clj
:auth {:grant-type :client-secret
       :client-id "generic-oauth-client"
       :client-secret "generic-secret"
       :scopes ["read" "write"]
       :token-url "https://oauth.provider.com/token"}
```

#### API Key (simplest, no OAuth)

```clj
:auth {:grant-type :api-key
       :client-id "api-key-identifier"  ; optional, for logging/tracing
       :api-key "sk_live_abc123xyz"
       :scopes []}                      ; not used in API-key flow
; Note: The API key itself is typically passed via :base-headers in :operations
; or injected by a middleware that reads it from env/config
```

#### AdvancedMD Custom Flow (with :extras)

```clj
:auth {:grant-type :client-secret
       :client-id "bulk-app-key"
       :client-secret "bulk-app-secret"
       :extras {:username "test-user"
                :password "test-pass"
                :officekey "991900"}    ; vendor-specific params for 2-step auth
       :token-url "/v1/oauth2/token"
       :scopes ["system/*.read"]}
```

---

### Operations Examples (Dynamic Path & Headers)

#### Bulk Export with Dynamic Group ID

```clj
:operations [{:name :$export
              :path ["/v1/r4/Group" :groupId "$export"]
              :method :post
              :expected-status [202]
              :base-headers {"Prefer" "respond-async" "OfficeKey" :officekey}
              :description "Initiates bulk export for a specific group"}]
```

Usage from service:

```clj
(adapter/execute {:operation :$export
                  :params      {:groupId "991900" :officekey "991900"}  ; for interpolation only
                  :query-params {:_type "Patient,Encounter" :_since "2024-01-01"} ; actual query string
                  :body        nil})
;; Resolves to: POST /v1/r4/Group/991900/$export?_type=Patient,Encounter&_since=2024-01-01
;; With headers: {"Prefer" "respond-async", "OfficeKey" "991900", "Authorization" "Bearer ..."}
```

#### Fetch Resource by Batch ID (Dynamic Path Segments)

```clj
:operations [{:name :fetch-resource
              :path ["/v1/fhir-bulk/fhir-resource" :batchId :fhirEntity]
              :method :get
              :expected-status [200]
              :base-headers {"OfficeKey" :officekey}
              :description "Fetches a specific FHIR entity from a completed batch"}]
```

Usage:

```clj
(adapter/execute {:operation :fetch-resource
                  :params      {:batchId "batch-xyz" :fhirEntity "Patient" :officekey "991900"}
                  :query-params {}})
;; Resolves to: GET /v1/fhir-bulk/fhir-resource/batch-xyz/Patient
```

#### Status Check with Optional Params

```clj
:operations [{:name :check-status
              :path ["/v1/fhir-bulk/status"]
              :method :get
              :expected-status [200 202]
              :base-headers {"OfficeKey" :officekey}
              :description "Checks the status of a bulk export job"}]
```

---

### Minimal Valid Adapter (just enough to start)

```clj
{:domain :custom/single-instance
 :base-url "https://fhir.test-server.com"
 :auth {:grant-type :client-secret
        :client-id "test-client"
        :client-secret "test-secret"
        :scopes ["user/*.*"]}
 :http-client-fn clj-http.client/request
 :middlewares [ehr-adapter.middleware/clj-http]
 :policies {}
 :operations []}
```

---

## Resolution Rules

### Path Resolution

- `:path` is a vector of segments: strings are kept literal, keywords are resolved from `:params`.
- Segments are joined with `/`. Example: `["/api" :resource :id]` + `{:resource "Patient" :id "123"}` → `"/api/Patient/123"`.
- If a keyword in `:path` is not found in `:params`, an `ex-info` is thrown immediately (fail-fast).
- Values are **not URL-encoded** by default (can be added later if needed).

### Header Resolution

- `:base-headers` values can be keywords. If so, they are resolved from `:params` using the same logic as `:path`.
- Example: `{"OfficeKey" :officekey}` + `{:officekey "991900"}` → `{"OfficeKey" "991900"}`.
- Missing keyword → `ex-info` with context `{:missing :officekey :available (keys params)}`.

### Query Params

- `:query-params` in `:execute` are serialized separately and appended to the URL as a query string.
- Keys used for path/header interpolation are **not** automatically added to query params. Explicit is better than implicit.

### Error Handling

- Missing interpolation keyword → `(ex-info "Missing interpolation param" {:missing :groupId :available [:officekey]})`.
- HTTP errors → normalized and re-thrown with context (`:domain`, `:status`, `:retry-count`, `:cause`, `:raw-response`).
- Network/parse errors → `(ex-info "HTTP client error" {:type :network :retryable? true})`.

```clj
[:map
 [:type [:enum :basic-auth :smart-on-fhir/backend-services :oauth2 :api-key]] ;;en dependencia de el type el flujo sería diferente
 [:bindings {:optional true}
   [:map-of :keyword [:vector [:or :keyword :string]]]]]
```
