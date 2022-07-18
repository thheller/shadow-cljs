(ns shadow.css
  (:require
    [shadow.css.specs :as s]))

(def class-defs
  (atom {}))

(defn get-class [class]
  ;; FIXME: need to actually generate classnames for CLJ
  (get @class-defs class class))

(defmacro css
  "generates css classnames

   using a subset of Clojure/EDN to define css rules, no dynamic code allowed whatsoever"
  [& body]
  ;; FIXME: only conforming for spec errors, not required otherwise
  (let [conformed
        (s/conform! body)

        {:keys [line column]}
        (meta &form)

        ns-str
        (str *ns*)

        ;; this must generate a unique identifier right here
        ;; using only information that can be taken from the css form
        ;; itself. It must not look at any other location and the id
        ;; generated must be deterministic.

        ;; this unfortunately makes it pretty much unusable in the REPL
        ;; this is fine since there is no need for CSS in the REPL
        ;; but may end up emitting invalid references in code
        ;; which again is fine in JS since it'll just be undefined
        css-id
        (s/generate-id ns-str line column)]

    ;; using analyzer data is hard to combine with CLJ data
    ;; so instead just using an external thing that finds (css ...) calls
    ;; and generates the stuff we need
    #_class-def
    #_(assoc conformed
        :ns (symbol ns-str)
        :line line
        :column column
        :css-id css-id)

    (if-not (:ns &env)
      ;; expanding CLJ
      `(get-class ~css-id)

      ;; expanding CLJS
      ;; generating js* calls so the analyzer doesn't get any idea about trying to resolve these
      ;; emitting straight lookups instead of a map so :advanced can go its thing properly
      ;; FIXME: no idea what to do about self-host yet
      `(~'js* ~(str "(shadow.css.defs." css-id ")")))))

(comment

  (require 'clojure.pprint)

  @class-defs

  (clojure.pprint/pprint
    (macroexpand
      '(css :foo
         "yo" {:hello "world"})
      )))