<p align="center">
  <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License MIT">
  <img src="https://img.shields.io/badge/clj-1.12+-%2391DE51?logo=clojure&logoColor=white" alt="Clojure Version">
  <img src="https://github.com/SamuelCarriles/ehr-adapter/actions/workflows/tests.yml/badge.svg" alt="Tests Status">
</p>

# ehr-adapter

`ehr-adapter` is a Clojure library designed to solve the most frustrating problem in healthcare software development: integrating with multiple Electronic Health Record (EHR) providers.

## The Motivation

Connecting a backend service with an EHR provider (such as Epic, eClinicalWorks, AdvancedMD, etc.) is typically a tedious, inconsistent process riddled with "spaghetti code." Every provider implements security in their own way, utilizes hybrid or proprietary authentication flows, and structures their endpoint paths whimsically. Generic FHIR libraries often fail in production because they assume a perfect world where everyone follows official standards to the letter.

`ehr-adapter` was born with a clear goal: **Save engineering time**. We aim to transform a process prone to human error into a **simple, explicit, and declarative** task.

## Key Ideas & Philosophy

The library's architecture stands on three core pillars:

### 1. Data-Driven Architecture

In `ehr-adapter`, **everything is a Clojure map**. There are no rigid classes, complex objects, or hidden configurations. The entire adapter infrastructure—from how you log in to the definition of clinical endpoints—is declared using native, transparent, and easy-to-audit data structures validated strictly at runtime via Malli.

### 2. Inverted & Decoupled Flow (`:bindings`)

Instead of building complex hardcoded middlewares that blindly "push" data from one function to another, the library implements a declarative **inverted resolution (_pull_)** pattern. The layers that need information explicitly declare what data they need to extract from the global context using a `:bindings` map. This keeps the library core 100% decoupled and ready to tame complex or hybrid authentication pipelines.

### 3. Dynamic Endpoint Compilation

You define pure data templates for your operations (e.g., `["v1/Patient" :ref/patientId]`), and the `ehr-adapter` engine compiles them on the fly into safe, highly efficient Clojure functions that are rigorously validated against strict contracts.

## Quick Glance

This is how explicit, robust, and readable an adapter configuration looks using **Babashka's HTTP client** as the core transportation layer:

```clojure
(require '[ehr-adapter.core :as ehr]
         '[babashka.http-client :as http])

(def eclinicalworks-adapter-config
  {:domain :eclinicalworks/tenant-beta

   :base-url "[https://api.interop-ehr.com/v2](https://api.interop-ehr.com/v2)"

   :network-config {:request-handler babashka.http-client/request
                    :timeout-ms 5000
                    :retries 3
                    :retry-delay-ms 200
                    :retry-strategy :exponential
                    :retry-on [500 502 503 504]
                    :refresh-token-on [401]}

   :middlewares [ehr-adapter.middleware.bb-http-client/wrap-http-client]

   :auth [{:type          :oauth2
           :token-url     "https://auth.interop-ehr.com/v2/oauth2/token"
           :grant-type    "client_credentials"
           :client-id      "prod-client-id-xyz"
           :client-secret "super-secure-secret"}]

   ;; Operations that will be compiled into executable functions
   :operations [{:name :search-patient
                 :method :get
                 :path ["v1/Patient" :ref/patientId]
                 :expected-status [200 206]
                 :description "Search for a patient using their unique identifier."}]})

;; 2. Initialize the engine
(def client (ehr/initialize eclinicalworks-adapter-config))

;; 3. Invoke the operation cleanly within your business logic
(ehr/invoke client :search-patient {:patientId "12345"})

```

## Complete Configuration Guide

To understand the exact structure of the configuration map, the data types supported by the validator, the behavior of the network resiliency engine, and the exhaustive breakdown of each authentication strategy (including advanced usage of `:bindings`), please check the official technical reference guide at: **[`Configuration Reference Guide`](https://github.com/SamuelCarriles/ehr-adapter/wiki/Adapter-Configuration-Guide)**
