(ns shadow.cljs.devtools.server.ring-gzip
  {:doc "Ring gzip compression."
   :source "https://github.com/bertrandk/ring-gzip/blob/master/src/ring/middleware/gzip.clj"}
  (:require [clojure.java.io :as io])
  (:import (java.io InputStream
                    Closeable
                    File
                    PipedInputStream
                    PipedOutputStream)
           (java.util.zip GZIPOutputStream)))

(defn- accepts-gzip?
  [req]
  (if-let [accepts (get-in req [:headers "accept-encoding"])]
    ;; Be aggressive in supporting clients with mangled headers (due to
    ;; proxies, av software, buggy browsers, etc...)
    (re-seq
      #"(gzip\s*,?\s*(gzip|deflate)?|X{4,13}|~{4,13}|\-{4,13})"
      accepts)))

;; Set Vary to make sure proxies don't deliver the wrong content.
(defn- set-response-headers
  [headers]
  (if-let [vary (or (get headers "vary") (get headers "Vary"))]
    (-> headers
        (assoc "Vary" (str vary ", Accept-Encoding"))
        (assoc "Content-Encoding" "gzip")
        (dissoc "Content-Length" "content-length")
        (dissoc "vary"))
    (-> headers
        (assoc "Vary" "Accept-Encoding")
        (assoc "Content-Encoding" "gzip")
        (dissoc "Content-Length" "content-length"))))

(def ^:private supported-status? #{200, 201, 202, 203, 204, 205 403, 404})

(defn- unencoded-type?
  [headers]
  ;; thheller: fix NPE when no headers are set
  (if (or (get headers "Content-Encoding") (get headers "content-encoding"))
    false
    true))

(defn- supported-type?
  [resp]
  (let [{:keys [headers body]} resp]
    (or (string? body)
        (seq? body)
        (instance? InputStream body)
        (and (instance? File body)
             (re-seq #"(?i)\.(htm|html|css|js|json|xml)" (pr-str body))))))

(def ^:private min-length 859)

(defn- supported-size?
  [{:keys [body] :as resp}]
  (cond
    (string? body) (> (count body) min-length)
    (seq? body) (> (count body) min-length)
    (instance? File body) (> (.length ^File body) min-length)
    :else true))

(defn- supported-response?
  [{:keys [status headers] :as resp}]
  (and (supported-status? status)
       (unencoded-type? headers)
       (supported-type? resp)
       (supported-size? resp)))

(defn- compress-body
  [body]
  (let [p-in (PipedInputStream.)
        p-out (PipedOutputStream. p-in)]
    (future
      (with-open [out (GZIPOutputStream. p-out)]
        (if (seq? body)
          (doseq [string body] (io/copy (str string) out))
          (io/copy body out)))
      (when (instance? Closeable body)
        (.close ^Closeable body)))
    p-in))

(defn- gzip-response
  [resp]
  (-> resp
      (update-in [:headers] set-response-headers)
      (update-in [:body] compress-body)))

(defn wrap-gzip
  "Middleware that compresses responses with gzip for supported user-agents."
  [handler]
  (fn [req]
    (if (accepts-gzip? req)
      (let [resp (handler req)]
        (if (supported-response? resp)
          (gzip-response resp)
          resp))
      (handler req))))
