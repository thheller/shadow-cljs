(ns shadow.css
  (:require
    [shadow.css.specs :as s]
    [clojure.string :as str]))

(def class-defs-ref
  (atom {}))

;; for clojure we just do lookups at runtime
;; by default is empty but in case something has minified the css
;; it can provide those lookups and put them in the class-defs-ref above
(defn get-class [class]
  (get @class-defs-ref class class))

(defmacro css
  "generates css classnames

   using a subset of Clojure/EDN to define css rules, no dynamic code allowed whatsoever"
  [& body]

  ;; FIXME: errors are not pretty
  (s/conform! &form)

  (let [{:keys [line column]}
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
        (s/generate-id ns-str line column)

        passthrough
        (->> body
             (filter string?)
             (str/join " "))]

    ;; using analyzer data is hard to combine with CLJ data
    ;; so instead just using an external thing that finds (css ...) calls
    ;; and generates the stuff we need
    #_class-def
    #_(assoc conformed
        :ns (symbol ns-str)
        :line line
        :column column
        :css-id css-id)

    ;; FIXME: no idea what to do about self-host yet
    (if-not (:ns &env)
      (if (seq passthrough)
        `(str ~(str passthrough " ") (get-class ~css-id))
        `(get-class ~css-id))
      (if (seq passthrough)
        `(~'js* "(~{} + shadow.css.sel(~{}))" ~(str passthrough " ") ~css-id)
        `(~'js* "(shadow.css.sel(~{}))" ~css-id)))))

(comment

  (require 'clojure.pprint)

  @class-defs-ref

  (clojure.pprint/pprint
    (macroexpand
      '(css :foo
         "yo" {:hello "world"})
      )))