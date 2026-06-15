(ns ehr-adapter.auth.core
  (:require [ehr-adapter.auth.strategy :as strategy]
            [ehr-adapter.reference :refer [resolve]]
            [ehr-adapter.time :refer [now]]
            [ehr-adapter.http.core :as http]
            [ehr-adapter.error :as error]))

(defn bindings
  "Resolves a map of dynamic paths against the given context map.
  
  Takes a context map (`ctx`) and a map of bindings (`paths`), where each key
  represents the target parameter name and each value is a vector path (suitable
  for `get-in`) pointing to the location of the real value within `ctx`.

  Returns a new map containing only the successfully resolved key-value pairs.
  If a path does not exist in the context, it is safely ignored.

  Example:
    (bindings {:tenant {:id \"123\"}} {:client-id [:tenant :id]})
    => {:client-id \"123\"}"
  [ctx paths]
  (reduce-kv

   (fn [acc b p]
     (let [extract (if (vector? p) get-in get)
           v (extract ctx p)]
       (if (some? v)
         (assoc acc b v)
         acc)))

   {} paths))

(defn full-context
  [ctx bind-paths]
  (cond-> ctx
    (seq bind-paths)
    (merge (bindings ctx bind-paths))))

(defn normalize
  [ctx layer]
  (let [ctx (full-context ctx (dissoc layer :type))
        token-data (select-keys ctx [:token :token-type :refresh-token])

        expires-in (some-> ctx :expires-in str parse-long)]
    (cond-> token-data
      expires-in
      (assoc :expires-at (+ (now) expires-in)))))

(defn process-layer
  "Prepares the authentication layer by resolving its dynamic bindings 
  against the context, merging the results, and then dispatches to the 
  corresponding authentication strategy execution."
  [ctx layer request-handler]
  (let [layer-type (:type layer)
        full-ctx (full-context ctx (:bindings layer))
        ready-layer (resolve full-ctx (dissoc layer :bindings))]
    (if (= :normalize layer-type)
      (normalize full-ctx ready-layer)

      (let [result (strategy/execute ready-layer request-handler)
            status (:status result)]

        (cond

          (or (not status) (http/success? result))
          result

          :else
          (throw (error/info
                  :http/failure
                  {:message (format "Authentication request failed for layer %s" layer-type)
                   :scope :ehr-adapter.auth.core
                   :operation :authentication
                   :status status
                   :error-body (:body result)})))))))

(defn run
  "Sequentially executes a pipeline of authentication layers.

  Applies a `reduce` over `auth-layers`, passing the context map through
  each layer along with the `http-client`. The result of processing a layer
  becomes the input context for the next one.

  Returns the final, fully transformed context map."
  [initial-ctx auth-layers request-handler]
  (reduce
   (fn [ctx layer]
     (process-layer ctx layer request-handler))
   initial-ctx
   auth-layers))

(defn token-expired?
  "Evaluates whether an authentication token has expired or is within a proactive refresh window.
   Supports two arities: the first uses a strict check (0-second buffer), while the second allows
   specifying a buffer in seconds to trigger a refresh before the exact expiration time.
   If `:expires-at` is missing or nil, returns false to prevent unnecessary refresh attempts.

   Args:
   - state     : Map containing the `:expires-at` timestamp.
   - buff-secs : (Optional) Number of seconds before expiration to consider the token expired.

   Returns:
   Boolean indicating if the token should be treated as expired."
  ([state] (token-expired? state 0))
  ([{:keys [expires-at]} buff-secs]
   (and (some? expires-at)
        (>= (now) (- expires-at buff-secs)))))

(defn refresh
  "Creates a compiled, context-aware function for executing the token refresh flow.
   This higher-order function closes over the authentication layers and the HTTP request handler,
   returning a single-argument function that executes the full refresh pipeline when invoked.

   Args:
   - auth-layers     : Vector of authentication strategy configurations to execute during refresh.
   - request-handler : The wrapped HTTP client function used to perform the network request.

   Returns:
   A compiled function `(fn [ctx] ...)` that takes the current auth state context and returns
   the newly refreshed auth state map."
  [auth-layers request-handler]
  (fn [ctx]
    (run ctx auth-layers request-handler)))

(defn ensure-token!
  "Thread-safe coordinator that guarantees the authentication state atom contains a valid token.
   Implements the Leader/Follower concurrency pattern: if the token is expired or within the 
   proactive refresh buffer, one thread claims leadership to perform the refresh while others 
   wait on a promise. On success, updates the atom and wakes waiting threads. On failure, 
   restores the previous state to allow future retries and propagates the exception.

   Args:
   - auth-data   : Map containing `:state` (the auth atom) and `:refresh-fn` (the compiled refresh function).
   - buffer-secs : Seconds of anticipation to trigger a proactive refresh.

   Returns:
   The original `auth-data` map (for pipeline compatibility). 
   Note: Mutates `:state` atom in-place. Throws on refresh failure."
  [{:keys [state refresh-fn] :as auth-data} buffer-secs]
  (when auth-data
    (let [current @state]
      (cond

        (instance? clojure.lang.IPending current)
        (let [result @current]
          (if (instance? Throwable result)
            (throw result)
            auth-data))

        (token-expired? current buffer-secs)
        (let [new-promise (promise)]
          (if (compare-and-set! state current new-promise)
            (try
              (let [new-state (refresh-fn current)]
                (deliver new-promise new-state)
                (reset! state new-state)
                auth-data)
              (catch Throwable e
                (deliver new-promise e)
                (reset! state current)
                (throw e)))

            (recur auth-data buffer-secs)))

        :else auth-data))))



