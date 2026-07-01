(ns ehr-adapter.io.core
  (:refer-clojure :exclude [import])
  (:require
   [clojure.string :as str]
   [clojure.pprint :refer [pprint]]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [ehr-adapter.io.transit :as transit]
   [ehr-adapter.error :as error]
   [ehr-adapter.schema :as schema])
  (:import
   [java.time Instant ZoneOffset]
   [java.time.format DateTimeFormatter]))

(defn path->ref
  "Builds a :ref keyword from a sequence of keys/indices representing
   a location inside the configuration map.
   Args:
   - path : A vector of keywords and/or integers (e.g. [:auth :initial 0 :handler]).
   Returns:
   A namespaced :ref/... keyword joining the path segments with dots,
   or nil if path is empty."
  [path]
  (when (seq path)
    (->> path
         (map #(if (keyword? %) (name %) %))
         (str/join ".")
         (keyword "ref"))))

(defn walk
  "Recursively walks a value, replacing any function found with the
   :ref keyword corresponding to its location in the structure.
   Args:
   - x    : The value to walk (map, vector, function, or scalar).
   - path : The path accumulated so far to reach x.
   Returns:
   x unchanged if it has no functions inside; a :ref keyword if x itself
   is a function; or a structurally-equal map/vector with every nested
   function replaced by its :ref."

  [x path]
  (cond
    (fn? x)
    (path->ref path)

    (map? x)
    (reduce-kv (fn [acc k v]
                 (assoc acc k (walk v (conj path k)))) {} x)

    (vector? x)
    (vec (map-indexed #(walk %2 (conj path %1)) x))

    :else
    x))

(defn ->serializable
  "Transforms an adapter configuration into a serializable form, replacing
   every function it contains with a :ref/... keyword the developer must
   resupply when reimporting the configuration.
   :middlewares is replaced as a single whole :ref (not walked into); :auth
   and :network-config are walked recursively so every nested handler gets
   its own indexed :ref.
   Args:
   - config : An adapter configuration map (as accepted by ehr-adapter.schema/AdapterConfiguration).
   Returns:
   A new configuration map with the same shape, safe to serialize to
   EDN or transit+json."
  [config]
  (cond-> config
    (:middlewares config)
    (assoc :middlewares :ref/middlewares)

    (:auth config)
    (update :auth walk [:auth])

    (:network-config config)
    (update :network-config walk [:network-config])))

(def ^:private supported-formats
  {:edn {:writer #(with-out-str (pprint %))
         :reader #(edn/read-string %)
         :file-extension ".edn"}

   :transit {:writer transit/->string
             :reader transit/<-string
             :file-extension ".json"}})

(defn format-error [fmt]
  (throw (error/info :invalid/format
                     {:message "Invalid IO format"
                      :scope :ehr-adapter.io.core
                      :operation :io-flow
                      :value fmt
                      :expected (vec (keys supported-formats))})))

(defn- ->file-name [domain fmt]
  (let [timestamp (.format (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
                           (.atZone (Instant/now) ZoneOffset/UTC))]

    (if-some [extension (get-in supported-formats [fmt :file-extension])]
      (format "%s.%s-%s%s" (namespace domain) (name domain) timestamp extension)
      (format-error fmt))))

(defn export!
  "Validates and serializes an adapter configuration, replacing every
   embedded function with a :ref/... keyword the developer must resupply
   when reimporting. Writes the result to disk if :dir and/or :file-name
   are given in opts; otherwise returns the serialized text in memory.
   Args:
   - config : An adapter configuration map (as accepted by ehr-adapter.schema/AdapterConfiguration).
   - opts   : {:format :edn|:transit, :dir (optional), :file-name (optional)}.
              When :dir/:file-name are omitted, nothing is written to disk.
   Returns:
   The serialized string (EDN or transit+json) when no destination is given,
   or the java.io.File written to disk otherwise.
   Throws:
   :invalid/format if opts :format has no registered writer."
  [config opts]
  (let [serializable-cfg (-> config schema/validate-adapter-config ->serializable)
        {fmt :format dir :dir filename :file-name} (schema/validate-export-opts opts)
        file-name (or filename (->file-name (:domain config) fmt))
        text (if-some [writer (get-in supported-formats [fmt :writer])]
               (writer serializable-cfg)
               (format-error fmt))]
    (if (or dir filename)
      (let [file (apply io/file (remove nil? [dir file-name]))]
        (when dir (.mkdirs (io/file dir)))
        (spit file text)
        file)
      text)))

(defn import
  "Reads a previously exported configuration file back into Clojure data,
   without resolving any :ref/... keyword (that's the caller's responsibility,
   typically when feeding the config to the adapter's initialize step).
   Args:
   - opts : {:format :edn|:transit, :file (a java.io.File)}, or
            {:format :edn|:transit, :dir (optional), :file-name}, where
            :file-name alone (no :dir) looks up the file in the current
            working directory.
   Returns:
   The Clojure data read from the file, as produced by ->serializable
   before it was written to disk.
   Throws:
   :missing/file if the resolved file doesn't exist;
   :invalid/format if opts :format has no registered reader."
  [opts]
  (let [{fmt :format dir :dir filename :file-name file :file} (schema/validate-import-opts opts)
        file (or file (apply io/file (remove nil? [dir filename])))]
    (when-not (.exists file)
      (throw (error/info :missing/file
                         {:message (format "The import was failed. The file %s doesn't exists" (.getName file))
                          :scope :ehr-adapter.io.core
                          :operation :io-flow
                          :file (.getName file)})))
    (if-some [reader (get-in supported-formats [fmt :reader])]
      (reader (slurp file))
      (format-error fmt))))

