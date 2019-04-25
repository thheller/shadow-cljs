(ns shadow.cljs.npm.babel-worker
  (:require
    [clojure.string :as str]
    [cljs.core.async :as async :refer (go)]
    [cljs.reader :refer (read-string)]
    ["@babel/core" :as babel]
    ["@babel/preset-env" :as babel-preset-env]
    ))

;; FIXME: generating all external helpers adds 44kb to the build
;; given that we can't properly track which ones are used that is too much overhead
;; so instead let it inject only the required helpers when needed
;; FIXME: should probably try to track which helpers are used and generate them properly
(defn external-helpers-plugin [ref]
  (let [^js t (.. ref -types)]
    #js {:pre #(.set % "helperGenerator" (fn [name]
                                           (.memberExpression t
                                             (.identifier t "global.shadow.js.babel")
                                             (.identifier t name))))}))

(defn babel-transform [{:keys [code file preset-config] :as req}]
  (let [presets
        (array
          (if-not preset-config
            babel-preset-env
            (array babel-preset-env (clj->js preset-config))))

        plugins
        #js []

        opts
        #js {:presets presets
             :plugins plugins
             :babelrc false
             :filename file
             :highlightCode false
             :inputSourceMap true
             :sourceMaps true}

        res
        (babel/transform code opts)]

    {:code (.-code res)
     :source-map-json (js/JSON.stringify (.-map res))
     }))

(defn process-request [line]
  (let [req
        (read-string line)

        #_{:code "class Foo {}; let x = 1; export { x }; export default x;"
           :resource-name "test.js"}
        res
        (babel-transform req)]
    (prn res)))

(defn process-chunk [buffer chunk]
  (let [nl (str/index-of chunk "\n")]
    (if-not nl
      ;; no newline, return new buffer
      (str buffer chunk)

      ;; did contain \n, concat with remaining buffer, handoff
      (let [line (str buffer (subs chunk 0 nl))]
        (process-request line)
        (recur "" (subs chunk (inc nl))))
      )))

(defn main [& args]
  (let [stdin
        (async/chan)

        main-loop
        (go (loop [buffer ""]
              (when-some [chunk (<! stdin)]
                (recur (process-chunk buffer chunk))
                )))

        stdin-data
        (fn stdin-data [buf]
          (let [chunk (.toString buf)]
            (async/put! stdin chunk)))]

    (js/process.stdin.on "data" stdin-data)
    (js/process.stdin.on "close" #(async/close! stdin))))
