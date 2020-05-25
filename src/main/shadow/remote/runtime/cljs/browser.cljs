(ns shadow.remote.runtime.cljs.browser
  (:require [shadow.remote.runtime.cljs.websocket]))

;; keeping this ns in case there is something browser specific in the future
;; for now websocket is generic enough to work for browser and react-native
;; basically anything having built-in Websocket should work
