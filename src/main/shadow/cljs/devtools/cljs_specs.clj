(ns shadow.cljs.devtools.cljs-specs
  (:require [clojure.spec.alpha :as s]
            [clojure.core.specs.alpha :as cs]))

;; FIXME: remove once this is in cljs.core

(s/fdef cljs.core/let
  :args (s/cat :bindings ::cs/bindings
               :body (s/* any?)))

(s/fdef cljs.core/let
  :args (s/cat :bindings ::cs/bindings
               :body (s/* any?)))

(s/fdef cljs.core/if-let
  :args (s/cat :bindings (s/and vector? ::cs/binding)
               :then any?
               :else (s/? any?)))

(s/fdef cljs.core/when-let
  :args (s/cat :bindings (s/and vector? ::cs/binding)
               :body (s/* any?)))

(s/fdef cljs.core/defn
  :args ::cs/defn-args
  :ret any?)

(s/fdef cljs.core/defn-
  :args ::cs/defn-args
  :ret any?)

(s/fdef cljs.core/fn
  :args (s/cat :name (s/? simple-symbol?)
               :bs (s/alt :arity-1 ::cs/args+body
                          :arity-n (s/+ (s/spec ::cs/args+body))))
  :ret any?)
