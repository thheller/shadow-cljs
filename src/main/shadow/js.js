goog.provide("shadow.js");
goog.require("shadow.process");

// since we only run JS through :simple we need to keep these as is.
// these are not in process.js since the :closure js provider will optimize
// the JS fully and we don't need to export this since the renamed names
// will be used
goog.exportSymbol("process.env.NODE_ENV", process.env.NODE_ENV);
goog.exportSymbol("process.browser", process.browser);
goog.exportSymbol("process.title", process.title);
goog.exportSymbol("process.argv", process.argv);
goog.exportSymbol("process.env", process.env);
goog.exportSymbol("process.cwd", process.cwd);
goog.exportSymbol("process.on", process.on);
goog.exportSymbol("process.umask", process.umask);
goog.exportSymbol("process.once", process.once);
goog.exportSymbol("process.off", process.off);

/**
 * @dict
 */
shadow.js.files = {};

/**
 * @return {ShadowJS}
 */
shadow.js.jsRequire = function(name, opts) {
  var exports = shadow.js.files[name];
  if (exports === undefined) {
    exports = shadow.js.files[name] = {};
  }

  var moduleFn = shadow$provide[name];
  if (moduleFn) {
    delete shadow$provide[name];

    var module = {};

    // FIXME: should this use an empty {} for exports
    // and copy onto the actual exports after? circular dependencies are weird
    // I'm not sure all references work properly like this

    // must use string accessors, otherwise :advanced will rename them
    // but the JS is not optimized so it wont map properly
    module["exports"] = exports;

    // FIXME: is the call necessary? only ensures that this equals the module
    // which should match node? not entirely sure how others do it.

    try {
      moduleFn.call(module, goog.global, shadow.js.jsRequire, module, module["exports"], goog.global);
    } catch (e) {
      console.warn("shadow-cljs - failed to load", name);
      throw e;
    }

    exports = module["exports"];

    shadow.js.files[name] = exports;

    if (opts) {
      var globals = opts["globals"];
      if (globals) {
        for (var i = 0; i < globals.length; i++) {
          window[globals[i]] = exports;
        }
      }
    }
  }

  return exports;
};

/**
 * @dict
 */
shadow.js.modules = {};


shadow.js.require = function(name, opts) {
  return shadow.js.jsRequire(name, opts);
  /*
  var mod = shadow.js.modules[name];

  if (typeof(mod) == 'undefined') {
    var exports = shadow.js.jsRequire(name, opts);

    if (exports && exports["__esModule"]) {
        mod = exports;
    } else {
        mod = {"default":exports};
    }

    shadow.js.modules[name] = mod;
  }

  return mod;
  */
};
