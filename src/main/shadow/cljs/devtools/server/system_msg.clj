(ns shadow.cljs.devtools.server.system-msg
  (:require [clojure.spec.alpha :as s]))

;; FIXME: do this properly and actually validate somewhere

(s/def ::cljs-watch any?)
(s/def ::config-watch any?)

;; sent when build resources were added/removed/changed
(s/def ::resource-update any?)

