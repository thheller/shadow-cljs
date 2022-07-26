(ns build
  (:require
    [shadow.css.build :as cb]
    [clojure.java.io :as io]))

(defn css-release []
  (let [build-state
        (-> (cb/start)
            (cb/index-path (io/file "src" "main") {})
            (cb/generate
              '{:ui
                {:include
                 [shadow.cljs.ui*
                  shadow.cljs.devtools.server.web*]}})
            (cb/write-outputs-to (io/file "src" "ui-release" "shadow" "cljs" "ui" "dist" "css")))]
    (tap> build-state)

    (doseq [mod (:outputs build-state)
            {:keys [warning-type] :as warning} (:warnings mod)]

      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))
    ))

(comment
  (time
    (css-release)))