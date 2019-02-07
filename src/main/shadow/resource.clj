(ns shadow.resource
  (:require
    [clojure.java.io :as io]
    [shadow.cljs.util :as util]
    [cljs.env :as env]
    [cljs.analyzer :as ana]
    [clojure.string :as str]
    [shadow.build.resource :as rc])
  (:import [java.nio.file Paths]))

(defn slurp-resource
  "API function to let other macros read resources while also recording that they did so"
  [env path]
  (let [current-ns (-> env :ns :name)

        path
        (cond
          (str/starts-with? path "/")
          (subs path 1)

          (str/starts-with? path ".")
          (let [resource-name
                (util/ns->cljs-filename current-ns)

                parent
                (-> (Paths/get resource-name (into-array String []))
                    (.getParent))]

            (when-not parent
              (throw (ana/error env (str "Could not resolve " path " from " current-ns))))

            (-> parent
                (.resolve path)
                (.normalize)
                (.toString)
                (rc/normalize-name)))

          :else
          path)

        rc (io/resource path)]

    (when-not rc
      (throw (ana/error env (str "Resource not found: " path))))

    ;; when used as part of a compilation record which namespace did so
    (when env/*compiler*
      (let [last-mod (util/url-last-modified rc)]
        (swap! env/*compiler* assoc-in [::ana/namespaces current-ns ::resource-refs path] last-mod)))

    (slurp rc)))

(defmacro inline
  "Inlines the given resource path as a string value

   will throw if the path is not found on the classpath.
   relative paths will be resolved relative to the current namespace

   (ns demo.app
     (:require [shadow.resource :as rc]))

   (def x (rc/inline \"./test.md\"))

   this includes demo/test.md from the classpath and ends as

   (def x \"contents of test.md\")

   Cannot be used dynamically and should be limited to small files.
   Larger files should be loaded dynamically at runtime."
  [path]
  (when-not (string? path)
    (throw (ana/error &env (str "shadow.resource/inline must be called with a literal string argument"))))
  (slurp-resource &env path))