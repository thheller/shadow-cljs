(ns shadow.cljs.ui.components.code-editor
  (:require
    ["codemirror" :as cm]
    ["codemirror/mode/clojure/clojure"]
    ["parinfer-codemirror" :as par-cm]
    [clojure.string :as str]
    [shadow.experiments.arborist.protocols :as ap]
    [shadow.experiments.arborist.common :as common]
    [shadow.experiments.grove :as sg]

    [shadow.experiments.grove.components :as comp]
    [shadow.experiments.grove.protocols :as gp]))

(declare EditorInit)

(deftype EditorRoot
  [env
   marker
   ^:mutable opts
   ^:mutable editor
   ^:mutable editor-el]

  ap/IManaged
  (supports? [this ^EditorInit next]
    (instance? EditorInit next))

  (dom-sync! [this ^EditorInit next]
    (let [{:keys [value] :as next-opts} (.-opts next)]
      (set! opts next-opts)

      (when (and editor (seq value))
        (.setValue editor value)
        )))

  (dom-insert [this parent anchor]
    (.insertBefore parent marker anchor))

  (dom-first [this]
    (or editor-el marker))

  ;; codemirror doesn't render correctly if added to an element
  ;; that isn't actually in the dcoument, so we delay construction until actually entered
  (dom-entered! [this]
    (let [{:keys [value cm-opts clojure autofocus]}
          opts

          ;; FIXME: this config stuff needs to be cleaned up, this is horrible
          cm-opts
          (js/Object.assign
            #js {:lineNumbers true
                 :theme "github"
                 :autofocus (not (false? autofocus))}
            (or cm-opts #js {})
            (when-not (false? clojure)
              #js {:mode "clojure"
                   :matchBrackets true})
            (when (seq value)
              #js {:value value}))

          ed
          (cm.
            (fn [el]
              (set! editor-el el)
              (.insertBefore (.-parentElement marker) el marker))
            cm-opts)

          submit-fn
          (fn [e]
            (let [val (str/trim (.getValue ed))]
              (when (seq val)
                (let [comp (comp/get-component env)]
                  (gp/handle-event! comp (conj (:submit-event opts) val) e)
                  (.setValue ed "")))))]

      (set! editor ed)

      (when (:submit-event opts)
        (.setOption ed "extraKeys"
          #js {"Ctrl-Enter" submit-fn
               "Shift-Enter" submit-fn}))

      (when-not (false? clojure)
        (par-cm/init ed))))

  (destroy! [this]
    (when editor-el
      ;; FIXME: can't find a dispose method on codemirror?
      (.remove editor-el))
    (.remove marker)))

(deftype EditorInit [opts]
  ap/IConstruct
  (as-managed [this env]
    (EditorRoot. env (common/dom-marker env) opts nil nil)))

(defn codemirror [opts]
  (->EditorInit opts))