# Configuration Reference Guide: `ehr-adapter`

This guide details how to compose the configuration map required to initialize an EHR provider adapter. In `ehr-adapter`, everything —from authentication mechanics to endpoint paths— is defined using native Clojure data structures.

## The Root Configuration Map

The configuration is a single Clojure map containing the core infrastructure definitions, the sequential authentication layers, resiliency/network policies, and the target endpoints.

### Fields Matrix

| Key               | Type       | Required | Description & Live Behavior                                                                                                                                                                                                      | Example                                                                   |
| :---------------- | :--------- | :------: | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :------------------------------------------------------------------------ |
| `:domain`         | `Keyword`  | **Yes**  | A qualified keyword used to uniquely identify the tenant (clinic/organization) and route traffic cleanly.                                                                                                                        | `:advancedmd/test-sandbox`                                                |
| `:base-url`       | `String`   | **Yes**  | The root URL of the target EHR provider API server. Used globally to prefix relative operation paths.                                                                                                                            | `"https://providerapi.advancedmd.com"`                                    |
| `:http-client-fn` | `Function` | **Yes**  | The underlying Clojure HTTP function (e.g., using `clj-http` or `http-kit`) that will execute the actual request.                                                                                                                | `clj-http.client/request`                                                 |
| `:middlewares`    | `Vector`   | **Yes**  | A vector of functions composing the processing pipeline. Must contain at least one base client adapter middleware (`ehr-adapter.middleware/clj-http-client`) to decouple core data structures from physical network invocations. | `[ehr-adapter.middleware/clj-http-client]`                                |
| `:auth`           | `Vector`   | **Yes**  | A vector of authentication layer maps executed sequentially. It acts as an isolation pipeline where layers can pass dynamic variables forward.                                                                                   | _(See [Authentication Section](#1-authentication-layers-auth))_           |
| `:network-config` | `Map`      |    No    | Performance and resilience settings such as timeouts, connection behavior, and retry rules.                                                                                                                                      | _(See [Network Config Section](#2-network-configuration-network-config))_ |
| `:operations`     | `Vector`   |    No    | A list of available endpoint templates to be compiled into executable higher-order functions by the engine.                                                                                                                      | _(See [Operations Section](#3-endpoint-operations-operations))_           |

## 1. Authentication Layers (`:auth`)

Because EHR authentication often requires multi-step procedures, `:auth` accepts a **sequential vector of maps**. This design operates like a pure data pipeline, allowing intermediate payloads, tokens, or custom data adjustments to feed forward.

### Dynamic Resolution Contract (`:ref/...`)

Whenever a field within an auth layer or payload requires data generated at runtime (or from preceding layers), it utilizes a **namespaced keyword** following the strict format `:ref/variable-name`.
The engine resolves these variables using a fail-fast strategy in this precise lookup priority order:

1. **Context Lookup:** Searches for the exact match in the current execution registry context.
2. **Explicit Bindings Fallback:** If not found directly, it evaluates the `:bindings` dictionary rule mapped against the upstream layer's output map.
3. **Execution Block:** If the reference remains unresolved, the engine immediately throws an `ex-info` exception to prevent corrupted downstream requests.

### Layer Interleaving for Data Transformation

Beyond standard protocol strategies, developers can insert a layer of type `:custom` between any two authentication stages. The sole responsibility of this middleware layer can be transforming, nesting, or flattening the map output from a previous request to ensure it fits the exact structural contract required by the following layer.

### Auth Types & Examples

#### Strategy: `:api-key`

Used for simple static tokens, application keys, or custom header values.

```clojure
{:type      :api-key
 :api-key   "ewc-prod-38910x-key"
 :client-id "my-application-id"}

```

#### Strategy: `:basic-auth`

Standard Username/Password exchanges to obtain short-lived access elements.

```clojure
{:type      :basic-auth
 :token-url "[https://api.provider.com/oauth2/token](https://api.provider.com/oauth2/token)"
 :username  "integrator_app"
 :password  "SecurePassword987!"
 :payload   {:grant_type "client_credentials"}}

```

#### Strategy: `:oauth2`

Standard OAuth2 flow for application-to-application server communication, as well as assertions using asymmetric JWT cryptography or proprietary parameters via custom structural payloads.

```clojure
{:type          :oauth2
 :token-url     "[https://providerapi.advancedmd.com/v1/oauth2/token](https://providerapi.advancedmd.com/v1/oauth2/token)"
 :grant-type    "client_credentials"
 :payload       {:client_assertion      :ref/jwt
                 :client_assertion_type "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
                 :scope                 "system/*.read"
                 :username              "AMDTESTER"
                 :officekey             990191}}

```

#### Strategy: `:custom`

Utilized for legacy, non-standard, or specialized proxy-transformation workflows within the auth sequential pipeline.

```clojure
{:type    :custom
 :handler my-app.auth.transformer/restructure-json-payload
 :data    {:target-node [:response :auth_context :token]}}

```

## 2. Network Configuration (`:network-config`)

Controls socket boundaries, network threshold limits, and failure fallback loops for the http client adapter.

> **Co-dependency Rule:** If you configure `:retries`, you **must** configure `:retry-delay-ms` as well. Setting retry cycles without an explicit wait delay threshold is an invalid configuration state.

| Key                 | Type      | Description                                                                           | Example / Allowed Values    |
| ------------------- | --------- | ------------------------------------------------------------------------------------- | --------------------------- |
| `:timeout-ms`       | `Integer` | Maximum network wait threshold in milliseconds before aborting a request.             | `5000`                      |
| `:retries`          | `Integer` | Maximum number of execution retry attempts upon network-level failure.                | `3`                         |
| `:retry-delay-ms`   | `Integer` | Base delay interval wait time (in milliseconds) between execution retries.            | `200`                       |
| `:retry-on`         | `Vector`  | Targeted list of HTTP status codes that automatically trigger a retry cycle.          | `[500 502 503 504]`         |
| `:retry-strategy`   | `Keyword` | Algorithmic time backoff approach between execution cycles.                           | `:linear` or `:exponential` |
| `:refresh-token-on` | `Vector`  | HTTP status codes that invalidate token caches and rerun the entire `:auth` pipeline. | `[401]`                     |

## 3. Endpoint Operations (`:operations`)

Defines the relative endpoint matrices that the compiler engine maps and transforms into fully executable, validated, higher-order Clojure functions.

- **Relative Isolation Rule:** Paths defined within operations **must not contain the full domain URL**. The compiler engine automatically aggregates segments by resolving them against the root `:base-url` via a safe `str/join` matrix, neutralizing anomalies from leading/trailing slashes.

| Key             | Type      | Description & Live Engine Behavior                                                                                                                                                                                                                          | Example                                   |
| --------------- | --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------- |
| `:name`         | `Keyword` | Unique identifier used by the engine to register, map, and invoke the compiled function.                                                                                                                                                                    | `:$export`                                |
| `:method`       | `Keyword` | Native HTTP verb/method utilized to transmit the operation over the wire.                                                                                                                                                                                   | `:get`, `:post`, `:patch`, `:delete`      |
| `:path`         | `Vector`  | Paths parsed by the compiler engine. Literals evaluate as exact matches. Namespaced keywords (e.g., `:ref/group-id`) act as mandatory variable dependencies extracted from the invocation `opts` map. Missing references trigger immediate `ex-info` halts. | `["v1/r4/Group" :ref/group-id "$export"]` |
| `:base-headers` | `Map`     | Fixed headers injected specifically for this operational endpoint. Can accept `:ref/...` lookups to bind data resolved dynamically at runtime.                                                                                                              | `{"Prefer" "respond-async"}`              |
| `:description`  | `String`  | Human-readable documentation outlining parameters, usage contracts, or structural purposes.                                                                                                                                                                 | `"Initialize an $export FHIR operation."` |

## Complete Real-World Implementation (Standard Multi-Tenant Enterprise Layout)

The following example showcases a production-grade data adapter layout mapping a rigorous, standard dual-stage authentication pipeline used by modern interoperable EHR systems (e.g., eClinicalWorks or Epic traditional integration endpoints).

```clojure
{:domain :provider-group/tenant-alpha

 :base-url "https://api.interop-ehr.com/v2"

 :http-client-fn clj-http.client/request

 :middlewares [ehr-adapter.middleware/clj-http-client]

 :auth [{:type      :api-key
         :api-key   "app-gateway-secure-token-9901"
         :client-id "integrator-service-id"}

        {:type          :oauth2
         :token-url     "https://auth.interop-ehr.com/v2/oauth2/token"
         :grant-type    "client_credentials"
         :client-id     "client_usr_prod_01x"
         :client-secret "super-secret-oauth-string-xyz"
         :scope         "system/Group.read system/Patient.read"}]

 :network-config {:timeout-ms     5000
                  :retries        3
                  :retry-delay-ms 200}

 :operations [{:name         :$export
               :description  "Initialize an $export FHIR operation. Requires a map with :group-id."
               :method       :get
               :path         ["v1/r4/Group" :ref/group-id "$export"]
               :base-headers {"Prefer" "respond-async"}}]
 }
```
