(ns shadow.cljs.devtools.client.hud
  (:require
    [shadow.dom :as dom]
    [shadow.xhr :as xhr]
    [shadow.animate :as anim]
    [shadow.cljs.devtools.client.env :as env]
    [cljs.core.async :as async :refer (go)]
    [goog.string.format]
    [goog.string :refer (format)]
    [clojure.string :as str]))

(goog-define show-progress true)

(goog-define show-warnings true)

(goog-define show-errors true)

(defn open-file [file line column]
  (js/console.log "opening file" file line column)

  (let [req
        (xhr/chan :POST
          (str (env/get-url-base) "/api/open-file")
          {:file file
           :line line
           :column column}
          {:with-credentials false
           :body-only true})]
    (go (when-some [{:keys [exit] :as result} (<! req)]
          (when-not (zero? exit)
            (js/console.warn "file open failed" result))
          ))))

(defn dom-insert
  ([node]
   (dom-insert js/document.body node))
  ([where node]
   (let [el (dom/dom-node node)
         id (.-id el)]
     (assert (seq id))
     (when-some [x (dom/by-id id)]
       (dom/remove x))
     (dom/append where el)
     )))

(def hud-id "shadow-hud-container")

(def load-id "shadow-hud-loading-container")

(def logo-svg
  (delay
    (let [s-path
          "M247.183941,141.416413 C247.183941,74.7839971 148.383423,78.9723529 148.383423,141.416413 C148.383423,203.860473 265.090698,171.864644 265.090698,248.900057 C265.090698,325.93547 135,325.851749 135,251.708304"]
      (dom/svg
        {:id "shadow-cljs-logo"
         :version "1.1"
         :viewBox "0 0 400 400"
         :style {:display "block"}
         :height "60px"
         :width "60px"}
        [:title "shadow-cljs"]
        [:defs
         [:mask#shadow-cljs-logo-mask {:fill "#fff"}
          [:circle {:r "200" :cy "200" :cx "200"}]]]
        [:g
         {:fill-rule "evenodd"
          :fill "none"
          :stroke-width "0"
          :stroke "none"
          :mask "url(#shadow-cljs-logo-mask)"}

         [:g.circles
          [:circle.blue {:r "200" :cy "200" :cx "200" :fill "#4F80DF"}]
          [:circle.light-blue {:r "71.5" :cy "200" :cx "370" :fill "#89B4FF"}]
          [:circle.dark-green {:r "180" :cy "360" :cx "60" :fill "#40B400"}]
          [:circle.light-green {:r "129" :cy "320" :cx "280" :fill "#76E013"}]
          [:animateTransform
           {:attributeType "xml"
            :attributeName "transform"
            :type "rotate"
            :from "0 200 200"
            :to "360 200 200"
            :dur "3s"
            :repeatCount "indefinite"}]]

         ;; S shadow
         [:g {:transform "translate(10,10)"}
          [:path
           {:stroke-linecap "square"
            :stroke-width "16"
            :stroke "#aaa"
            :d s-path}]]
         ;; S
         [:path
          {:stroke-linecap "square"
           :stroke-width "16"
           :stroke "#FFFFFF"
           :d s-path}]
         ]))))

(defn load-start []
  (when show-progress
    (dom-insert
      [:div {:id load-id
             :style {:position "fixed"
                     :pointer-events "none"
                     :left "0px"
                     :bottom "20px"
                     :z-index "10000"}}
       [:div {:style {:background "#eee"
                      :border-top-right-radius "40px"
                      :border-bottom-right-radius "40px"
                      :box-shadow "2px 2px 10px #aaa"
                      :padding "10px"}}
        @logo-svg]])))

(defn load-end-success []
  (when show-progress
    (when-some [container-el (dom/by-id load-id)]
      (let [el (.-firstChild container-el)]
        (anim/start 500 {el (anim/transition :background "#eee" "#40B400" "ease-out")})
        (go (<! (async/timeout 250))
            (<! (anim/start 250 {el (anim/transition :opacity "1" "0" "ease-in")}))
            (dom/remove container-el)

            )))))

(defn load-end []
  (when-some [el (dom/by-id load-id)]
    (dom/remove el)))

(defn hud-hide []
  (when-some [d (dom/by-id hud-id)]
    (dom/remove d)))

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

(defn file-link [{:keys [resource-name file line column] :as warning}]
  (if-not file
    [:span resource-name]

    [:span {:style {:text-decoration "underline"
                    :color "blue"
                    :cursor "pointer"}
            :on {:click (fn [e]
                          (dom/ev-stop e)
                          (open-file file line column)
                          )}}

     resource-name]))

(defn html-for-warning [{:keys [resource-name msg file line column source-excerpt] :as warning}]
  [:div {:style {:border "2px solid #ccc"

                 :margin-bottom "10px"}}

   [:div {:style {:line-height "16px"
                  :background-color "#fadb64"
                  :padding "10px"
                  :font-size "1.2em"
                  :font-weight "bold"}}
    [:span "WARNING in "]
    (file-link warning)]

   (when source-excerpt
     (let [{:keys [start-idx before line after]} source-excerpt]
       [:div {:style {:padding "10px 10px"
                      :background-color "#fff"
                      :border-top "2px solid #ccc"}}
        (source-line-html start-idx before source-line-styles)
        (source-line-html (+ start-idx (count before)) [line] source-highlight-styles)
        (let [arrow-idx (+ 6 (or column 1))]
          [:pre {:style source-highlight-styles} (sep-line "^" arrow-idx)])
        [:div {:style {:font-weight "bold" :font-size "1.2em" :padding "10px 0"}} msg]
        [:pre {:style source-highlight-styles} (sep-line)]
        (source-line-html (+ start-idx (count before) 1) after source-line-styles)]
       ))])

(defn hud-warnings [{:keys [type info] :as msg}]
  (let [{:keys [sources]}
        info

        sources-with-warnings
        (->> sources
             (remove :from-jar)
             (filter #(seq (:warnings %)))
             (into []))]

    (when (seq sources-with-warnings)
      ;; TODO: fancy transition from logo to warnings
      (load-end)
      (when show-warnings
        (dom-insert
          [:div
           {:id hud-id
            :style {:position "fixed"
                    :z-index "10000"
                    :left "0px"
                    :bottom "0px"
                    :right "0px"
                    :display "flex"
                    :flex-direction "column"
                    :color "#000"
                    :font-family "monospace"
                    :font-size "12px"}}
           [:div {:style {:align-self "flex-end"
                          :background "#fff"
                          :border "2px solid #ccc"
                          :border-bottom "0px"
                          :cursor "pointer"
                          :padding "6px"
                          :margin-bottom "-2px"
                          :z-index "10000"}
                  :on {:click hud-hide}}
            "Close"]
           [:div {:style {:background "#fff"
                          :border-top "2px solid #ccc"
                          :flex "1"
                          :max-height "300px"
                          :padding "10px 10px 0"
                          :overflow "auto"}}
            (for [{:keys [warnings]} sources-with-warnings
                  warning warnings]
              (html-for-warning warning))]])))))

(defn hud-error [{:keys [report] :as msg}]
  (when show-errors
    (dom-insert
      [:div
       {:id hud-id
        :style {:position "fixed"
                :left "0px"
                :top "0px"
                :bottom "0px"
                :right "0px"
                :color "#000"
                :background-color "#fff"
                :border "5px solid red"
                :z-index "10000"
                :padding "20px"
                :overflow "auto"
                :font-family "monospace"
                :font-size "12px"}}
       [:div {:style "color: red; margin-bottom: 10px; font-size: 2em;"} "Compilation failed!"]
       [:pre report]])))

(def connection-error-id "shadow-connection-error")

(defn connection-error-clear! []
  (when-some [x (dom/by-id connection-error-id)]
    (dom/remove x)))

(defn connection-error [msg]
  (when show-errors
    (dom-insert
      [:div {:id connection-error-id
             :style {:position "fixed"
                     :pointer-events "none"
                     :left "0px"
                     :bottom "20px"
                     :z-index "10000"}}
       [:div {:style {:background "#c00"
                      :border-top-right-radius "40px"
                      :border-bottom-right-radius "40px"
                      :box-shadow "2px 2px 10px #aaa"
                      :padding "10px"
                      :font-family "monospace"
                      :font-size "14px"
                      :font-weight "bold"
                      :color "#fff"}}
        (str "shadow-cljs - " msg)
        ]])))

(defn load-failure [error task remaining]
  (load-end)
  (js/console.error "reload-failed" error)
  (connection-error (str "[reload failed] " (.-message error))))