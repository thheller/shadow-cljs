goog.provide("shadow.js");

/**
 * @dict
 */
shadow.js.files = {};

/**
 * @dict
 */
shadow.js.nativeProvides = {};

/**
 * @define {string}
 * all occurences should be removed by NodeEnvInline but for safety we keep it arround
 */
shadow.js.NODE_ENV = goog.define("shadow.js.NODE_ENV", "development");

shadow.js.requireStack = [];

shadow.js.exportCopy = function(module, other) {
  let copy = {};
  let exports = module["exports"];

  for (let key in other) {
    // don't copy default export, don't overwrite existing
    if (key == 'default' || key in exports || key in copy) {
      continue;
    }

    copy[key] = {
      enumerable: true,
      get: function() { return other[key]; }
    };
  }

  Object.defineProperties(exports, copy);
}

/**
 * @return {ShadowJS}
 */
shadow.js.jsRequire = function(name, opts) {
  var nativeObj = shadow.js.nativeProvides[name];
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
          shadow.js.jsRequire,
          module,
          module["exports"],
          goog.global
        );
      } catch (e) {
        console.warn("shadow-cljs - failed to load", name);
        console.error(e);
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
shadow.js.jsRequire["cache"] = {};
shadow.js.jsRequire["resolve"] = function(name) { return name };

// esm compatibility related things
// this is called for export * from "whatever", so copying all exports from one module to another
shadow.js.jsRequire["exportCopy"] = shadow.js.exportCopy;
// this is used for esm-cjs compatibility where cjs is supposed to be accessible as the default export in esm
shadow.js.jsRequire["esmDefault"] = function(mod) {
  return (mod && mod["__esModule"]) ? mod : {"default": mod};
};

// compat for transpiled import()
// changed to require.dynamic("module$...") by ReplaceRequirePass to hide it entirely from the closure compiler
// there is no actual dynamic loading as the sources are already loaded
// but we can still delay initializing the moduleFn until actually used
// using Promise.resolve as dynamic import still needs to return a promise
shadow.js.jsRequire["dynamic"] = function(name) {
  // delaying the actual require until .then triggers, so that it is actually
  // happening as if async and not in the same task as the request
  return Promise.resolve().then(function() { return shadow.js.jsRequire(name) });
}

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
