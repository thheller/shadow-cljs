(ns demo.browser
  (:require [npm.react :as react]
            [npm.react-dom :as rdom])
  (:import [npm.react Component]))


;; :refer (createElement) doesn't work
;; Invalid :refer, var npm.react/createElement does not exist
;; that is wrong of course but would need patch in cljs to fix

;; CLJS seems to assume that npm.react is CLJS code and emit
;; (npm.react.createElement.cljs$core$IFn$_invoke$arity$3 ? npm.react.createElement.cljs$core$IFn$_invoke$arity$3("div",null,"hello world") : npm.react.createElement.call(null,"div",null,"hello world"));
;; needs https://dev.clojure.org/jira/browse/CLJS-2040

(defn foo []
  (react/createElement "h1" nil "hello from react"))

(js/console.log "demo.browser" Component)

(prn :foo)

(defn ^:export start []
  (prn "foo")
  (js/console.log "browser-start")

  (rdom/render (foo) (js/document.getElementById "app")))

(defn stop []
  (js/console.log "browser-stop"))

