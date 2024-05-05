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
                {:entries [shadow.cljs.ui.main
                           ;; FIXME: since this is lazy-loaded shadow.css doesn't find it on its own
                           shadow.cljs.ui.components.code-editor]}})
            (cb/minify)
            (cb/write-outputs-to (io/file "src" "ui-release" "shadow" "cljs" "ui" "dist" "css")))]

    (doseq [mod (:outputs build-state)
            {:keys [warning-type] :as warning} (:warnings mod)]

      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))
    ))

(comment
  (time
    (css-release)))