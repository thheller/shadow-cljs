(ns shadow.insight-test
  (:require
    [clojure.java.io :as io]
    [clojure.pprint :refer (pprint)]
    [clojure.test :as ct :refer (deftest is)]
    [rewrite-clj.node :as n]
    [rewrite-clj.parser :as p]
    [rewrite-clj.zip :as z]
    [shadow.insight.parser :as sip]))

(deftest parsing
  (let [content
        (slurp (io/resource "shadow/insight/example__in.clj"))

        blocks
        (sip/parse content)]

    (tap> blocks)
    ))

(deftest markdown-parsing
  (let [text "# yo\nhello world\n```foo```"]
    (prn (sip/md->data text))
    ))