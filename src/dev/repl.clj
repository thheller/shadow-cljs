(ns repl
  (:require
    [shadow.cljs.devtools.server :as server]
    [shadow.cljs.devtools.api :as cljs]
    [shadow.cljs.devtools.cli]
    [shadow.css.build :as cb]
    [shadow.cljs.devtools.server.fs-watch :as fs-watch]
    [clojure.java.io :as io]))

(defonce css-ref (atom nil))

(defn generate-css
  ([]
   (-> (cb/start {})
       (cb/index-path "src/main" {})
       (generate-css)))
  ([cssb]
   (let [result
         (cb/generate cssb
           '{:output-dir ".shadow-cljs/ui/css"
             :chunks {:main {:include [shadow.cljs.ui*
                                       shadow.cljs.devtools.server.web
                                       shadow.cljs.devtools.server.web.*]}}})]

     (prn [:CSS-GENERATED])

     (doseq [mod result
             {:keys [warning-type] :as warning} (:warnings mod)]

       (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))

     (println))

   :done))

(defn start []
  (server/start!)

  (cljs/watch :ui {})

  ;; until I can figure out a clean API for this
  (let [css
        (-> (cb/start {})
            (cb/index-path "src/main" {}))]

    (generate-css css)

    (reset! css-ref
      (fs-watch/start
        {}
        [(io/file "src" "main")]
        ["cljs" "cljc" "clj"]
        (fn [updates]
          (doseq [{:keys [file event]} updates
                  :when (not= event :del)]
            (cb/index-file css file))

          (generate-css css)))))

  ::started)

(defn stop []
  ;; (cljs/stop!)

  (when-some [css-watch @css-ref]
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
