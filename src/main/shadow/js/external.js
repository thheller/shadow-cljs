goog.provide("shadow.js.external");
goog.require("shadow.js")

// same as the actual shadow.js.jsRequire
// but meant for :js-provider :external as the actual provider for most packages
// still must be able to provide commonjs dependencies from classpath JS
// for first checking if package is provided via shadow$provide, and if not check shadow$bridge

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
        return shadow$bridge(name);
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
