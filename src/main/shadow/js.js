/**
 * Utitily namespace to provide common JS idioms for Closure
 */
goog.provide("process");
goog.provide("process.env");

/**
 * @define {string}
 * FIXME: allow specifying env as a map in config and generate this file?
 */
goog.define("process.env.NODE_ENV", "development");

/**
 * @define {boolean}
 */
goog.define("process.browser", true);

goog.provide("shadow.js");

/**
 * @dict
 */
shadow.js.files = {};

/**
 * @return ShadowJS
 */
shadow.js.require = function(name) {
  var exports = shadow.js.files[name];
  // always return something since circular dependencies are allowed in JS
  if (exports === undefined) {
    exports = shadow.js.files[name] = {};
  }
  return exports;
};

shadow.js.provide = function(name, moduleFn) {
  var exports = shadow.js.require(name);
  var module = {};

  // FIXME: should this use an empty {} for exports
  // and copy onto the actual exports after? circular dependencies are weird
  // I'm not sure all references work properly like this

  // must use string accessors, otherwise :advanced will rename them
  // but the JS is not optimized so it wont map properly
  module["exports"] = exports;

  // FIXME: is the call necessary? only ensures that this equals the module
  // which should match node? not entirely sure how others do it.

  // FIXME: wrap in try/catch?
  moduleFn.call(module, shadow.js.require, module, module["exports"]);

  exports = module["exports"];

  // FIXME: working around the impl of CLJS-1620
  // https://dev.clojure.org/jira/browse/CLJS-1620
  // not exactly certain why it doesnt work but sometimes
  // CLJS continues to rewrite thing.default to thing.default$
  //
  // this is a pattern done by babel when converted from ES6
  // so its very isolated and should be reliable enough
  if (typeof(exports) == 'object' && exports["__esModule"] === true) {
    exports["default$"] = exports["default"];
  }

  shadow.js.files[name] = exports;
};

goog.exportSymbol("shadow.js.provide", shadow.js.provide);

// FIXME: remove, just for debugging :advanced stuff
goog.exportSymbol("shadow.js.files", shadow.js.files);

