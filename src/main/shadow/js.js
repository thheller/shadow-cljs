goog.provide("shadow.js");

/**
 * @dict
 */
shadow.js.files = {};

/**
 * @dict
 */
shadow.js.nativeRequires = {};

/**
 * @define {string}
 * all occurences should be removed by NodeEnvInline but for safety we keep it arround
 */
goog.define("shadow.js.NODE_ENV", "development");

shadow.js.requireStack = [];

shadow.js.add_native_require = function(name, obj) {
  shadow.js.nativeRequires[name] = obj;
};

/**
 * @return {ShadowJS}
 */
shadow.js.jsRequire = function(name, opts) {
  var nativeObj = shadow.js.nativeRequires[name];
  if (nativeObj !== undefined) {
    return nativeObj;
  }

  try {
    if (goog.DEBUG) {
      if (name instanceof String && name.indexOf("/") != -1) {
        console.warn(
          "Tried to dynamically require '" +
            name +
            "' from '" +
            shadow.js.requireStack[shadow.js.requireStack.length - 1] +
            "'. This is not supported and may cause issues."
        );
      }
    }

    shadow.js.requireStack.push(name);

    var module = shadow.js.files[name];

    // module must be created before calling moduleFn due to circular deps
    if (module === undefined) {
      module = {};
      module["exports"] = {};
      shadow.js.files[name] = module;
    }

    var moduleFn = shadow$provide[name];
    if (moduleFn) {
      delete shadow$provide[name];

      try {
        moduleFn.call(
          module,
          goog.global,
          shadow.js.jsRequire,
          module,
          module["exports"]
        );
      } catch (e) {
        console.warn("shadow-cljs - failed to load", name);
        throw e;
      }

      if (opts) {
        var globals = opts["globals"];
        if (globals) {
          for (var i = 0; i < globals.length; i++) {
            window[globals[i]] = module["exports"];
          }
        }
      }
    }
  } finally {
    shadow.js.requireStack.pop();
  }

  return module["exports"];
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
