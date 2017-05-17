(ns demo.browser
  (:require [npm.react :refer (createElement)]))

(js/console.log "foo")
(js/console.log "demo.browser" (createElement "div" nil "hello world"))

(prn :foo)

(defn ^:export start []
  (prn "foo")
  (js/console.log "browser-start"))

(defn stop []
  (js/console.log "browser-stop"))

