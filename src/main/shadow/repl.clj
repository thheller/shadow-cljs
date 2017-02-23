(ns shadow.repl
  (:require [clojure.core.server :as srv]
            [clojure.main :as m]))

(defn noop-takeover [child-features]
  (prn [:takeover child-features])
  ;; don't care what a child can do, will never call anything
  ;; don't have anything to release
  (fn noop-release []))

(def ^:dynamic *features*
  {::takeover noop-takeover})

(defmacro takeover [child-features & body]
  `(let [fn#
         (get *features* ::takeover)

         release-fn#
         (fn# ~child-features)]
     (try
       ~@body
       (finally
         (when release-fn#
           (release-fn#))))))

(defn get-feature [id]
  (get *features* id))

(defn has-feature? [id]
  (contains? *features* id))

(defn repl [features repl-opts]
  (binding [*features* (merge *features* features)]

    ;; dammit kw-args
    (apply m/repl (->> repl-opts
                       (merge {:init srv/repl-init
                               :read srv/repl-read})
                       (reduce-kv conj [])))))
