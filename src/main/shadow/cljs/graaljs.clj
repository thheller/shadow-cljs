(ns shadow.cljs.graaljs
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [shadow.build.warnings :as warnings])
  (:import [org.graalvm.polyglot PolyglotException$StackFrame PolyglotException]
           [com.google.debugging.sourcemap SourceMapConsumerV3]))


(defn frame->data [^PolyglotException$StackFrame frame]
  (let [loc (.getSourceLocation frame)
        src (.getSource loc)
        path (.getPath src)]
    (cond->
      {:root-name (.getRootName frame)}

      (seq path)
      (assoc :path path)

      (.hasLines loc)
      (assoc :line (.getStartLine loc)
             ;; :end-line (.getEndLine loc)
             )

      (.hasColumns loc)
      (assoc :column (.getStartColumn loc)
             ;; :end-column (.getEndColumn loc)
             )
      )))

(defn add-source-excerpt [{:keys [mapped-src mapped-line mapped-column mapped-identifier] :as frame}]
  (if-not mapped-src
    frame
    (let [src (io/resource mapped-src)]
      (if-not src
        frame
        (assoc frame
          :source-excerpt
          (-> (slurp src)
              (warnings/get-source-excerpts
                [{:line mapped-line
                  :column mapped-column}])
              (first)))))))

(def sep-length 80)

(defn sep-line
  ([]
   (sep-line "" 0))
  ([label offset]
   (let [sep-len (Math/max sep-length offset)
         len (count label)

         sep
         (fn [c]
           (->> (repeat c "-")
                (str/join "")))]
     (str (sep offset) label (sep (- sep-len (+ offset len)))))))

(defn print-source-lines
  [start-idx lines]
  (->> (for [[idx text] (map-indexed vector lines)]
         (format "%4d | %s" (+ 1 idx start-idx) text))
       (str/join "\n")
       (println)))

(defn print-source-excerpt
  [{:keys [start-idx before line after] :as source-excerpt}]
  (print-source-lines start-idx before)
  (print-source-lines (+ start-idx (count before)) [line])

  ;; columns seem rather weird and don't really point anywhere useful sometimes
  ;; just "point up" to the source line which seems to be accurate
  (println (->> (repeat sep-length "^")
                (str/join "")))

  (when (seq after)
    (print-source-lines (+ start-idx (count before) 1) after)))

(defn ex-print [^PolyglotException e]
  (assert (instance? PolyglotException e))

  (let [src-maps (atom {})

        load-map
        (fn [path]
          (or (get @src-maps path)
              (let [sm-file (io/file (str path ".map"))]
                (when (.exists sm-file)
                  (let [sm-consumer (SourceMapConsumerV3.)]
                    (.parse sm-consumer (slurp sm-file))
                    sm-consumer)))))

        frames
        (->> (.getPolyglotStackTrace e)
             (take-while #(.isGuestFrame %))
             (map frame->data)
             (map (fn [{:keys [path line column] :as frame}]
                    (let [map (and path (load-map path))]
                      (if-not (and map line column)
                        frame
                        (let [mapping (.getMappingForLine map line column)]
                          (if-not mapping
                            frame
                            (-> frame
                                (assoc :mapped-src (.getOriginalFile mapping)
                                       :mapped-line (.getLineNumber mapping)
                                       :mapped-column (.getColumnPosition mapping))
                                (cond->
                                  (.hasIdentifier mapping)
                                  (assoc :mapped-identifier (.getIdentifier mapping))))))))))
             (map add-source-excerpt)
             (vec))]

    (println (sep-line))
    (println (str "JS Exception: " (.getMessage e)))
    (println (sep-line))
    (doseq [{:keys [path mapped-src mapped-line mapped-column mapped-identifier source-excerpt] :as frame} frames]
      (cond
        (not path)
        (println (str ">>>>>> Unmapped location: " (pr-str frame)))

        (not mapped-src)
        (println (str ">>>>>> Unmapped location: " (pr-str frame)))

        source-excerpt
        (do (println (str ">>>>>> " mapped-src " line:" mapped-line " column:" mapped-column))
            (println (sep-line))
            (print-source-excerpt source-excerpt)
            (println (sep-line))
            (println))

        :else
        (println (str ">>>>>> " mapped-src " line:" mapped-line " column:" mapped-column))
        ))

    (println (sep-line))))