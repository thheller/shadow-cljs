(ns shadow.cljs.graaljs
  (:require [clojure.java.io :as io])
  (:import [org.graalvm.polyglot PolyglotException$StackFrame PolyglotException]
           [com.google.debugging.sourcemap SourceMapConsumerV3]))


(defn frame->data [^PolyglotException$StackFrame frame]
  (let [loc (.getSourceLocation frame)
        src (.getSource loc)]
    (cond->
      {:root-name (.getRootName frame)
       :path (.getPath src)}

      (.hasLines loc)
      (assoc :line (.getStartLine loc)
        :end-line (.getEndLine loc))

      (.hasColumns loc)
      (assoc :column (.getStartColumn loc)
        :end-column (.getEndColumn loc))
      )))

(defn source-map [^PolyglotException e]
  (assert (instance? PolyglotException e))

  (let [src-maps (atom {})

        load-map
        (fn [path]
          (or (get @src-maps path)
              (let [sm-file (io/file (str path ".map"))]
                (when (.exists sm-file)
                  (let [sm-consumer (SourceMapConsumerV3.)]
                    (.parse sm-consumer (slurp sm-file))
                    sm-consumer)))))]

    (->> (.getPolyglotStackTrace e)
         (take-while #(.isGuestFrame %))
         (map frame->data)
         (map (fn [{:keys [path line column] :as frame}]
                (let [map (load-map path)]
                  (if-not (and map line column)
                    frame
                    (let [mapping (.getMappingForLine map line column)]
                      (assoc frame
                        :mapped-src (.getOriginalFile mapping)
                        :mapped-line (.getLineNumber mapping)
                        :mapped-column (.getColumnPosition mapping)))
                    ))))
         (vec))))
