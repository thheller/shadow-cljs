(ns shadow.devtools.nrepl
  (:require [clojure.tools.nrepl :as nrepl]
            [clojure.pprint :refer (pprint)]
            [clojure.tools.nrepl.middleware :as nrepl-middleware]
            [clojure.core.async :as async :refer (>!!)]))


;; nrepl makes soooo many assumptions about running on the JVM
;; I'm not sure it makes sense to use any of it
;; just write a custom implementation of "clone"&co

(defn make-handler [config state callback]
  (fn [handler]
    (let [state (callback state [])]
      (fn [{:keys [op] :as msg}]
        (prn [:nrepl-handler op])
        (when (= op "eval")
          (prn [:eval (:code msg)]))
        (handler msg)
        ))))

(defmacro defmiddleware [name config init callback]
  `(do (def ~name (make-handler ~config ~init ~callback))
       (nrepl-middleware/set-descriptor!
         (var ~name)
         {:requires #{"clone"}
          :expects #{"eval"
                     "load-file"}
          :handles {}})))


