(ns demo.npm
  (:require [shadow.npm :as npm]))

(js/console.log "demo.npm" (.. (npm/env) -goog -global -setTimeout))

;; (js/console.log "npm/require" (npm/require "react"))
(js/console.log "npm/require-file" (npm/require-file "src/dev/demo/foo"))
