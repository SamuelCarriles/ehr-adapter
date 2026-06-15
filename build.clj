(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib  'io.github.samuelcarriles/ehr-adapter)

(def version "1.0.0")

(def class-dir "target/classes")

(def jar-file (format "target/%s-%s.jar" (name lib) version))

(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :pom-data [[:description "Simplify EHR integrations by defining and validating your entire provider connection pipeline using native Clojure data structures."]
                           [:url "https://github.com/SamuelCarriles/ehr-adapter"]
                           [:licenses
                            [:license
                             [:name "MIT License"]
                             [:url "https://opensource.org/license/MIT"]]]]})

  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})

  (b/jar {:class-dir class-dir
          :jar-file jar-file}))
