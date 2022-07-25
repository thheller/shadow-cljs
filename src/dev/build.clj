(ns build
  (:require
    [shadow.css.build :as cb]))

(defn css-release []
  (let [result
        (-> (cb/start {})
            (cb/index-path "src/main" {})
            (cb/generate
              '{:output-dir "src/ui-release/shadow/cljs/ui/dist/css"
                :chunks
                {:ui
                 {:include
                  [shadow.cljs.ui*
                   shadow.cljs.devtools.server.web*]}}}))]

    (doseq [mod result
            {:keys [warning-type] :as warning} (:warnings mod)]

      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))
    ))

(comment
  (time
    (css-release)))