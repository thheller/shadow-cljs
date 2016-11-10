(ns shadow.devtools.test)

(defmacro with-profile [name & body]
  `(try
     (js/console.profile ~name)
     ~@body
     (finally
       (js/console.profileEnd ~name)
       )))

(defmacro deftest [name & body]
  (if (-> name (meta) :profile)
    `(cljs.test/deftest ~name
       (shadow.devtools.test/with-profile ~(str name)
         ~@body))
    `(cljs.test/deftest ~name
       ~@body)))

(defmacro deftest-dom [name [dom-el & more] & body]
  (when more
    (throw (ex-info "i was lazy, properly implement support for multiple dom elements" {})))
  `(when (shadow.devtools.test/dom?)
     (shadow.devtools.test/deftest ~name
       (let [~dom-el (shadow.devtools.test/dom-test-el ~(str name))]
         ~@body))))
