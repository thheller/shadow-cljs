/**
 * Utitily namespace to provide common JS idioms for Closure
 */
goog.provide("process.env");

/**
 * @define {string}
 * FIXME: allow specifying env as a map in config and generate this file?
 */
goog.define("process.env.NODE_ENV", "development");

goog.provide("shadow.js");

/**
 * @dict
 */
shadow.js.files = {};

shadow.js.require = function(name) {
  return shadow.js.files[name];
};

shadow.js.provide = function(name, moduleFn) {
  var exports = shadow.js.files[name] || {};
  var module = {};
  // must use string accessors, otherwise :advanced will rename them
  // but the JS is not optimized so it wont map properly
  module["exports"] = exports;

  // FIXME: is the call necessary? only ensures that this equals the module
  // which should match node? not entirely sure how others do it.

  // FIXME: wrap in try/catch?
  moduleFn.call(module, shadow.js.require, module, module["exports"]);

  shadow.js.files[name] = module["exports"];
};

goog.exportSymbol("shadow.js.provide", shadow.js.provide);

// FIXME: remove, just for debugging :advanced stuff
goog.exportSymbol("shadow.js.files", shadow.js.files);

