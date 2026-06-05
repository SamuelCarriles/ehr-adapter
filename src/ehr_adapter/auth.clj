(ns ehr-adapter.auth
  (:import [java.util Base64]
           [java.nio.charset StandardCharsets]))

(defn ->base64
  [^String s]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes s StandardCharsets/UTF_8)))

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
     (if-let [v (get-in ctx p)]
       (assoc acc b v)
       acc))

   {} paths))

#_(defn basic-auth
    [context auth-layer http-client])

(defmulti process-auth (fn [{:keys [type]} _] type))

#_(defmethod process-auth :basic-auth
    [{:keys [username password token-url options]} http-client]
    (let [token (->base64 (str username ":" password))]
      (if-not token-url
        {:token token
         :token-type "Basic"}

        (http-client {:method :post
                      :url token-url
                      :headers (:headers payload)
                      :body (:body payload)
                      :query (:query payload)}))))
