(ns shadow.cljs.config-env
  #?(:cljs
     (:require [goog.object :as gobj])))

(defn getenv [envname]
  #?(:cljs
     (gobj/get js/process.env envname)
     :clj
     (System/getenv envname)))

(defn as-int [sval]
  #?(:cljs
     (js/parseInt sval 10)
     :clj ;; clj uses longs as default
     (Long/parseLong sval 10)))

(defn read-env [input]
  (cond
    (string? input)
    (getenv input)

    (not (vector? input))
    (throw (ex-info "invalid #shadow/env definition, expected string or vector" {:input input}))

    :else
    (let [c (count input)]
      (cond
        ;; ["FOO"]
        (= 1 c)
        (getenv (nth input 0))

        ;; ["FOO" "default-value"]
        (= 2 c)
        (let [sval (getenv (nth input 0))]
          (or sval (nth input 1)))

        ;; ["FOO" :as :int :default 12345]
        (odd? c)
        (let [sval (getenv (nth input 0))

              {:keys [as default] :as args}
              (apply array-map (rest input))]
          (cond
            (and (nil? sval) (contains? args :default))
            default

            ;; no value, no :default
            (nil? sval)
            nil

            ;; value, no :as
            (not as)
            sval

            (= :int as)
            (as-int sval)

            (= :bool as)
            (= "true" sval)

            (= :symbol as)
            (symbol sval)

            (= :keyword as)
            (keyword sval)

            :else
            (throw (ex-info "invalid #shadow/env definition, unsupported :as" {:input input}))
            ))

        :else
        (throw (ex-info "invalid #shadow/env definition, expected kv-style arguments after string" {:arg input}))
        ))))


(comment
  (read-env "FOO")
  (read-env ["FOO" "default"])
  (read-env ["FOO" :default "default"])
  (read-env ["HOME" :as :foo])
  (read-env ["NUMBER_OF_PROCESSORS" :as :int]))