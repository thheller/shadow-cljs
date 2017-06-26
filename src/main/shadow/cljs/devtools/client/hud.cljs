(ns shadow.cljs.devtools.client.hud
  (:require [shadow.dom :as dom]
            [shadow.cljs.devtools.client.env :as env]
            [shadow.cljs.devtools.client.browser :as browser]
            [cljs.tools.reader :as reader]
            [goog.string.format]
            [goog.string :refer (format)]
            [clojure.string :as str]))

(defonce socket-ref (volatile! nil))

(defonce dom-ref (volatile! nil))

(defn hud-hide []
  (when-some [d @dom-ref]
    (dom/remove d)
    (vreset! dom-ref nil)))

(def source-line-styles
  {:padding "0"
   :margin "0"})

(def source-highlight-styles
  (assoc source-line-styles
         :font-weight "bold"))

(defn source-line-html
  [start-idx lines styles]
  (->> (for [[idx text] (map-indexed vector lines)]
         [:pre {:style styles} (format "%4d | %s" (+ 1 idx start-idx) text)])))

(def sep-length 80)

(defn sep-line
  ([]
   (sep-line "" 0))
  ([label offset]
   (let [sep-len (js/Math.max sep-length offset)
         len (count label)

         sep
         (fn [c]
           (->> (repeat c "-")
                (str/join "")))]
     (str (sep offset) label (sep (- sep-len (+ offset len)))))))

(defn html-for-warning [{:keys [source-name msg file line column source-excerpt] :as warning}]
  [:div {:style {:border "2px solid #ccc"
                 :background-color "#fadb64"
                 :padding "10px"
                 :margin-bottom "10px"}}

   [:div {:style {:line-height "16px"
                  :font-size "1.2em"
                  :font-weight "bold"}}
    (str "WARNING in " source-name)]

   (when source-excerpt
     (let [{:keys [start-idx before line after]} source-excerpt]
       [:div {:style {:padding "10px 0"}}
        (source-line-html start-idx before source-line-styles)
        (source-line-html (+ start-idx (count before)) [line] source-highlight-styles)
        (let [arrow-idx (+ 6 (or column 1))]
          [:pre {:style source-highlight-styles} (sep-line "^" arrow-idx)])
        [:div {:style {:font-weight "bold" :font-size "1.2em" :padding "10px 0"}} msg]
        [:pre {:style source-highlight-styles} (sep-line)]
        (source-line-html (+ start-idx (count before) 1) after source-line-styles)]
       ))])

(defn maybe-display-hud [{:keys [info] :as msg}]
  (let [{:keys [sources]}
        info

        sources-with-warnings
        (->> sources
             (filter #(seq (:warnings %)))
             (into []))]

    (when (seq sources-with-warnings)
      (let [el
            (dom/append
              [:div#shadow-hud__container
               {:style {:position "absolute"
                        :left "0px"
                        :bottom "0px"
                        :right "0px"
                        :padding "10px 10px 0 10px"
                        :overflow "auto"
                        :font-family "monospace"
                        :font-size "12px"}}
               (for [{:keys [warnings] :as src} sources-with-warnings
                     warning warnings]
                 (html-for-warning warning))])]
        (vreset! dom-ref el)))))

(defn handle-message [msg]
  (case (:type msg)
    :build-complete
    (maybe-display-hud msg)

    :build-init
    (maybe-display-hud msg)

    :build-start
    (hud-hide)

    ;; default
    :ignored))

(defn ws-connect []
  (let [socket
        (-> (env/ws-listener-url)
            (js/WebSocket.))]

    (vreset! socket-ref socket)

    (set! (.-onmessage socket)
      (fn [e]
        (env/process-ws-msg e handle-message)))

    (set! (.-onopen socket)
      (fn [e]
        ;; do something on connect
        ))

    (set! (.-onclose socket)
      (fn [e]
        ;; cleanup on close
        (reset! socket-ref nil)
        ))

    (set! (.-onerror socket)
      (fn [e]
        ))
    ))

(when ^boolean env/enabled
  ;; disconnect an already connected socket, happens if this file is reloaded
  ;; pretty much only for me while working on this file
  (when-let [s @socket-ref]
    (set! (.-onclose s) (fn [e]))
    (.close s))

  (hud-hide)
  (ws-connect))
