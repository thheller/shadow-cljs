(ns shadow.http.router
  (:require [clojure.string :as str]))

;; no yada because of crazy deps
;; no pedestal because no aleph

;; this code is 4+ years old, don't look too close
;; but I know how it works and there are no deps
;; I just want some basic routing

(defn call-handler [req handler args]
  (apply handler req args))

(defn method-match? [route-method req-method]
  (or (= :any route-method) (= route-method req-method)))

(defn consume-path [req path-tokens num params]
  (if (= num 0)
    req
    (let [consumed-tokens (subvec path-tokens 0 num)
          prev-consumed-tokens (::path-tokens-consumed req)
          remaining-tokens (subvec path-tokens num)]

      (-> req
          (assoc ::path-tokens remaining-tokens)
          (assoc ::path-tokens-consumed (conj prev-consumed-tokens consumed-tokens))
          (assoc :path-params (merge (:path-params req {}) params))))))

(def regex-for-type-tag
  {:int #"^\d+$"
   :long #"^\d+$"})

(defn extract-token-var [^String string]
  (let [[name regex] (.split string "#" 2)
        [name type] (.split name ":" 2)
        type (keyword (or type "string"))
        value (if (= "" name)
                nil
                (symbol name))

        regex (if regex
                (clojure.core/read-string (str "#\"" regex "\""))
                (get regex-for-type-tag type))]

    {:type :var
     :value value
     :keyword (when value
                (keyword value))
     :type-tag type
     :regex regex}
    ))

(defn- define-token [^String string]
  (if (and (.startsWith string "{") (.endsWith string "}"))
    (extract-token-var (.substring string 1 (- (count string) 1)))
    {:type :constant
     :value string}))

(defn- path-split [spec ^String path]
  (assert (not= "/" path) (str "Route requires at least one token" path))
  (assoc spec :tokens
    (if (empty? path)
      (list)
      (let [tokens (.split (.substring path 1) "/")]
        (map (fn [token idx]
               (assoc (define-token token)
                 :pos idx))
          tokens
          (iterate inc 0))))))

(defn- parse-route-string [^String path]
  (if (.startsWith path "^")
    (path-split {:prefix true} (.substring path 1))
    (path-split {:prefix false} path)))

(defn- make-path-conditions [tokens token-val-name]
  (let [constants (filter #(= :constant (:type %)) tokens)
        regexes (filter :regex tokens)]
    (concat
      (map (fn [{:keys [value pos]}]
             `(= ~value (nth ~token-val-name ~pos))) constants)
      (map (fn [{:keys [value pos regex]}]
             `(re-find ~regex (nth ~token-val-name ~pos)))
        regexes))))

(defn wrap-type-expr [tag token]
  (condp = tag
    :int `(Integer/parseInt ~token)
    :long `(Long/parseLong ~token)
    :ints `(vec (map #(Integer/parseInt %) (str/split ~token #",")))
    :longs `(vec (map #(Long/parseLong %) (str/split ~token #",")))
    :string token
    :keyword `(keyword ~token)
    (throw (ex-info "unknown type tag in route expression" {:tag tag :token token}))))

(defn- make-path-vars [tokens token-val-name]
  (let [vars (filter #(= :var (:type %)) tokens)]
    (->> vars
         (filter :value)
         (map (fn [{:keys [value pos type-tag] :as token}]
                (if value
                  (merge token
                         {:expr (wrap-type-expr type-tag `(nth ~token-val-name ~pos))
                          :ref value})
                  token))))))

(defn- make-cond-pair [req path-tokens count-tokens req-method [method route-string handler & args :as route]]
  (assert (keyword? method) (str "Route method missing or not a keyword: " route))
  (assert (string? route-string) (str "Route path missing or not a string: " route))
  (assert (symbol? handler) (str "Route handler missing or not a symbol: " route))

  (let [spec (parse-route-string route-string)
        method (-> method name .toLowerCase keyword) ;; ring has lowercase, i prefer upper ...
        spec-tokens (spec :tokens)
        match-count (count spec-tokens)
        size-test (if (spec :prefix) 'clojure.core/>= 'clojure.core/=) ; prefix matches continue if more tokens are present
        path-conditions (make-path-conditions spec-tokens path-tokens)
        path-vars (make-path-vars spec-tokens path-tokens)
        predicate
        `(and
           (method-match? ~method ~req-method)
           (~size-test ~count-tokens ~match-count)
           ~@path-conditions)]

    (if (empty? path-vars)
      ;; no path vars, just dispatch args is given
      `(~predicate (call-handler
                     (consume-path ~req ~path-tokens ~match-count {})
                     ~handler
                     (list ~@args)))
      ;; path had args, so make them visible and replace references
      (let [let-vars (mapcat (fn [{:keys [ref expr]}] `(~ref ~expr)) path-vars)
            merge-args (mapcat (fn [{:keys [ref keyword]}] `(~keyword ~ref)) path-vars)
            action `(let [~@let-vars]
                      (-> ~req
                          (consume-path ~path-tokens ~match-count (hash-map ~@merge-args))
                          (call-handler ~handler [~@args])))]
        `(~predicate ~action)))))

(defn- make-route-cond [routes req path-tokens count-tokens req-method]
  (let [else-handler (last routes)
        matches (butlast routes)
        cond-pairs (mapcat (partial make-cond-pair req path-tokens count-tokens req-method) matches)]
    (assert (symbol? else-handler) "Last route must be a symbol (to a function)")
    `(cond
       ~@cond-pairs
       :else (call-handler ~req ~else-handler []))))

(defn- make-route-body [req routes]
  (assert (>= (count routes) 2) "Need at least 2 routes")

  (let [path-tokens (gensym "path-tokens__")
        count-tokens (gensym "count-tokens__")
        req-method (gensym "req-method__")
        route-cond (make-route-cond routes req path-tokens count-tokens req-method)]
    `(let [~path-tokens (::path-tokens ~req)
           ~count-tokens (count ~path-tokens)
           ~req-method (get-in ~req [:ring-request :request-method])]
       ~route-cond)))

(defn parse-request-path [^String route-path]
  (let [route-path
        (if (= "/" (last route-path))
          (.substring route-path 0 (dec (count route-path)))
          route-path)]

    (vec (rest (.split route-path "/")))
    ))

(defmacro route-fn [& routes]
  (let [req-name (gensym "req__")
        route-body (make-route-body req-name routes)]
    `(fn [~req-name]
       ~route-body)))

(defmacro route [req & routes]
  (assert req "Req Name is missing")
  (let [req-name (gensym "req__")
        route-body (make-route-body req-name routes)]
    `(let [~req-name ~req]
       ~route-body)))

(defn prepare
  [{:keys [ring-request] :as ctx}]
  (let [{:keys [uri]}
        ring-request

        path-tokens
        (parse-request-path uri)]

    (assoc ctx
      ::path-tokens path-tokens
      ::path-tokens-consumed [])))

