(ns shadow.lang.protocol.text-document
  (:require [shadow.lang.protocol :as p]
            [shadow.lang.classpath :as classpath]
            [shadow.devtools.server.explorer :as cljs-explorer]
            [clojure.core.async :as async :refer (go)]
            [clojure.string :as str]))

(defmulti warning->diag :warning)

(defmethod warning->diag :undeclared-var
  [{:keys [line column extra msg]}]
  (let [{:keys [prefix suffix]} extra
        offset (-> suffix str count)]
    ;; FIXME: fun times ... CLJS is 1-based for columns, protocol is 0-based, monaco editor is 1-based
    {:source "CLJS"
     :message msg
     :severity 1 ;; 1 is red, 2 is green
     :range {:start {:line line :character (dec column)}
             :end {:line line :character (+ column offset -1)}}}
    ))

;; FIXME: this should probably use :text from the notification
;; otherwise this is coupled to the filesystem which sucks
(defn get-diagnostics-for-uri!
  [{:keys [system] :as client-state} uri]
  (assert (str/starts-with? uri "file://"))
  (try
    (let [{:keys [cljs-explorer classpath]} system

          path
          (subs uri (count "file://"))

          [cp name :as x]
          (classpath/match-to-classpath classpath path)]

      ;; FIXME: special case for project.clj
      (cond
        (nil? x)
        (p/notify! client-state "window/logMessage" {:type 3 :message (format "%s not on classpath" path)})

        (str/ends-with? path ".clj")
        (p/notify! client-state "window/logMessage" {:type 3 :message (format "%s not supported yet" path)})

        (str/ends-with? path ".cljs")
        (let [{:keys [info] :as x}
              (cljs-explorer/get-source-info cljs-explorer name)]

          (p/notify! client-state "textDocument/publishDiagnostics"
            {:uri uri
             :diagnostics
             (->> (:warnings info)
                  (map warning->diag)
                  (into []))
             })
          )))
    (catch Exception e
      (prn [:e e]))))

(defmethod p/handle-cast "textDocument/didOpen"
  [client-state _ params]
  (let [uri (get-in params [:textDocument :uri])]
    (go (get-diagnostics-for-uri! client-state uri))
    (update client-state :open-files conj uri)))

(defmethod p/handle-cast "textDocument/didClose"
  [client-state _ params]
  (let [uri (get-in params [:textDocument :uri])]
    (update client-state :open-files disj uri)))

;; didChange is a bit too chatty to recalc diagnostics for every change
;; FIXME: do it incrementally like the protocol intended
(defmethod p/handle-cast "textDocument/didChange"
  [client-state _ params]
  client-state)

(defmethod p/handle-cast "textDocument/didSave"
  [client-state _ params]
  (let [uri (get-in params [:textDocument :uri])]
    (go (get-diagnostics-for-uri! client-state uri))
    client-state))
