(ns shadow.http.push-state
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]))

(def not-found
  {:status 404
   :headers {"content-type" "text/plain"}
   :body "Not found."})

(defn handle [{:keys [uri http-roots http-config] :as req}]
  (let [accept (get-in req [:headers "accept"])]
    (if (and accept (not (str/includes? accept "text/html")))
      not-found
      (let [index-name
            (get http-config :push-state/index "index.html")

            headers
            (get http-config :push-state/headers {"content-type" "text/html; charset=utf-8"})

            ;; "/foo/" into "/foo"
            ;; "/" into ""
            uri
            (if (str/ends-with? uri "/")
              (subs uri 0 (-> uri (count) (dec)))
              uri)

            locations-to-test
            (-> []
                ;; for request going to "/foo" and http-root "public"
                ;; checking "public/foo/index.html"
                (into (map #(str % "/" uri "/" index-name)) http-roots)
                ;; and then "public/index.html"
                (into (map #(str % "/" index-name) http-roots)))

            index-file
            (reduce
              (fn [_ file-to-test]
                (if (str/starts-with? file-to-test "classpath:")
                  ;; drop classpath: and check for resources
                  (when-some [rc (io/resource (subs file-to-test 10))]
                    (reduced rc))
                  ;; check actual file via fs
                  (let [file (io/file file-to-test)]
                    (when (and file (.exists file))
                      (reduced file)))))
              nil
              locations-to-test)]

        (if-not index-file
          ;; FIXME: serve some kind of default page instead
          (assoc not-found :body "Not found. Missing index.html.")
          {:status 200
           :headers headers
           :body (slurp index-file)})))))
