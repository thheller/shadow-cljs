(ns shadow.devtools.server.system-msg
  (:require [clojure.spec :as s]))

;; FIXME: do this properly and actually validate somewhere

(s/def ::cljs-watch any?)
(s/def ::sass-watch any?)

