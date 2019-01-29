(ns shadow.cljs.silence-default-loggers
  (:import [java.util.logging Logger Level]))

;; xnio logs an annoying version INFO on startup we are not interested in

(-> (Logger/getLogger "")
    (.setLevel Level/WARNING))


