(ns shadow.insight.parser
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [rewrite-clj.node :as n]
    [rewrite-clj.parser :as p])
  (:import
    [java.io BufferedReader StringReader]
    [org.commonmark.parser Parser]
    [org.commonmark.renderer.html HtmlRenderer]
    [shadow.insight MarkdownDatafier]))

(defn md->data [text]
  (let [doc
        (-> (Parser/builder)
            (.build)
            (.parse text))]

    (MarkdownDatafier/convert doc)))

(defn md->html [text]
  (let [doc
        (-> (Parser/builder)
            (.build)
            (.parse text))]

    (-> (HtmlRenderer/builder)
        (.build)
        (.render doc))))

(defn- push-description [{:keys [description] :as state}]
  (if (str/blank? description)
    state
    (let [html
          (->> (line-seq (BufferedReader. (StringReader. (str/trim description))))
               (map #(str/replace % #"^(\s*)(;+)(\s*)" ""))
               (str/join "\n")
               (md->html))]

      (-> state
          ;; location metadata is useless for comments? just gets the data of the next form
          (update :blocks conj {:type :text :html html :source description})
          (assoc :description "")))))

(defn loc-meta [node]
  (let [{:keys [row col end-row end-col]} (meta node)]
    {:line row
     :column col
     :end-line end-row
     :end-column end-col}))

(defn set-index [blocks]
  (reduce-kv
    (fn [blocks idx block]
      (assoc blocks idx (assoc block :idx idx)))
    blocks blocks))

(defn parse [content]
  (let [root
        (p/parse-string-all content)

        contents
        (n/children root)

        parsed
        (-> (reduce
              (fn [state node]
                (cond
                  ;; comments or whitespace lines
                  (n/whitespace-or-comment? node)
                  (update state :description str (n/string node))

                  #_ uneval
                  (= :uneval (n/tag node))
                  (-> state
                      (push-description)
                      (update :blocks conj (merge (loc-meta node) {:type :comment :source (n/string node)})))

                  ;; code to be eval'd
                  :else
                  (-> state
                      (push-description)
                      (update :blocks conj (merge (loc-meta node) {:type :expr :source (n/string node)})))
                  ))
              {:blocks []
               :description ""}
              contents)
            (push-description)
            (update :blocks set-index))]

    (dissoc parsed :description)))