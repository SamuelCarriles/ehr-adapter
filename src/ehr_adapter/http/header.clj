(ns ehr-adapter.http.header
  (:require [ehr-adapter.error :as error]
            [ehr-adapter.schema :as schema]
            [clojure.string :as str]
            [clojure.set :refer [map-invert]]))

(def keyword-format->mime
  {:json "application/json"
   :fhir/json "application/fhir+json"
   :form-url-encoded "application/x-www-form-urlencoded"
   :xml "application/xml"
   :text "text/plain"
   :multipart "multipart/form-data"})

(def mime->keyword-format
  (map-invert keyword-format->mime))

(defn parse-mime
  "Parses a raw HTTP Content-Type header string into the internal structured
   map representation of the engine.

   Always returns a map with a :code key, and optionally a :properties map
   if parameters are present in the header or if the media type is unsupported.

   The returned map contains:
   - :code        The unassigned format keyword, or :unsupported if not registered.
   - :properties  An optional map of string key-value pairs representing parameters
                  (e.g., charset, boundary). If the format is :unsupported, it 
                  includes a :raw-mime key containing the original parsed media type.

   Examples:
     (parse-mime \"application/json\")
     => {:code :json}

     (parse-mime \"application/fhir+json; charset=utf-8\")
     => {:code :fhir/json, :properties {\"charset\" \"utf-8\"}}

     (parse-mime \"application/x-custom-type; boundary=123\")
     => {:code :unsupported, :raw-mime \"application/x-custom-type\" :properties {\"boundary\" \"123\"}}"
  [^String s]
  (let [[mime & props] (-> s str/trim (str/split #";"))
        properties (map #(-> (str/trim %) (str/split #"=" 2)) props)
        code (if-let [c (get mime->keyword-format mime)]
               c :unsupported)]
    (cond-> {:code code}
      props (assoc :properties (into {} properties))
      (= :unsupported code)
      (assoc :raw-mime mime))))

(defn mime-error [x]
  (error/info :unsupported/mime-code
              {:message "Unsupported mime-code. Try to use a map to define unsupported types. Example: {:code :unsupported :raw-mime \"<my-mime-code>\"}"
               :scope :ehr-adapter.middleware.header
               :operation :resolve-header
               :mime-code x
               :expected (into [:unsupported] (keys keyword-format->mime))}))

(defn ->mime
  [x]
  (cond
    (keyword? x)
    (if-let [mime (get keyword-format->mime x)]
      mime
      (throw (mime-error x)))

    (map? x)
    (let [{:keys [code raw-mime properties]} (schema/validate-mime-code-map x)
          unsupported? (= :unsupported code)
          mime (if unsupported? raw-mime (->mime code))
          full-mime-vec (reduce-kv (fn [acc k v] (conj acc (str k "=" v))) [mime] properties)]
      (str/join "; " full-mime-vec))

    :else
    (throw (error/info :invalid/format
                       {:message "Invalid mime-code format"
                        :scope :ehr-adapter.http.header
                        :operation :resolve-header
                        :value x
                        :expected [:or :keyword [:map
                                                 [:code :keyword]
                                                 [:raw-mime {:optional true} :string]
                                                 [:properties {:optional true}
                                                  [:map-of :string :string]]]]}))))

(defn content-type
  "Given a format keyword or Content-Type-Structured map, returns {\"Content-Type\" <mime-type>} or throws if the format is not supported."
  [x]
  (when-not (nil? x)
    (if-let [mime (->mime x)]
      {"Content-Type" mime}
      (throw (mime-error x)))))

(defn accept
  "Given a format keyword or Content-Type-Structured map, returns {\"Accept\" <mime-type>} or throws if the format is not supported."
  [x]
  (when-not (nil? x)
    (if-let [mime (->mime x)]
      {"Accept" mime}
      (throw (mime-error x)))))

(defn json-media-type?
  "Returns true if the given content-type value represents a JSON media type.
 Accepts either a keyword (e.g. :json, :fhir/json) or a structured map with a :code key."
  [content-type]
  (let [code (if (map? content-type) (:code content-type) content-type)]
    (or (= :json code)
        (= :fhir/json code))))

(defn authorization
  [token token-type]
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