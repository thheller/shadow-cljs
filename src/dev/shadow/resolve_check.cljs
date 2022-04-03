(ns shadow.resolve-check
  (:require
    ["enhanced-resolve" :as er]
    ["path" :as path]
    ["fs" :as fs]
    [cljs.reader :as reader]
    [cljs.pprint :refer (pprint)]))

(defn fail! [test err file]
  (prn [:fail! test file])
  (js/console.log err)
  (js/process.exit 1))


(-> (.process processor "* org-mode example\n your text goes here")
    (.then (fn [^js file]
             (js/console.log (.-result file)))))

(defn main [& args]
  (let [test-dir
        (path/resolve ".." ".." "test-env")

        tests
        (-> (fs/readFileSync "tests.edn")
            (.toString)
            (reader/read-string))

        cache-fs
        (er/CachedInputFileSystem. fs 4000)]

    (doseq [{:keys [from request expected extensions js-package-dirs entry-keys use-browser-overrides] :as test} tests]
      (let [resolver
            (er/create.sync
              #js {:fileSystem cache-fs
                   :aliasFields (if-not (false? use-browser-overrides)
                                  #js ["browser"]
                                  #js [])
                   :mainFields (clj->js (or entry-keys ["browser" "main" "module"]))
                   :modules (clj->js (or js-package-dirs ["node_modules"]))
                   :extensions (clj->js (or extensions [".js" ".json"]))})

            expected
            (if (string? expected)
              (path/resolve test-dir expected)
              expected)

            from
            (if from
              ;; could use resolveToContext, need the dir not the file
              (let [from-file (resolver #js {} test-dir from #js {})]
                (path/dirname from-file))
              test-dir)

            file
            (try
              (resolver #js {} from request #js {})
              (catch :default err
                (if (:fail-expected test)
                  expected
                  (fail! test err nil))))]

        (if (not= file expected)
          (fail! test :expected file)
          (prn [:OK test])
          ))))

  (println "ALL OK."))
