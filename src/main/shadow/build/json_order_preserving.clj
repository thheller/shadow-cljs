;; Copyright (c) Stuart Sierra, 2012. All rights reserved.  The use
;; and distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution.  By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license.  You must not
;; remove this notice, or any other, from this software.

(ns ^{:author "Stuart Sierra"
      :doc "JavaScript Object Notation (JSON) parser/generator.
  See http://www.json.org/"}
  shadow.build.json-order-preserving
  (:refer-clojure :exclude (read))
  (:import (java.io PushbackReader
                    StringReader EOFException)
           [clojure.lang PersistentArrayMap]
           [java.util LinkedList]))

;; thheller:
;; this is a modified clojure.data.json taken from github, matching version 2.4.0
;; https://github.com/clojure/data.json/blob/9f1c9ccf3fd3e5a39cfb7289d3d456e842ddf442/src/main/clojure/clojure/data/json.clj
;; deleted all write/print related function and modified read-object to read objects in a way that preserves ordering of keys.
;; the only purpose for this is reading package.json where ordering is significant in certain fields such as "exports"
;; it does this by enforcing all objects to be constructed as array-maps regardless of size

;; the data is only meant to be read and used as is. modifying such maps in any way will result in that ordering getting lost
;; if they exceed the hashmap threshold. also removed key-fn/value-fn support since we always want package.json as is.

;; DO NOT USE THIS FOR ANYTHING OTHER THAN THE STATED PURPOSE!

;;; JSON READER

(set! *warn-on-reflection* true)

(defn- default-value-fn [k v] v)

(declare -read)

(defmacro ^:private codepoint [c]
  (int c))

(defn- codepoint-clause [[test result]]
  (cond (list? test)
        [(map int test) result]
        (= test :whitespace)
        ['(9 10 13 32) result]
        (= test :js-separators)
        ['(16r2028 16r2029) result]
        :else
        [(int test) result]))

(defmacro ^:private codepoint-case [e & clauses]
  `(case ~e
     ~@(mapcat codepoint-clause (partition 2 clauses))
     ~@(when (odd? (count clauses))
         [(last clauses)])))

(defn- read-hex-char [^PushbackReader stream]
  ;; Expects to be called with the head of the stream AFTER the
  ;; initial "\u".  Reads the next four characters from the stream.
  (let [a (.read stream)
        b (.read stream)
        c (.read stream)
        d (.read stream)]
    (when (or (neg? a) (neg? b) (neg? c) (neg? d))
      (throw (EOFException.
               "JSON error (end-of-file inside Unicode character escape)")))
    (let [s (str (char a) (char b) (char c) (char d))]
      (char (Integer/parseInt s 16)))))

(defn- read-escaped-char [^PushbackReader stream]
  ;; Expects to be called with the head of the stream AFTER the
  ;; initial backslash.
  (let [c (.read stream)]
    (when (neg? c)
      (throw (EOFException. "JSON error (end-of-file inside escaped char)")))
    (codepoint-case c
      (\" \\ \/) (char c)
      \b \backspace
      \f \formfeed
      \n \newline
      \r \return
      \t \tab
      \u (read-hex-char stream))))

(defn- slow-read-string [^PushbackReader stream ^String already-read]
  (let [buffer (StringBuilder. already-read)]
    (loop []
      (let [c (.read stream)]
        (when (neg? c)
          (throw (EOFException. "JSON error (end-of-file inside string)")))
        (codepoint-case c
          \" (str buffer)
          \\ (do (.append buffer (read-escaped-char stream))
                 (recur))
          (do (.append buffer (char c))
              (recur)))))))

(defn- read-quoted-string [^PushbackReader stream]
  ;; Expects to be called with the head of the stream AFTER the
  ;; opening quotation mark.
  (let [buffer ^chars (char-array 64)
        read (.read stream buffer 0 64)
        end-index (unchecked-dec-int read)]
    (when (neg? read)
      (throw (EOFException. "JSON error (end-of-file inside string)")))
    (loop [i (int 0)]
      (let [c (int (aget buffer i))]
        (codepoint-case c
          \" (let [off (unchecked-inc-int i)
                   len (unchecked-subtract-int read off)]
               (.unread stream buffer off len)
               (String. buffer 0 i))
          \\ (let [off i
                   len (unchecked-subtract-int read off)]
               (.unread stream buffer off len)
               (slow-read-string stream (String. buffer 0 i)))
          (if (= i end-index)
            (do (.unread stream c)
                (slow-read-string stream (String. buffer 0 i)))
            (recur (unchecked-inc-int i))))))))

(defn- read-integer [^String string]
  (if (< (count string) 18)  ; definitely fits in a Long
    (Long/valueOf string)
    (or (try (Long/valueOf string)
             (catch NumberFormatException e nil))
        (bigint string))))

(defn- read-decimal [^String string bigdec?]
  (if bigdec?
    (bigdec string)
    (Double/valueOf string)))

(defn- read-number [^PushbackReader stream bigdec?]
  (let [buffer (StringBuilder.)
        decimal? (loop [stage :minus]
                   (let [c (.read stream)]
                     (case stage
                       :minus
                       (codepoint-case c
                         \-
                         (do (.append buffer (char c))
                             (recur :int-zero))
                         \0
                         (do (.append buffer (char c))
                             (recur :frac-point))
                         (\1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :int-digit))
                         (throw (Exception. "JSON error (invalid number literal)")))
                       ;; Number must either be a single 0 or 1-9 followed by 0-9
                       :int-zero
                       (codepoint-case c
                         \0
                         (do (.append buffer (char c))
                             (recur :frac-point))
                         (\1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :int-digit))
                         (throw (Exception. "JSON error (invalid number literal)")))
                       ;; at this point, there is at least one digit
                       :int-digit
                       (codepoint-case c
                         (\0 \1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :int-digit))
                         \.
                         (do (.append buffer (char c))
                             (recur :frac-first))
                         (\e \E)
                         (do (.append buffer (char c))
                             (recur :exp-symbol))
                         ;; early exit
                         :whitespace
                         (do (.unread stream c)
                             false)
                         (\, \] \} -1)
                         (do (.unread stream c)
                             false)
                         (throw (Exception. "JSON error (invalid number literal)")))
                       ;; previous character is a "0"
                       :frac-point
                       (codepoint-case c
                         \.
                         (do (.append buffer (char c))
                             (recur :frac-first))
                         (\e \E)
                         (do (.append buffer (char c))
                             (recur :exp-symbol))
                         ;; early exit
                         :whitespace
                         (do (.unread stream c)
                             false)
                         (\, \] \} -1)
                         (do (.unread stream c)
                             false)
                         ;; Disallow zero-padded numbers or invalid characters
                         (throw (Exception. "JSON error (invalid number literal)")))
                       ;; previous character is a "."
                       :frac-first
                       (codepoint-case c
                         (\0 \1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :frac-digit))
                         (throw (Exception. "JSON error (invalid number literal)")))
                       ;; any number of following digits
                       :frac-digit
                       (codepoint-case c
                         (\0 \1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :frac-digit))
                         (\e \E)
                         (do (.append buffer (char c))
                             (recur :exp-symbol))
                         ;; early exit
                         :whitespace
                         (do (.unread stream c)
                             true)
                         (\, \] \} -1)
                         (do (.unread stream c)
                             true)
                         (throw (Exception. "JSON error (invalid number literal)")))
                       ;; previous character is a "e" or "E"
                       :exp-symbol
                       (codepoint-case c
                         (\- \+)
                         (do (.append buffer (char c))
                             (recur :exp-first))
                         (\0 \1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :exp-digit)))
                       ;; previous character is a "-" or "+"
                       ;; must have at least one digit
                       :exp-first
                       (codepoint-case c
                         (\0 \1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :exp-digit))
                         (throw (Exception. "JSON error (invalid number literal)")))
                       ;; any number of following digits
                       :exp-digit
                       (codepoint-case c
                         (\0 \1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :exp-digit))
                         :whitespace
                         (do (.unread stream c)
                             true)
                         (\, \] \} -1)
                         (do (.unread stream c)
                             true)
                         (throw (Exception. "JSON error (invalid number literal)"))))))]
    (if decimal?
      (read-decimal (str buffer) bigdec?)
      (read-integer (str buffer)))))

(defn- next-token [^PushbackReader stream]
  (loop [c (.read stream)]
    (if (< 32 c)
      (int c)
      (codepoint-case (int c)
        :whitespace (recur (.read stream))
        -1 -1))))

(defn invalid-array-exception []
  (Exception. "JSON error (invalid array)"))

(defn- read-array* [^PushbackReader stream options]
  ;; Handles all array values after the first.
  (loop [result (transient [])]
    (let [r (conj! result (-read stream true nil options))]
      (codepoint-case (int (next-token stream))
        \] (persistent! r)
        \, (recur r)
        (throw (invalid-array-exception))))))

(defn- read-array [^PushbackReader stream options]
  ;; Expects to be called with the head of the stream AFTER the
  ;; opening bracket.
  ;; Only handles array value.
  (let [c (int (next-token stream))]
    (codepoint-case c
      \] []
      \, (throw (invalid-array-exception))
      (do (.unread stream c)
          (read-array* stream options)))))

(defn- read-key [^PushbackReader stream]
  (let [c (int (next-token stream))]
    (if (= c (codepoint \"))
      (let [key (read-quoted-string stream)]
        (if (= (codepoint \:) (int (next-token stream)))
          key
          (throw (Exception. "JSON error (missing `:` in object)"))))
      (if (= c (codepoint \}))
        nil
        (throw (Exception. (str "JSON error (non-string key in object), found `" (char c) "`, expected `\"`")))))))

(defn- read-object [^PushbackReader stream options]
  ;; Expects to be called with the head of the stream AFTER the
  ;; opening bracket.
  (let [l (LinkedList.)]
    (loop []
      (if-let [key (read-key stream)]
        (let [value (-read stream true nil options)]
          (.add l key)
          (.add l value)
          (codepoint-case (int (next-token stream))
            \, (recur)
            \} (PersistentArrayMap/createAsIfByAssoc (.toArray l))
            (throw (Exception. "JSON error (missing entry in object)"))))
        (if (zero? (.size l))
          {}
          (throw (Exception. "JSON error empty entry in object is not allowed")))))))

(defn- -read
  [^PushbackReader stream eof-error? eof-value options]
  (let [c (int (next-token stream))]
    (codepoint-case c
      ;; Read numbers
      (\- \0 \1 \2 \3 \4 \5 \6 \7 \8 \9)
      (do (.unread stream c)
          (read-number stream (:bigdec options)))

      ;; Read strings
      \" (read-quoted-string stream)

      ;; Read null as nil
      \n (if (and (= (codepoint \u) (.read stream))
                  (= (codepoint \l) (.read stream))
                  (= (codepoint \l) (.read stream)))
           nil
           (throw (Exception. "JSON error (expected null)")))

      ;; Read true
      \t (if (and (= (codepoint \r) (.read stream))
                  (= (codepoint \u) (.read stream))
                  (= (codepoint \e) (.read stream)))
           true
           (throw (Exception. "JSON error (expected true)")))

      ;; Read false
      \f (if (and (= (codepoint \a) (.read stream))
                  (= (codepoint \l) (.read stream))
                  (= (codepoint \s) (.read stream))
                  (= (codepoint \e) (.read stream)))
           false
           (throw (Exception. "JSON error (expected false)")))

      ;; Read JSON objects
      \{ (read-object stream options)

      ;; Read JSON arrays
      \[ (read-array stream options)

      (if (neg? c) ;; Handle end-of-stream
        (if eof-error?
          (throw (EOFException. "JSON error (end-of-file)"))
          eof-value)
        (throw (Exception.
                 (str "JSON error (unexpected character): " (char c))))))))

(def default-read-options {:bigdec false})
(defn read
  "Reads a single item of JSON data from a java.io.Reader. Options are
  key-value pairs, valid options are:

     :eof-error? boolean

        If true (default) will throw exception if the stream is empty.

     :eof-value Object

        Object to return if the stream is empty and eof-error? is
        false. Default is nil.

     :bigdec boolean

        If true use BigDecimal for decimal numbers instead of Double.
        Default is false."
  [reader & {:as options}]
  (let [{:keys [eof-error? eof-value]
         :or {eof-error? true}} options]
    (->> options
         (merge default-read-options)
         (-read (PushbackReader. reader 64) eof-error? eof-value))))

(defn read-str
  "Reads one JSON value from input String. Options are the same as for
  read."
  [string & {:as options}]
  (let [{:keys [eof-error? eof-value]
         :or {eof-error? true}} options]
    (->> options
         (merge default-read-options)
         (-read (PushbackReader. (StringReader. string) 64) eof-error? eof-value))))


(comment
  (require '[clojure.data.json :as json-proper])

  (let [test-json "{\"k1\":1, \"k2\":2, \"k3\":3, \"k4\":4, \"k5\":5, \"k6\":6, \"k7\":7, \"k8\":8, \"k9\":9, \"k10\":10}"]
    (prn (json-proper/read-str test-json))
    (prn (read-str test-json)))

  (read-str "{}")
  (read-str "{\"k1\":1, \"k1\":2}"))