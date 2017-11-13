(ns shadow.cljs.npm.transform
  (:require-macros [cljs.core.async.macros :refer (go)])
  (:require [cljs.core.async :as async]
            ["babel-core" :as babel]
            ["babel-preset-env" :as babel-preset-env]
            [clojure.string :as str]
            [cljs.reader :refer (read-string)]))

;; why is this a plugin and not a config option?
(defn external-helpers-plugin [ref]
  (let [t (.. ref -types)
        id (.identifier t "global.shadow.js.babel")]
    #js {:pre #(.set % "helpersNamespace" id)}))

(defn babel-transform [{:keys [code resource-name]}]
  (let [presets
        #js [babel-preset-env]

        plugins
        #js [external-helpers-plugin]

        opts
        #js {:presets presets
             :plugins plugins
             ;; only allow babelrc processing for project files
             :babelrc (not (str/includes? resource-name "node_modules"))
             :filename resource-name
             :highlightCode false
             :sourceMaps true}

        res
        (babel/transform
          code
          opts)]

    {:code (.-code res)
     :source-map-json (js/JSON.stringify (.-map res))
     :metadata (js->clj (.-metadata res) :keywordize-keys true)}
    ))

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

(def stdin (async/chan))

(def main-loop
  (go (loop [buffer ""]
        (when-some [chunk (<! stdin)]
          (recur (process-chunk buffer chunk))
          ))))

(defn stdin-data [buf]
  (let [chunk (.toString buf)]
    (async/put! stdin chunk)))

(js/process.stdin.on "data" stdin-data)
(js/process.stdin.on "close" #(async/close! stdin))
