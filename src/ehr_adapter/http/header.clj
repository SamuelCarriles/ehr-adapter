(ns ehr-adapter.http.header
  (:require [ehr-adapter.error :as error]
            [ehr-adapter.schema :as schema]
            [clojure.string :as str]
            [clojure.set :refer [map-invert]]))

(def keyword-format->mime
  {:json "application/json"
   :json/fhir "application/json+fhir"
   :fhir/json "application/fhir+json" ;; for old FHIR versions like DSTU2
   :form-url-encoded "application/x-www-form-urlencoded"
   :xml "application/xml"
   :text "text/plain"
   :multipart "multipart/form-data"})

(def json-media-types #{:json :json/fhir :fhir/json})

(def mime->keyword-format
  (map-invert keyword-format->mime))

(defn parse-mime
  "Parses a raw HTTP Content-Type header string into the internal structured
   map representation of the engine.

   Returns a map containing a :code key and, when present, a :properties map
   with the parameters specified in the Content-Type header.

   If the media type is not registered, :code is set to :unsupported and
   :raw-mime contains the original media type.

   The one-argument arity uses the default MIME code mappings. The two-argument
   arity accepts a map of additional MIME codes, where keys are format keywords
   and values are MIME type strings. Custom MIME codes take precedence over
   the default mappings.

   Examples:
     (parse-mime \"application/json\")
     => {:code :json}

     (parse-mime \"application/fhir+json; charset=utf-8\")
     => {:code :fhir/json
         :properties {\"charset\" \"utf-8\"}}

     (parse-mime \"application/x-custom-type; boundary=123\")
     => {:code :unsupported
         :raw-mime \"application/x-custom-type\"
         :properties {\"boundary\" \"123\"}}

     (parse-mime \"application/json+fhir\"
                 {:json/fhir \"application/json+fhir\"})
     => {:code :json/fhir}"
  ([^String s]
   (parse-mime s {}))
  ([^String s mime-codes]
   (let [[mime & props] (-> s str/trim (str/split #";"))
         properties (map #(-> (str/trim %) (str/split #"=" 2)) props)
         mime-codes (merge mime->keyword-format (map-invert mime-codes))
         code (get mime-codes mime :unsupported)]
     (cond-> {:code code}
       props (assoc :properties (into {} properties))
       (= :unsupported code)
       (assoc :raw-mime mime)))))

(defn mime-error [x]
  (error/info :unsupported/mime-code
              {:message "Unsupported MIME code. Add :mime-codes on :network in the AdapterConfiguration to add ohter MIME codes, or try to use a map to define unsupported types. Example: {:code :unsupported :raw-mime \"<my-mime-code>\"}"
               :scope :ehr-adapter.middleware.header
               :operation :resolve-header
               :mime-code x
               :expected (into [:unsupported] (keys keyword-format->mime))}))

(defn ->mime
  ([x]
   (->mime x {}))
  ([x mime-codes]
   (cond
     (keyword? x)
     (if-let [mime (get (merge keyword-format->mime mime-codes) x)]
       mime
       (throw (mime-error x)))

     (map? x)
     (let [{:keys [code raw-mime properties]} (schema/validate-mime-code-map x)
           unsupported? (= :unsupported code)
           mime (if unsupported? raw-mime (->mime code mime-codes))
           full-mime-vec (reduce-kv (fn [acc k v] (conj acc (str k "=" v))) [mime] properties)]
       (str/join "; " full-mime-vec))

     :else
     (throw (error/info :invalid/format
                        {:message "Invalid MIME code format"
                         :scope :ehr-adapter.http.header
                         :operation :resolve-header
                         :value x
                         :expected [:or :keyword [:map
                                                  [:code :keyword]
                                                  [:raw-mime {:optional true} :string]
                                                  [:properties {:optional true}
                                                   [:map-of :string :string]]]]})))))

(defn content-type
  "Returns a Content-Type header map for the given format.

   Accepts either a format keyword or a Content-Type-Structured map.
   The one-argument arity uses the default MIME code mappings. The two-argument
   arity accepts a map of additional MIME codes.

   Returns nil when x is nil.

   Examples:
     (content-type :json)
     => {\"Content-Type\" \"application/json\"}

     (content-type :json/fhir
                   {:json/fhir \"application/json+fhir\"})
     => {\"Content-Type\" \"application/json+fhir\"}

     (content-type nil)
     => nil"
  ([x]
   (content-type x {}))
  ([x mime-codes]
   (when-not (nil? x)
     (if-let [mime (->mime x mime-codes)]
       {"Content-Type" mime}
       (throw (mime-error x))))))

(defn accept
  "Returns an Accept header map for the given format.

   Accepts either a format keyword or a Content-Type-Structured map.
   The one-argument arity uses the default MIME code mappings. The two-argument
   arity accepts a map of additional MIME codes.

   Returns nil when x is nil.

   Examples:
     (accept :json)
     => {\"Accept\" \"application/json\"}

     (accept :fhir/json)
     => {\"Accept\" \"application/fhir+json\"}

     (accept nil)
     => nil"
  ([x]
   (accept x {}))
  ([x mime-codes]
   (when-not (nil? x)
     (if-let [mime (->mime x mime-codes)]
       {"Accept" mime}
       (throw (mime-error x))))))

;; TODO: Revisar que la implementación está correcta y cambiar el doc string
(defn json-media-type?
  "Returns true if the given value represents a JSON media type.

   Accepts either a format keyword or a Content-Type-Structured map with a
   :code key.

   The one-argument arity checks against the default JSON media types.
   The two-argument arity accepts an options map. Additional JSON media types
   can be specified with :includes. The default JSON media types are always
   included.

   Examples:
     (json-media-type? :json)
     => true

     (json-media-type? :fhir/json)
     => true

     (json-media-type? :xml)
     => false

     (json-media-type? {:code :json})
     => true

     (json-media-type? :json+fhir
                       {:includes #{:json+fhir}})
     => true

     (json-media-type? :json+fhir)
     => false"
  ([value]
   (json-media-type? value {}))
  ([value opts]
   (let [extra (:includes opts)
         media-types (into extra json-media-types)
         code (if (map? value) (:code value) value)]
     (contains? media-types code))))

(defn authorization
  [{:keys [token token-type]}]
  (when-not (and token token-type)
    (let [missing-field (cond-> []
                          (not token) (conj :token)
                          (not token-type) (conj :token-type))]
      (throw (error/info :missing/field
                         {:message "The field :token, :token-type or both are missing"
                          :scope :ehr-adapter.http.header
                          :operation :resolve-header
                          :field missing-field}))))

  {"Authorization" (format "%s %s" token-type token)})
