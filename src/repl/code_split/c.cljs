(ns code-split.c)

(js/console.log :foo)

(defn in-c [x]
  (js/console.log "in-c" x))