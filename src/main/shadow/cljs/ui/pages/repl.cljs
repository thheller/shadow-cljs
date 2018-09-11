(ns shadow.cljs.ui.pages.repl
  (:require
    [clojure.string :as str]
    [shadow.api :refer (ns-ready)]
    [shadow.dom :as dom]
    [shadow.react.component :as comp]
    [shadow.markup.react :as html :refer (defstyled)]
    [shadow.cljs.ui.env :as env]
    [shadow.cljs.ui.util :as util]
    [shadow.cljs.ui.model :as ui-model]
    [shadow.cljs.ui.style :as s]
    [cljs.core.async :as async :refer (go alt!)]
    [cljs.reader :as reader]
    ["react-dom" :as rd]
    ["codemirror" :as cm]
    ["codemirror/mode/clojure/clojure"]
    ["parinfer-codemirror" :as par-cm]
    ["xterm" :as xterm]
    ["xterm/lib/addons/fit/fit" :as xterm-fit]
    [fulcro.client.primitives :as fp :refer (defsc)]
    [shadow.cljs.ui.transactions :as tx]
    [shadow.cljs.ui.routing :as routing]))

(defn send-msg [{:keys [ws-out] :as state} msg]
  (let [msg-id
        (str (random-uuid))

        msg-out
        (assoc msg :id msg-id)]

    (when-not (async/offer! ws-out msg-out)
      (js/console.log "offer failed" msg-out))

    (-> state
        (update :message-out conj msg-out))
    ))

(defn ws-process-in [{:keys [^js term] :as state} {:keys [tag] :as msg}]
  (js/console.log "ws-in" msg)
  (case tag
    :socket-in
    (.write term (:text msg))

    nil)
  state)

(defn ws-process-cmd [{:keys [^js editor ^js term] :as state} cmd]
  (js/console.log "ws-cmd" cmd)
  (case cmd
    :eval
    (let [val (str/trim (.getValue editor))]
      (.setValue editor "")
      (.write term (str val "\n"))
      (send-msg state {:tag :socket-out :text val}))

    (do (js/console.log "unknown cmd" cmd)
        state)))

(defn attach-terminal [comp term-div]
  (js/console.log ::attach-terminal term-div comp)

  (if-not term-div
    (let [{::keys [term term-resize]} (util/get-local! comp)]
      (when term-resize
        (js/window.removeEventListener "resize" term-resize))
      (when term
        (.destroy term))
      (util/swap-local! comp dissoc ::term ::term-resize))

    ;; fresh mount
    (let [term
          (doto (xterm/Terminal. #js {:disableStdin true
                                      :convertEol true
                                      :fontFamily "monospace"
                                      :fontSize 12
                                      :theme #js {:foreground "#000000"
                                                  :background "#FFFFFF"
                                                  :selection "rgba(0,0,0,0.3)"}})
            (.open term-div))

          term-resize
          (fn []
            (xterm-fit/fit term))]

      (term-resize)

      (js/window.addEventListener "resize" term-resize)

      (util/swap-local! comp assoc
        ::term term
        ::term-resize term-resize))))

(defn do-eval [comp]
  (let [{::keys [^js editor]} (util/get-local! comp)
        text (.getValue editor)]
    (fp/transact! comp [(tx/process-repl-input {:text text})])))

(defn attach-codemirror [comp cm-input]
  (js/console.log ::attach-codemirror cm-input comp)
  (if-not cm-input
    (let [{::keys [editor]} (util/get-local! comp)]
      (when editor
        (.toTextArea editor))
      (util/swap-local! comp dissoc ::editor))

    (let [editor
          (cm/fromTextArea
            cm-input
            #js {:lineNumbers true
                 :mode "clojure"
                 :theme "github"
                 :autofocus true
                 :matchBrackets true})]

      (.setOption editor "extraKeys"
        #js {"Ctrl-Enter" #(do-eval comp)
             "Shift-Enter" #(do-eval comp)})

      (par-cm/init editor)
      (util/swap-local! comp assoc ::editor editor))))

(defstyled html-container :div [_]
  {:flex 1
   :display "flex"
   :flex-direction "column"})

(defstyled term-container :div [_]
  {:flex 1
   :padding-left 10
   :overflow "hidden"})

(defstyled editor-container :div [_]
  {:height 200
   :border-bottom "2px solid #eee"
   :border-top "2px solid #eee"})

(defstyled html-toolbar :div [_]
  {:padding 10
   :margin-bottom 10
   :font-weight "bold"})

(defstyled input-toolbar :div [_]
  {:padding 10
   :display "flex"})

(defsc Page [this props]
  {:ident
   (fn []
     [::ui-model/page-repl 1])

   :query
   (fn []
     [])

   :initial-state
   (fn [p]
     {})}

  (html-container
    (html-toolbar "REPL")
    (term-container {:ref (util/comp-fn this ::term-ref attach-terminal)})
    (editor-container
      (html/input {:ref (util/comp-fn this ::editor-ref attach-codemirror)}))
    (input-toolbar
      (s/toolbar-actions
        (s/toolbar-action
          {:type "button"
           :title "shift+enter"
           :onClick (fn [e]
                      (.preventDefault e)
                      (do-eval this))}
          "eval")

        (s/toolbar-action
          {:type "button"
           :title "ctrl+e"}
          "history")
        ))))

(def ui-page (fp/factory Page {}))

(routing/register ::ui-model/root-router ::ui-model/page-repl
  {:class Page
   :factory ui-page})

(defn route [r]
  (fp/transact! r
    [(routing/set-route
       {:router ::ui-model/root-router
        :ident [::ui-model/page-repl 1]})]))


