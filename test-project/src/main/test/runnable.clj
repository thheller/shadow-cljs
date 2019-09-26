(ns test.runnable
  (:require
    ;; FIXME: there should be proper test builds for each or these
    ;; for now just testing if the targets actually load properly
    [shadow.build.targets.azure-app]
    [shadow.build.targets.bootstrap]
    [shadow.build.targets.browser]
    [shadow.build.targets.browser-test]
    [shadow.build.targets.chrome-extension]
    [shadow.build.targets.karma]
    [shadow.build.targets.node-library]
    [shadow.build.targets.node-script]
    [shadow.build.targets.node-test]
    [shadow.build.targets.npm-module]
    [shadow.build.targets.react-native]))

(defn foo []
  (prn :foo))

(defn foo-server
  {:shadow/requires-server true}
  [& args]
  (prn [:foo-server args]))