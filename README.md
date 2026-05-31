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

In `ehr-adapter`, **everything is a Clojure map**. There are no rigid classes, complex objects, or hidden configurations. The entire adapter infrastructure—from how you log in to the definition of clinical endpoints—is declared using native, transparent, and easy-to-audit data structures.

### 2. Inverted & Decoupled Flow (`:bindings`)

Instead of building complex middlewares that blindly "push" data from one function to another, the library implements a declarative **inverted resolution (_pull_)** pattern. The layers that need information explicitly declare what data they need to extract from the global context using a `:bindings` map. This keeps the library core 100% decoupled and ready to tame complex or hybrid authentication pipelines.

### 3. Dynamic Endpoint Compilation

You define pure data templates for your operations (e.g., `["v1/Patient" :ref/patientId]`), and the `ehr-adapter` engine compiles them on the fly into safe, highly efficient Clojure functions that are rigorously validated at runtime.

## Quick Glance

This is how explicit and readable an adapter configuration looks in your system:

```clojure
(require '[ehr-adapter.core :as ehr])

;; 1. Define the declarative configuration for your tenant
(def eclinicalworks-adapter-config
  {:domain :eclinicalworks/tenant-beta
   :base-url "https://api.interop-ehr.com/v2"
   :http-client-fn clj-http.client/request
   :middlewares [ehr-adapter.middleware/clj-http-client]

   ;; Sequential authentication pipeline
   :auth [{:type          :oauth2
           :token-url     "https://auth.interop-ehr.com/v2/oauth2/token"
           :grant-type    "client_credentials"
           :client-id     "prod-client-id-xyz"
           :client-secret "super-secure-secret"}]

   ;; Operations that will be compiled into executable functions
   :operations [{:name :search-patient
                 :method :get
                 :path ["v1/Patient" :ref/patientId]
                 :description "Search for a patient using their unique identifier."}]})

;; 2. Initialize the engine
(def client (ehr/initialize eclinicalworks-adapter-config))

;; 3. Invoke the operation cleanly within your business logic
(ehr/invoke client :search-patient {:patientId "12345"})

```

## Flexible Configuration Storage

The adapter configuration is designed to be fully versatile. It can be declared directly inline within your application code as a native Clojure map, or decoupled entirely by storing it inside an external `ehr_adapters.edn` file (or any other specified file) for cleaner environment management and structural isolation.

## Complete Configuration Guide

To understand the exact structure of the configuration map, the data types supported by the validator, the behavior of the network resiliency engine, and the exhaustive breakdown of each authentication strategy (including advanced usage of `:bindings`), please check the official technical reference guide at: **[`Configuration Reference Guide`](https://github.com/SamuelCarriles/ehr-adapter/wiki/Adapter-Configuration-Guide)**

## 📋 Project Status

Currently, `ehr-adapter` is under active development. The current focus is locking down the validation engine using Malli and robustly stabilizing the sequential authentication pipeline to fully support flows like `:api-key`, `:basic-auth`, `:oauth2`, and `:smart-on-fhir/backend-services`.
