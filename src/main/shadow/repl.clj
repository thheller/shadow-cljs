(ns shadow.repl
  (:require [clojure.core.server :as srv]
            [clojure.main :as m]))

(defonce ^:private levels-ref (volatile! (list)))

(defn levels []
  @levels-ref)

(defn do-takeover [level-actions]
  (let [my-level (count @levels-ref)]
    (vswap! levels-ref conj (assoc level-actions ::level my-level ::ns (str *ns*)))
    (fn release []
      (vswap! levels-ref pop))))

(def ^:dynamic *features*
  {::takeover do-takeover})

(defmacro takeover [level-actions & body]
  `(let [fn#
         (get *features* ::takeover)

         release-fn#
         (fn# ~level-actions)]
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
