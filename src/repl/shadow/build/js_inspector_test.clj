(ns shadow.build.js-inspector-test
  (:require
    [clojure.test :as ct :refer (deftest is)]
    [clojure.java.io :as io]
    [shadow.build.data :as data]
    [shadow.build.closure :as closure]
    [shadow.build.classpath :as classpath])
  (:import
    [shadow.build.closure JsInspector]))


(deftest dummy-inspect

  (let [resource-name
        "goog/events/events.js"

        source
        (slurp (io/resource resource-name))

        {:keys [compiler] :as cp}
        (classpath/start (io/file "tmp" "test-cache-root"))

        info
        (JsInspector/getFileInfoMap
          compiler
          (closure/closure-source-file resource-name source))]

    (tap> info)

    (classpath/stop cp)
    ))

