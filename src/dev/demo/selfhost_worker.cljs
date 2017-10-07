(ns demo.selfhost-worker
  (:require [shadow.cljs.bootstrap.browser :as boot]
            [cljs.js :as cljs]
            [cljs.env :as env]
            [cognitect.transit :as transit]))

(defn transit-read [txt]
  (let [r (transit/reader :json)]
    (transit/read r txt)))

(defn transit-write [obj]
  (let [r (transit/writer :json)]
    (transit/write r obj)))

(defonce compile-state-ref (env/default-compiler-env))

(defn post-message [obj]
  (js/postMessage (transit-write obj)))

(defmulti do-request :action :default ::default)

(defmethod do-request ::default [msg]
  (js/console.log "unknown action" msg))

(defmethod do-request :compile [{:keys [code]}]
  (cljs/eval-str
    compile-state-ref
    code
    "[test]"
    {:analyze-deps false
     :eval (fn [{:keys [source] :as x}]
             (js/console.log "eval" x)
             (post-message {:action :eval :source source}))
     :load (partial boot/load compile-state-ref)}
    (fn [result]
      (js/console.log "result" result))))


(defn ready []
  (post-message {:action :ready})
  (set! (.-onmessage js/self)
    (fn [e]
      (let [data (transit-read (.-data e))]
        (do-request data))
      )))

(boot/init compile-state-ref
  {:path "/bootstrap"
   :load (fn [{:keys [type source] :as load-info}]
           (when (= :js type)
             ;; FIXME: host does not need to eval $macros
             (post-message {:action :shadow-load :load-info load-info})))}
  ready)
