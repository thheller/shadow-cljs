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

(defmacro log-> [obj & args]
  `(do (let [obj# ~obj]
         (js/console.log ~@args obj#)
         obj#)))

(defmacro log->> [& args]
  `(do (let [obj# ~(last args)]
         (js/console.log ~@(butlast args) obj#)
         obj#)))

(defmacro deftest-dom [name dom-els & body]
  (let [container-sym (gensym)]
    `(shadow.devtools.test/deftest ~name
       (when (shadow.devtools.test/dom?)
         (let [~container-sym (shadow.devtools.test/dom-test-container ~(str *ns*) ~(str name))]
           (let ~(->> (for [dom-el dom-els]
                        `[~dom-el (shadow.devtools.test/dom-test-el ~container-sym ~(str dom-el))])
                      (mapcat identity)
                      (into []))
             ~@body))))))
