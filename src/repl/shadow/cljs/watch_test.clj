(ns shadow.cljs.watch-test
  (:require
    [clojure.test :refer (is deftest)]
    [clojure.java.io :as io]
    [shadow.cljs.devtools.server.fs-watch :as fs-watch]
    [shadow.cljs.devtools.server.fs-watch-previous :as fs-watch-previous]))

(deftest watch-performance
  (add-tap println)
  (let [base-dir (io/file "tmp" "fs-watch")
        test-file (doto (io/file base-dir "foo.txt")
                    (io/make-parents))
        start (atom nil)
        report (fn [label _event]
                 (locking *out*
                   (tap> (str "    " label ": " (- (System/currentTimeMillis) @start) "ms"))))
        fs (fs-watch/start {} [base-dir] #{"txt"}
                           #(report "new-watch" %))
        fsp (fs-watch-previous/start {} [base-dir] #{"txt"}
                                     #(report "previous-watch" %))]
    (dotimes [i 10]
      (reset! start (System/currentTimeMillis))
      (tap> (str "Write" i ":"))
      (spit test-file (str i))
      (Thread/sleep 3500))

    (fs-watch/stop fs)
    (fs-watch-previous/stop fsp)))

;;;; Test output on MacOS BigSur
;;Running shadow.cljs.watch-test/watch-performance
;Write0:
;    new-watch: 13ms
;    previous-watch: 1029ms
;Write1:
;    new-watch: 13ms
;    previous-watch: 1552ms
;Write2:
;    new-watch: 13ms
;    previous-watch: 2072ms
;Write3:
;    new-watch: 12ms
;    previous-watch: 587ms
;Write4:
;    new-watch: 13ms
;    previous-watch: 1110ms
;Write5:
;    new-watch: 13ms
;    previous-watch: 1645ms
;Write6:
;    new-watch: 12ms
;    previous-watch: 2179ms
;Write7:
;    new-watch: 12ms
;    previous-watch: 694ms
;Write8:
;    new-watch: 11ms
;    previous-watch: 1213ms
;Write9:
;    new-watch: 13ms
;    previous-watch: 1741ms
