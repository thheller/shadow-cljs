(ns repl
  (:require
    [shadow.cljs.devtools.server :as server]
    [shadow.cljs.devtools.api :as cljs]
    [shadow.cljs.devtools.cli]
    [shadow.css.build :as cb]
    [shadow.cljs.devtools.server.fs-watch :as fs-watch]
    [clojure.java.io :as io]))

(defonce css-ref (atom nil))
(defonce css-watch-ref (atom nil))

(defn generate-css []
  (let [result
        (-> @css-ref
            (cb/generate
              '{:ui
                {:include
                 [shadow.cljs.ui*
                  shadow.cljs.devtools.server.web*]}})
            (cb/write-outputs-to (io/file ".shadow-cljs" "ui" "css")))]

    (prn :CSS-GENERATED)

    (doseq [mod (:outputs result)
            {:keys [warning-type] :as warning} (:warnings mod)]

      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))

    (println))

  :done)

(defn start []
  (server/start!)

  ;; (cljs/watch :ui {})

  ;; until I can figure out a clean API for this
  (reset! css-ref
    (-> (cb/start)
        (cb/index-path (io/file "src" "main") {})))

  (generate-css)

  (reset! css-watch-ref
    (fs-watch/start
      {}
      [(io/file "src" "main")]
      ["cljs" "cljc" "clj"]
      (fn [updates]
        (try
          (doseq [{:keys [file event]} updates
                  :when (not= event :del)]
            (swap! css-ref cb/index-file file))

          (generate-css)
          (catch Exception e
            (prn :css-build-failure)
            (prn e))))))

  ::started)

(defn stop []
  ;; (cljs/stop!)

  (when-some [css-watch @css-watch-ref]
    (fs-watch/stop css-watch)
    (reset! css-ref nil))

  (server/stop!)
  ::stopped)

;; (ns-tools/set-refresh-dirs "src/main")

(defn go []
  (stop)
  ;; this somehow breaks reloading
  ;; the usual :reloading message tells me that is namespace is being reloaded
  ;; but when the new instance is launched it is still using the old one
  ;; i cannot figure out why
  ;; (ns-tools/refresh :after 'repl/start)
  (start))

(defn -main []
  (start)
  (read)
  (stop))



(comment
  (generate-css))
