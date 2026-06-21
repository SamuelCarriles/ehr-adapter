<p align="center">
  <a href="https://clojars.org/io.github.samuelcarriles/ehr-adapter">
    <img src="https://img.shields.io/clojars/v/io.github.samuelcarriles/ehr-adapter.svg" alt="Clojars Project">
  </a>
  <img src="https://img.shields.io/badge/clj-1.12+-%2391DE51?logo=clojure&logoColor=white" alt="Clojure Version">
  <img src="https://github.com/SamuelCarriles/ehr-adapter/actions/workflows/tests.yml/badge.svg" alt="Tests Status">
  <a href="https://deepwiki.com/SamuelCarriles/ehr-adapter"><img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki"></a>
</p>

# ehr-adapter

A Clojure library for declarative, data-driven EHR integrations.

## Installation

Add the following dependency to your `deps.edn`:

```clojure
io.github.samuelcarriles/ehr-adapter {:mvn/version "2.0.1"}
```

Or to your `project.clj`:

```clojure
[io.github.samuelcarriles/ehr-adapter "2.0.1"]
```

## Core Concepts

1. **Data-Driven Architecture**: Define your entire adapter using Clojure maps. No classes, no hidden state.
2. **Partial Resolution**: Use `:ref/` keywords to create reusable templates. Static values resolve at initialization; dynamic values resolve at runtime.
3. **Thread-Safe Auth**: Built-in proactive token refresh with Leader/Follower concurrency pattern.
4. **Strict Validation**: Runtime validation via Malli ensures your configuration is correct before execution.
5. **Extensible**: Add custom authentication strategies or middlewares using Clojure's multimethods.

## Quick Start

This is how explicit, robust, and readable an adapter configuration looks using **Babashka's HTTP client** as the core transportation layer:

```clojure
(require '[ehr-adapter.core :as ehr]
         '[babashka.http-client :as http]
         '[ehr-adapter.middleware.bb-http-client :as bb-http-client]
         '[ehr-adapter.middleware.jsonista :as jsonista])

(def eclinicalworks-adapter-config
  {:domain :eclinicalworks/tenant-beta

   :base-url "https://staging-fhir.ecw.com/fhir/r4"

   :middlewares [bb-http-client/wrap
                 jsonista/wrap]

   :network-config {:request-handler http/request}

   :auth {:initial [{:type :oauth2
                     :token-url "https://staging-fhir.ecw.com/fhir/oauth2/token"
                     :grant-type "client_credentials"
                     :client-id "prod-client-id-xyz"
                     :scopes ["patient/*.read"]
                     :client-secret "super-secure-secret"}

                    {:type :normalize
                     :token [:body :access_token]
                     :token-type [:body :token_type]
                     :expires-in [:body :expires_in]}]}

   :operations [{:name :get-metadata
                 :method :get
                 :path "metadata"
                 :auth? false
                 :description "Retrieve the server's FHIR CapabilityStatement"}

                {:name :get-patient-by-id
                 :method :get
                 :path ["Patient" :ref/patientId]
                 :description "Retrieve a patient by their unique identifier."}]})

;; Initialize the adapter
(def adapter (ehr/initialize eclinicalworks-adapter-config))

;; Invoke metadata (no auth required)
(ehr/invoke adapter :get-metadata)
;; => {:status 200, :body {:resourceType "CapabilityStatement", ...}}

;; Invoke patient operation (auth required, token automatically managed)
(ehr/invoke adapter :get-patient-by-id {:patientId "12345"})
;; => {:status 200, :body {:resourceType "Patient", ...}}
```

## Complete Configuration Guide

To understand the exact structure of the configuration map, the data types supported by the validator, the behavior of the network resiliency engine, and the exhaustive breakdown of each authentication strategy (including advanced usage of `:bindings`), please check the official technical reference guide at: **[`Configuration Reference Guide`](https://github.com/SamuelCarriles/ehr-adapter/wiki/Adapter-Configuration-Guide)**

## License

Copyright © 2026 Samuel Carriles

Distributed under the MIT License.
