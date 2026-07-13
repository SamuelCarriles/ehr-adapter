(ns ehr-adapter.core
  (:require
   [ehr-adapter.schema :as schema]
   [ehr-adapter.auth.core :as auth-core]
   [ehr-adapter.network :as net]
   [ehr-adapter.http.header :refer [authorization]]
   [ehr-adapter.operation :as op]
   [ehr-adapter.reference.core :refer [partial-resolve]]
   [ehr-adapter.error :as error]))

(defn wrap-handler
  [{:keys [request-handler middlewares]}]
  (let [wrapper (apply comp (reverse middlewares))]
    (wrapper request-handler)))

(defn initialize
  "Builds and validates an adapter instance ready for use from a configuration map.
  
  This process:
  1. Validates the input configuration against the `AdapterConfiguration` schema.
  2. Wraps the `request-handler` with middlewares and network retry logic.
  3. Compiles all defined operations for fast execution.
  4. If authentication is configured, initializes the token state in a thread-safe atom.
  
  Args:
  - ctx: (Optional) Initial context map to resolve references during setup.
  - adapter-config: Configuration map conforming to the adapter schema.
  
  Returns:
  A map validated as `AdapterInstance`, ready to be used with `invoke`."
  ([adapter-config] (initialize {} adapter-config))
  ([ctx adapter-config]
   (let [partial-resolved-cfg (->> adapter-config (partial-resolve ctx) schema/validate-adapter-config)
         {:keys [domain base-url auth network operations]} partial-resolved-cfg
         wrapped-handler (-> (wrap-handler network)
                             (net/with-retries network)
                             (net/with-client network))

         auth-initial-layers (:initial auth)

         auth-refresh-layer (or (:refresh auth) auth-initial-layers)

         auth-state (atom (auth-core/run ctx auth-initial-layers wrapped-handler))

         instance (cond-> {:ehr-adapter/domain domain
                           :ehr-adapter/base-url base-url
                           :ehr-adapter/request-handler wrapped-handler}

                    auth
                    (assoc :ehr-adapter/auth {:state auth-state
                                              :refresh-fn (auth-core/refresh auth-refresh-layer wrapped-handler)})

                    operations
                    (assoc :ehr-adapter/operations (->> (op/flatten-operations operations)
                                                        (map op/compile)
                                                        (apply merge))))]

     (schema/validate-adapter-instance instance))))

(defn- runtime-context
  "Prepares the runtime execution context by ensuring a valid authentication token
   and safely injecting authorization headers.

   If authentication is configured, it triggers a proactive token refresh if needed
   and merges the resulting auth headers into the user-provided context, preserving
   any existing custom headers. If no auth is configured, it returns the context unchanged.

   Args:
   - adapter-instance: The initialized adapter instance.
   - ctx: The user-provided runtime context map.

   Returns:
   The enriched context map ready to be passed to the operation handler."
  [adapter-instance ctx auth?]
  (let [auth-data (when auth? (auth-core/ensure-token! (:ehr-adapter/auth adapter-instance) 60))
        base-url (:ehr-adapter/base-url adapter-instance)]

    (cond-> (assoc ctx  :ehr-adapter/base-url base-url)

      auth-data
      (update-in [:request :headers] (fnil merge {}) (authorization @(:state auth-data))))))

(defn invoke
  "Executes a precompiled operation within an adapter instance.
  
  Before executing the operation, this function:
  1. Applies :in transformers to the user-provided context (if defined).
  2. Automatically ensures the authentication token is valid (proactive refresh if needed).
  3. Safely injects authorization headers into the request, preserving any custom headers provided by the user in the context.
  4. Executes the operation handler.
  5. Applies :out transformers to the HTTP response (if defined).
  
  Args:
  - adapter-instance: The adapter instance created previously with `initialize`.
  - operation-key: The keyword identifying the operation to execute.
  - ctx: (Optional) Map with dynamic bindings and request overrides for this specific execution.
  
  Returns:
  The resulting HTTP response map from the operation execution, after :out transformers have been applied.
  
  Throws:
  - `:unsupported/invoked-operation` if the operation key does not exist in the instance."
  ([adapter-instance operation-key] (invoke adapter-instance operation-key {}))
  ([adapter-instance operation-key ctx]

   (let [operation-data (get-in adapter-instance [:ehr-adapter/operations operation-key])
         {:keys [handler auth? transformers]} operation-data
         {in-tr :in out-tr :out} transformers
         req-handler (:ehr-adapter/request-handler adapter-instance)
         ctx (if in-tr (in-tr ctx) ctx)
         ready-ctx (runtime-context adapter-instance ctx auth?)]
     (if (some? handler)
       (let [result (handler ready-ctx req-handler)]
         (if out-tr
           (out-tr result)
           result))
       (throw (error/info :unsupported/invoked-operation
                          {:message (format "Unknown operation %s" operation-key)
                           :scope :ehr-adapter.core
                           :operation :invoke
                           :value operation-key
                           :expected (keys (:ehr-adapter/operations adapter-instance))}))))))


