;; # h1
;; hello world

(js/console.log "yo from insight file" ::foo)

(!/in-remote {:lang :clj})

(System/getenv)

;; already in it, but switching should be ok
(!/in-remote {:lang :clj})

(System/getProperties)

(!/in-local)

(js/console.log "back in viewer")

;; ## extra1
;; hello world

(require '[shadow.grove.insight])
(require '[shadow.grove :as sg :refer (defc <<)])

(<< [:h1 "hello world!"])

;; trailing comment?