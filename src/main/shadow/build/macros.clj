(ns shadow.build.macros
  (:require [cljs.env :as env]
            [cljs.analyzer :as ana]
            [shadow.build.cljs-bridge :as cljs-bridge]
            [clojure.java.io :as io]
            [shadow.cljs.util :as util]
            [shadow.build.classpath :as cp])
  (:import (clojure.lang Namespace)
           (java.net URL)))

;; this is a global since required macros are global as well
;; once a macro is "activated" by requiring it from a build it
;; may be reloaded when the .clj file is modified
;; since we can't isolate macros they will be reloaded for all builds
;; so it might affect builds without autobuild, can't do anything
;; about that
;; its a map of {symbol timestamp-it-was-required}
(def active-macros-ref (atom {}))

(defn is-macro?
  ([ns sym]
   (is-macro? (symbol (str ns) (str sym))))
  ([fqn]
   (try
     (when-let [the-var (find-var fqn)]
       (.isMacro the-var))
     (catch Exception e
       false))))

(defn find-macros-in-ns
  [name]
  (->> (ns-publics name)
       (reduce-kv
         (fn [m var-name the-var]
           (if (.isMacro ^clojure.lang.Var the-var)
             (let [macro-meta
                   (meta the-var)

                   macro-info
                   (let [ns (.getName ^Namespace (:ns macro-meta))]
                     (assoc macro-meta
                       :ns ns
                       :name (symbol (str ns) (str var-name))))]
               (assoc m var-name macro-info))
             m))
         {})))

(def require-lock (Object.))

(defn load-macros
  [{:keys [name require-macros use-macros] :as ns-info}]
  (if (= 'cljs.core name)
    ns-info
    (let [macro-namespaces
          (-> #{}
              (into (vals require-macros))
              (into (vals use-macros)))]

      (binding [ana/*cljs-ns* name]
        (locking require-lock
          (doseq [macro-ns macro-namespaces]
            ;; reloading is handled somewhere else
            (when-not (contains? @active-macros-ref macro-ns)

              ;; activate even if the require fails
              ;; so they are reloaded on change
              (swap! active-macros-ref assoc macro-ns (System/currentTimeMillis))

              (try
                (require macro-ns)
                (catch Exception e
                  (throw (ex-info
                           (format "failed to require macro-ns \"%s\", it was required by \"%s\"" macro-ns name)
                           {:tag ::macro-load
                            :macro-ns macro-ns
                            :ns-info ns-info}
                           e))))))))

      (if (contains? macro-namespaces name)
        (let [macros (find-macros-in-ns name)]
          (assoc ns-info :macros macros))
        ns-info))))

(defn infer-macro-require
  "infer (:require [some-ns]) that some-ns may come with macros
   must be used after load-macros"
  [{:keys [requires] :as ns-info}]
  (reduce
    (fn [ast [used-name used-ns]]
      (let [macros (get-in @env/*compiler* [::ana/namespaces used-ns :macros])]
        (if (nil? macros)
          ast
          (update-in ast [:require-macros] assoc used-name used-ns)
          )))
    ns-info
    requires))

(defn infer-macro-use
  "infer (:require [some-ns :refer (something)]) that something might be a macro
   must be used after load-macros"
  [{:keys [uses] :as ns-info}]
  (reduce
    (fn [ast [used-name used-ns]]
      (let [macros (get-in @env/*compiler* [::ana/namespaces used-ns :macros])]
        (if (or (nil? macros)
                (not (contains? macros used-name)))
          ast
          (update-in ast [:use-macros] assoc used-name used-ns)
          )))
    ns-info
    uses))

(defn infer-renames-for-macros
  [{:keys [renames] :as ns-info}]
  (reduce-kv
    (fn [ns-info rename-to source-sym]
      (if-not (is-macro? source-sym)
        ns-info
        (-> ns-info
            ;; remove the :rename if it is only a macro and not a cljs var
            (cond->
              (not (cljs-bridge/ana-is-cljs-def? source-sym))
              (update :renames dissoc rename-to))

            (update :rename-macros assoc rename-to source-sym))))
    ns-info
    renames))

(defn macros-used-by-ids [state source-ids]
  (->> source-ids
       (map #(get-in state [:sources %]))
       (filter #(seq (:macro-requires %)))
       (reduce (fn [macro-info {:keys [macro-requires id]}]
                 (reduce (fn [macro-info macro-ns]
                           (update-in macro-info [macro-ns] util/set-conj id))
                   macro-info
                   macro-requires))
         {})
       (map (fn [[macro-ns used-by]]
              (let [name (str (util/ns->path macro-ns) ".clj")
                    url (io/resource name)
                    ;; FIXME: clean this up, must look for .clj and .cljc
                    [name url]
                    (if url
                      [name url]
                      (let [name (str name "c")]
                        [name (io/resource name)]))]

                #_(when-not url (util/log-warning logger (format "Macro namespace: %s not found, required by %s" macro-ns used-by)))
                {:ns macro-ns
                 :used-by used-by
                 :name name
                 :url url})))
       ;; always get last modified for macro source
       (map (fn [{:keys [url] :as info}]
              (if (nil? url)
                info
                (let [con (.openConnection url)]
                  (assoc info :cache-key (.getLastModified con)))
                )))
       ;; get file (if not in jar)
       (map (fn [{:keys [^URL url] :as info}]
              (if (nil? url)
                info
                (if (not= "file" (.getProtocol url))
                  info
                  (let [file (io/file (.getPath url))]
                    (assoc info :file file))))))
       (into [])))
