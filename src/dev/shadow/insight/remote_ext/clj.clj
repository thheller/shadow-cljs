(ns shadow.insight.remote-ext.clj
  (:require
    [shadow.insight :as-alias si]
    [clojure.java.io :as io]
    [shadow.build.data :as bd]
    [shadow.remote.runtime.shared :as srs]
    [shadow.insight.parser :as sip]
    [shadow.insight.runtime :as sir])
  (:import [shadow.insight.runtime SwitchToLocal SwitchToRemote]))

(defn fetch!
  [{:keys [runtime] :as svc} {:keys [from file] :as msg}]
  (let [input
        (slurp (io/resource "shadow/insight/example__in.clj"))

        id
        (bd/sha1-string input)

        plan
        (-> (sip/parse input)
            (assoc :plan-id id
                   ;; FIXME: decide how to handle namespaces
                   ;; if defonce is supposed to work it must be consistent and likely based on file name
                   ;; if we don't care about that it can use id, to make recreate it for any content change
                   :plan-ns 'shadow.insight.example--in))]

    (srs/reply runtime msg {:op ::si/plan! :plan plan})))

(defn plan-execute-next!
  [{:keys [runtime state-ref] :as ext}
   {:keys [plan-id driver-id step] :as exec-ctx}]

  (let [{:keys [blocks] :as plan} (get-in @state-ref [:plans plan-id])]
    (assert plan "plan not known? how did you get here")

    (if (>= step (count blocks))
      ;; all done, notify driver
      (srs/relay-msg runtime {:op ::si/exec-finished :to driver-id :exec-ctx exec-ctx})
      ;; execute next block
      (let [block (get-in plan [:blocks step])]
        (if (not= :expr (:type block))
          ;; only interested in exprs here, skip over anything else
          (recur ext (update exec-ctx :step inc))

          (let [self-id (srs/get-client-id runtime)
                exec-ns (get-in exec-ctx [:exec-ns self-id])]

            ;; must ensure exec-ns exists properly, starts in plan-ns
            (if-not exec-ns
              ;; CLJS issues a blank (create-ns 'whatever) when compiling a CLJS ns
              ;; so the ns will already exist, it'll just be empty
              ;; issuing an actual ns form, so it gets initialized properly
              (let [{:keys [plan-ns]} plan]
                (binding [*ns* (find-ns 'shadow.insight.runtime)]
                  (eval `(~'ns ~plan-ns (:require [~'shadow.insight.runtime :as ~'!]))))
                (recur ext (assoc-in exec-ctx [:exec-ns self-id] plan-ns)))

              ;; ns exists, so not first time this runtime evals expr, proceed with prev ns
              (try
                (binding [*ns* (find-ns exec-ns)]
                  ;; FIXME: catch read errors, unlikely to have any but who knows
                  (let [form
                        (read-string (:source block))

                        ;; FIXME: notify driver before starting executing, so it can show progress
                        ;; FIXME: also maybe execute in thread we can interrupt in case things take too long
                        ;; FIXME: *out*/*err*, other bindings?
                        result
                        (eval form)

                        after-ns
                        (.-name *ns*)]

                    ;; FIXME: immediately notify driver that task finished

                    ;; form switched the ns, ensure ! alias still accessible
                    ;; FIXME: polluting user namespaces!
                    ;; insight forms should always be able to execute control ops
                    ;; alternative would be force user to create alias, inconvenient but no forced pollution?
                    ;; for now just hope nobody uses this alias :P
                    (when (not= exec-ns after-ns)
                      (.addAlias *ns* '! (find-ns 'shadow.insight.runtime)))

                    (sir/-proceed-with-plan result ext (assoc-in exec-ctx [:exec-ns self-id] after-ns))))

                (catch Throwable e
                  (srs/relay-msg runtime
                    {:op ::si/step-fail!
                     :to driver-id
                     :exec-ctx exec-ctx
                     :failure (pr-str e)})
                  )))))))))

(extend-protocol sir/IPlanAware
  ;; clj can't be the driver, so always do handoff
  SwitchToLocal
  (-proceed-with-plan
    [this
     {:keys [runtime] :as ext}
     {:keys [step  driver-id] :as exec-ctx}]
    ;; FIXME: notify driver that step is finished
    (let [exec-ctx (assoc-in exec-ctx [:results step] {:hidden true :handoff driver-id})]

      (srs/relay-msg runtime
        {:op ::si/handoff!
         :to driver-id
         :exec-ctx (update exec-ctx :step inc)})))

  SwitchToRemote
  (-proceed-with-plan
    [this
     {:keys [runtime] :as ext}
     {:keys [step driver-id] :as exec-ctx}]
    (let [exec-ctx (assoc-in exec-ctx [:results step] {:hidden true :handoff driver-id})]

      ;; driver should do the switching, so we don't have to replicate runtime select logic here
      (srs/relay-msg runtime
        {:op ::si/switch-to-remote!
         :to driver-id
         :opts (.-opts this)
         :exec-ctx (update exec-ctx :step inc)}))))
