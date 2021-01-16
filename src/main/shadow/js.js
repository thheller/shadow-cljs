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
shadow.js.NODE_ENV = goog.define("shadow.js.NODE_ENV", "development");

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
    var moduleFn = shadow$provide[name];

    // module must be created before calling moduleFn due to circular deps
    if (module === undefined) {
      if (moduleFn === undefined) {
        throw ("Module not provided: " + name);
      }
      module = {};
      module["exports"] = {};
      shadow.js.files[name] = module;
    }

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

// work around issues where libraries try to manipulate require at runtime
//   codemirror/addon/runmode/runmode.node.js
// will attempt to replace all
//   codemirror/lib/codemirror.js
// requires with itself. in webpack this actually reconfigures require at runtime
// but does not prevent webpack from including the original codemirror.js in the bundle
// output. just nothing ever accesses assuming runmode.node.js is loaded first
// in shadow-cljs this is handled via :package-overrides in the build config
// which actually prevents including the unwanted file and properly redirects
// making the runtime calls do nothing instead
shadow.js.jsRequire.cache = {};
shadow.js.jsRequire.resolve = function(name) { return name };

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
