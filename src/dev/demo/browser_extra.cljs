(ns demo.browser-extra
  (:require [demo.browser :as b]))

(js/console.log "bar")
(js/console.log "demo.browser-extra" ::foo ::foo)

;; (throw (ex-info "boom!" {}))

(def x "i'm from browser-extra")
(def y "i'm from browser-extra too")