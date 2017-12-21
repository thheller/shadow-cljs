(ns shadow.undertow.impl
  (:require [clojure.java.io :as io])
  (:import (io.undertow.util HeaderMap HeaderValues Headers HttpString)
           (io.undertow.server HttpServerExchange)
           (java.nio ByteBuffer)
           (io.undertow.io Sender)
           (java.io InputStream File)
           (io.undertow.server.handlers ResponseCodeHandler)))

;; vaguely copied from
;; https://github.com/piranha/ring-undertow-adapter/blob/master/src/ring/adapter/undertow.clj
;; unfortunately those fns are private :(
;; changes:
;; get-headers with multiple vals would str/join them, I want a vector

(defn get-headers
  [^HeaderMap header-map]
  (persistent!
    (reduce
      (fn [headers ^HeaderValues entry]
        (let [k (.. entry getHeaderName toString toLowerCase)
              val (if (> (.size entry) 1)
                    (into [] (iterator-seq (.iterator entry)))
                    (.get entry 0))]
          (assoc! headers k val)))
      (transient {})
      header-map)))

(defn exchange->ring
  [^HttpServerExchange exchange]
  (let [headers (.getRequestHeaders exchange)
        ctype (.getFirst headers Headers/CONTENT_TYPE)
        cenc (or (when ctype
                   (Headers/extractTokenFromHeader ctype "charset"))
                 "UTF-8")
        method (-> exchange (.getRequestMethod) (.toString) (.toLowerCase) (keyword))]

    (-> {::exchange exchange
         :server-port (-> exchange (.getDestinationAddress) (.getPort))
         :server-name (-> exchange (.getHostName))
         :remote-addr (-> exchange (.getSourceAddress) (.getAddress) (.getHostAddress))
         :uri (-> exchange (.getRequestURI))
         :query-string (-> exchange (.getQueryString))
         :scheme (-> exchange (.getRequestScheme) (.toString) (.toLowerCase) (keyword))
         :request-method method
         :headers (-> exchange (.getRequestHeaders) (get-headers))}
        (cond->
          (not (contains? #{:get :options} method))
          (assoc
            :body (.getInputStream exchange)
            :content-type ctype
            :content-length (-> exchange (.getRequestContentLength))
            :character-encoding cenc
            )))))

(defn set-headers
  [^HeaderMap header-map headers]
  (reduce-kv
    (fn [^HeaderMap header-map ^String key val-or-vals]
      (let [key (HttpString. key)]
        (if (string? val-or-vals)
          (.put header-map key ^String val-or-vals)
          (.putAll header-map key val-or-vals)))
      header-map)
    header-map
    headers))

(defn ^ByteBuffer str-to-bb
  [^String s]
  (ByteBuffer/wrap (.getBytes s "utf-8")))

(defprotocol RespondBody
  (respond [_ ^HttpServerExchange exchange]))

(extend-protocol RespondBody
  String
  (respond [body ^HttpServerExchange exchange]
    (.send ^Sender (.getResponseSender exchange) body))

  InputStream
  (respond [body ^HttpServerExchange exchange]
    (with-open [^InputStream b body]
      (io/copy b (.getOutputStream exchange))))

  File
  (respond [f exchange]
    (respond (io/input-stream f) exchange))

  clojure.lang.ISeq
  (respond [coll ^HttpServerExchange exchange]
    (reduce
      (fn [^Sender sender i]
        (.send sender (str-to-bb i))
        sender)
      (.getResponseSender exchange)
      coll))

  nil
  (respond [_ exc]))

(defn ring->exchange
  [^HttpServerExchange exchange req {:keys [status headers body]}]
  (when status
    (.setResponseCode exchange status))
  (set-headers (.getResponseHeaders exchange) headers)
  (respond body exchange))

;;
;; end copy
;;

(def handle-404 ResponseCodeHandler/HANDLE_404)
