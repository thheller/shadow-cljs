/**
 * Utitily namespace to provide common JS idioms for Closure
 */
goog.provide("process.env");

/**
 * @define {string}
 * FIXME: allow specifying env as a map in config and generate this file?
 */
goog.define("process.env.NODE_ENV", "development");

goog.provide("shadow.npm");

shadow.npm.pkgs = {};

shadow.npm.register = function(name, moduleFn) {
  var module = shadow.npm.pkgs[name] || { exports: {} };
  moduleFn(module, module.exports);
  shadow.npm.pkgs[name] = module.exports;

  // FIXME: make CLJS emit the property access instead of global, global just sucks
  goog.global[name] = module.exports;
  return module.exports;
};

