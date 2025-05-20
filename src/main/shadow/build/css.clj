(ns shadow.build.css
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [shadow.build :as-alias build]
            [shadow.build.data :as data])
  (:import [java.io File]))

(defn extract-hook
  {::build/stage :flush}
  ([build-state]
   (extract-hook build-state {}))
  ([{::build/keys [mode] :as build-state} opts]
   (reduce
     (fn [build-state {:keys [module-id sources] :as x}]
       (let [asset-files
             (->> sources
                  (map (fn [src-id]
                         (get-in build-state [:sources src-id])))
                  (filter :asset)
                  (map :file)
                  (filter #(str/ends-with? (.getName ^File %) ".css"))
                  (vec))]

         (when (seq asset-files)
           (let [css
                 (->> asset-files
                      (map (fn [file]
                             (str (when (= :dev mode)
                                    (str "/** -- " (.getAbsolutePath file) " -- */\n"))
                                  (slurp file))))
                      (str/join "\n"))

                 css-name
                 (str (name module-id) ".css")

                 out
                 (if-some [out-dir (:output-dir opts)]
                   (io/file out-dir css-name)
                   (data/output-file build-state css-name))]

             (spit out css))))

       build-state)
     build-state
     (:build-modules build-state))))
