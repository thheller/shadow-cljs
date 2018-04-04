(ns shadow.cljs.watch-test
  (:require
    [clojure.test :refer (is deftest)]
    [clojure.java.io :as io]
    [shadow.cljs.devtools.server.fs-watch-jvm :as fs-jvm]
    [shadow.cljs.devtools.server.fs-watch-hawk :as fs-hawk]))


(comment
  (def watcher
    (fs-jvm/start {}
      [(io/file "tmp" "watch")]
      #{"test"}
      prn
      ))

  (fs-jvm/stop watcher)

  (def w-hawk
    (fs-hawk/start {}
      [(io/file "tmp" "watch")]
      #{"test"}
      prn
      ))

  (fs-hawk/stop w-hawk))
