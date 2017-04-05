goog.provide("test.loader_gen");
goog.require("shadow.loader");

shadow.loader.setup(
{"core":"/module-loader/core.js", "foo":"/module-loader/foo.js", "bar":"/module-loader/bar.js"},
{'core':[], 'foo': ["core"], 'bar': ["foo"]}
);


