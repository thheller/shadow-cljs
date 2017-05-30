(ns shadow.cljs.npm.nrepl
  (:require-macros [cljs.core.async.macros :refer (go)])
  (:require [cljs.core.async :as async]
            ["net" :as net]
            ))

(defn client [port args]
  (prn [:nrepl-client port args])

  (let [socket (net/connect #js {})]

    true))
