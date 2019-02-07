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
              (throw (ex-info (str "could not resolve " path " from " current-ns) {})))

            (-> parent
                (.resolve path)
                (.normalize)
                (.toString)
                (rc/normalize-name)))

          :else
          path)

        rc (io/resource path)]

    (when-not rc
      (throw (ex-info (str "Resource not found: " path) {:path path})))

    ;; when used as part of a compilation record which namespace did so
    (when env/*compiler*
      (let [last-mod (util/url-last-modified rc)]
        (swap! env/*compiler* assoc-in [::ana/namespaces current-ns ::resource-refs path] last-mod)))

    (slurp rc)))

(defmacro inline
  "inlines the given resource path as a string value"
  [path]
  {:pre [(string? path)]}
  (slurp-resource &env path))