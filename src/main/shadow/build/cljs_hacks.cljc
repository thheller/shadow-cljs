(ns shadow.build.cljs-hacks)

(in-ns 'cljs.analyzer)

(defn analyze-symbol
  "Finds the var associated with sym"
  [env sym]
  (if ^boolean (:quoted? env)
    (do
      (register-constant! env sym)
      (analyze-wrap-meta {:op :constant :env env :form sym :tag 'cljs.core/Symbol}))
    (let [{:keys [line column]} (meta sym)
          env  (if-not (nil? line)
                 (assoc env :line line)
                 env)
          env  (if-not (nil? column)
                 (assoc env :column column)
                 env)
          ret  {:env env :form sym}
          lcls (:locals env)]
      (if-some [lb (get lcls sym)]
        (assoc ret :op :var :info lb)
        (let [sym-meta (meta sym)
              sym-ns (namespace sym)
              cur-ns (str (-> env :ns :name))
              ;; when compiling a macros namespace that requires itself, we need
              ;; to resolve calls to `my-ns.core/foo` to `my-ns.core$macros/foo`
              ;; to avoid undeclared variable warnings - António Monteiro
              #?@(:cljs [sym (if (and sym-ns
                                      (not= sym-ns "cljs.core")
                                      (gstring/endsWith cur-ns "$macros")
                                      (not (gstring/endsWith sym-ns "$macros"))
                                      (= sym-ns (subs cur-ns 0 (- (count cur-ns) 7))))
                               (symbol (str sym-ns "$macros") (name sym))
                               sym)])
              info     (if-not (contains? sym-meta ::analyzed)
                         (resolve-existing-var env sym)
                         (resolve-var env sym))]
          (if-not (true? (:def-var env))
            (-> ret
                (assoc :op :var :info info)
                (cond->
                  (= 'js (:ns info))
                  (assoc :tag 'js))
                (merge (when-let [const-expr (:const-expr info)]
                         {:const-expr const-expr})))
            (let [info (resolve-var env sym)]
              (assoc ret :op :var :info info))))))))
