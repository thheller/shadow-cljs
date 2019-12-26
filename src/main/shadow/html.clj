(ns shadow.html
  (:require
    [shadow.build :as build]
    [shadow.build.closure :as closure]
    [shadow.cljs.util :as util]
    [clojure.java.io :as io]
    [clojure.string :as str]))

(defn replace-script-names [html asset-path modules]
  (reduce
    (fn [html {:keys [module-id output-name] :as mod}]
      (let [script-path (str asset-path "/" (name module-id) ".js")]
        (str/replace html script-path (str asset-path "/" output-name))))
    html
    modules))

(comment
  (replace-script-names
    "<script src=\"/js/foo.js\"></script>"
    "/js"
    [{:module-id :foo :output-name "foo.123.js"}])

  (replace-script-names
    "<script src=\"/js/foo.js\"></script>"
    "/no-replace"
    [{:module-id :foo :output-name "foo.123.js"}]))

(defn copy-file
  {:shadow.build/stage :flush}
  [{::build/keys [mode config] :as build-state} source target]
  (let [source-file (io/file source)
        target-file (io/file target)]
    (cond
      (not (.exists source-file))
      (do (util/log build-state {:type ::source-does-not-exist :source source})
          build-state)

      ;; in dev mode we don't need to copy more than once
      (and (= :dev mode)
           (= (.lastModified source-file) (::source-last-mod build-state))
           (.exists target-file))
      build-state

      :else
      (let [html
            (-> (slurp source-file)
                ;; only need to replace in release builds with :module-hash-names
                ;; otherwise just copy the original
                (cond->
                  (and (= :release mode)
                       (:module-hash-names config))
                  (replace-script-names
                    (:asset-path config "/js")
                    (or (::closure/modules build-state)
                        (:build-modules build-state)))))]

        (io/make-parents target-file)
        (spit target-file html)

        (assoc build-state ::source-last-mod (.lastModified source-file))))))
