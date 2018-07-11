(ns shadow.cljs.ui.repl
  (:require
    [clojure.string :as str]
    [shadow.api :refer (ns-ready)]
    [shadow.dom :as dom]
    [shadow.markup.react :as html :refer (defstyled)]
    [cljs.core.async :as async :refer (go alt!)]
    [cljs.reader :as reader]
    ["react-dom" :as rd]
    ["codemirror" :as cm]
    ["codemirror/mode/clojure/clojure"]
    ["parinfer-codemirror" :as par-cm]
    ["xterm" :as xterm]
    ["xterm/lib/addons/fit/fit" :as xterm-fit]
    ))

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

(defonce state-ref
  (atom {}))

(defn ws-loop []
  (go (loop []
        (let [{:keys [cmd-in ws-in]} @state-ref]
          (alt!
            ws-in
            ([msg]
              (when (some? msg)
                (swap! state-ref ws-process-in msg)
                (recur)))

            cmd-in
            ([cmd]
              (swap! state-ref ws-process-cmd cmd)
              (recur)
              ))))))

(defn attach-terminal [term-div]
  (if-not term-div
    (when-some [term (:term @state-ref)]
      (js/window.removeEventListener "resize" (:term-resize @state-ref))
      (.destroy term))

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

      ;; FIXME: resizing somehow doesn't resize properly
      (js/window.addEventListener "resize" term-resize)

      (swap! state-ref assoc
        :term term
        :term-resize term-resize)
      )))

(defn attach-codemirror [cm-input]
  (if-not cm-input
    (when-some [editor (:editor @state-ref)]
      (.toTextArea editor))

    (let [editor
          (cm/fromTextArea
            cm-input
            #js {:lineNumbers true
                 :mode "clojure"
                 :theme "github"
                 :autofocus true
                 :matchBrackets true})]

      (.setOption editor "extraKeys"
        #js {"Shift-Enter" #(async/put! (:cmd-in @state-ref) :eval)})

      (par-cm/init editor)
      (swap! state-ref assoc :editor editor))))

(defstyled html-container :div [_]
  {:display "flex"
   :overflow "hidden"
   :position "absolute"
   :top 0
   :left 0
   :right 0
   :bottom 0
   :height "auto"
   ;;:margin 10
   ;; :border-radius 2
   ;; :box-shadow "0 3px 1px -2px rgba(0,0,0,.2), 0 2px 2px 0 rgba(0,0,0,.14), 0 1px 5px 0 rgba(0,0,0,.12)"
   :flex-direction "column"})

(defstyled term-container :div [_]
  {:flex 1
   :padding-left 10})

(defstyled editor-container :div [_]
  {:height 200
   :border-top "2px solid #eee"})

(defstyled html-toolbar :div [_]
  {:padding 10
   :margin-bottom 10
   :background-color "#eee"
   :font-weight "bold"})

(defn ui [state]
  (html/div
    (html-container
      (html-toolbar "[ALPHA] shadow-cljs REPL")

      (term-container {:ref attach-terminal})
      (editor-container
        (html/input {:ref attach-codemirror})))))

(defn ^:dev/after-load start []
  (let [cmd-in
        (async/chan 100)

        ws-in
        (async/chan 100)

        ws-out
        (async/chan 100)

        ws
        (js/WebSocket. (str "ws://" js/document.location.host "/repl-ws"))

        init-state
        {:ws ws
         :ws-in ws-in
         :ws-out ws-out
         :cmd-in cmd-in}

        dom-target
        (dom/by-id "root")]

    (swap! state-ref merge init-state)

    (. ws (addEventListener "message"
            (fn [e]
              (try
                (let [msg (reader/read-string (. e -data))]
                  (when-not (async/offer! ws-in msg)
                    (js/console.warn "ws-in failed" msg)))
                (catch :default ex
                  (js/console.warn "failed to read" e ex))))))

    (. ws (addEventListener "open"
            (fn [e]
              (go (loop []
                    (when-some [msg (<! ws-out)]
                      (js/console.log "ws-out" msg)
                      (.send ws (pr-str msg))
                      (recur))))

              (rd/render (ui @state-ref) dom-target)

              (add-watch state-ref
                ::render
                (fn [_ _ new-val old-val]
                  (rd/render (ui new-val) dom-target)))

              (ws-loop)

              (js/console.log "ws connect" e)
              )))

    (. ws (addEventListener "error"
            (fn [e]
              (js/console.warn "ws error" e))))

    (. ws (addEventListener "close"
            (fn [e]
              (async/close! ws-out)
              (async/close! ws-in))))))

(defn ^:dev/before-load stop []
  (remove-watch state-ref ::render)
  (rd/unmountComponentAtNode (dom/by-id "root"))
  (when-let [ws (:ws @state-ref)]
    (.close ws)
    ))

(defn ^:export init []
  (js/console.log "init repl")
  (start))

(ns-ready)