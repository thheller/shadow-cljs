(ns shadow.lazy
  (:require
    [clojure.spec.alpha :as s]
    [cljs.env :as env]
    [cljs.analyzer :as ana]
    [cljs.compiler :as comp]
    [clojure.string :as str]))

(defn module-for-ns [compiler-env ns]
  (get-in compiler-env [::ns->mod ns]))

(defn module-for-ns! [env ns]
  (let [mod (module-for-ns @env/*compiler* ns)]
    (when-not mod
      (throw (ana/error env (str "Could not find module for ns: " ns))))
    mod))

;; FIXME: macro spec
;; FIXME: make compiler check if loadable vars actually exist
;; FIXME: maybe best to emit a symbol only and assign that symbol later
;; cache invalidation otherwise is a bit messy. can't do this properly in one pass
;; FIXME: maybe should use quoted arguments? this would be fine as a function in CLJ if quoted

(defmacro loadable [thing]
  ;; FIXME: if expanding to CLJ code emit something with the same interface
  (let [current-ns (-> &env :ns :name)]
    (cond
      (qualified-symbol? thing)

      (let [ns (-> thing (namespace) (symbol))
            module (module-for-ns! &env ns)]
        (swap! env/*compiler* assoc-in [::ana/namespaces current-ns ::ns-refs ns] module)
        ;; emit js* so the compiler doesn't attempt to resolve it at all
        `(shadow.lazy/Loadable.
           [~module]
           (fn [] ~(list 'js* (str (comp/munge thing))))))

      (map? thing)
      (let [ns-map
            (reduce-kv
              (fn [ns-map alias sym]
                (let [ns (-> sym namespace symbol)
                      module (module-for-ns! &env ns)]
                  (assoc ns-map ns module)))
              {}
              thing)

            deref-map
            (reduce-kv
              (fn [m alias sym]
                (assoc m alias (list 'js* (str (comp/munge sym)))))
              {}
              thing)]

        (swap! env/*compiler* update-in [::ana/namespaces current-ns ::ns-refs] merge ns-map)

        `(shadow.lazy/Loadable.
           ~(-> ns-map vals distinct vec)
           (fn [] ~deref-map)))

      (vector? thing)
      (let [ns-map
            (reduce
              (fn [ns-map sym]
                (let [ns (-> sym namespace symbol)
                      module (module-for-ns! &env ns)]
                  (assoc ns-map ns module)))
              {}
              thing)

            deref-vec
            (reduce
              (fn [v sym]
                (conj v (list 'js* (str (comp/munge sym)))))
              []
              thing)]

        (swap! env/*compiler* update-in [::ana/namespaces current-ns ::ns-refs] merge ns-map)

        `(shadow.lazy/Loadable.
           ~(-> ns-map vals distinct vec)
           (fn [] ~deref-vec)))

      :else
      (throw (ex-info "invalid argument" {:thing thing}))
      )))
