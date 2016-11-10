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

(defmacro is [& body]
  `(cljs.test/is ~@body))

(defmacro testing [label & body]
  `(cljs.test/testing ~label
     (js/console.group ~label)
     (try
       ~@body
       (finally
         (js/console.groupEnd ~label)))
     ))

(defmacro log [& args]
  `(js/console.log ~@args))

(defmacro ->log [obj & args]
  `(do (js/console.log ~@args ~obj)
       ~obj))

(defmacro ->>log [& args]
  `(do (js/console.log ~@args)
       ~(last args)))

(defmacro deftest-dom [name [dom-el & more] & body]
  (when more
    (throw (ex-info "i was lazy, properly implement support for multiple dom elements" {})))
  `(when (shadow.devtools.test/dom?)
     (shadow.devtools.test/deftest ~name
       (let [~dom-el (shadow.devtools.test/dom-test-el ~(str name))]
         ~@body))))
