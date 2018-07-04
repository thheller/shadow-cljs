goog.provide("shadow.js");

/**
 * @dict
 */
shadow.js.files = {};

/**
 * @nocollapse
 */
shadow.js.process = {};

/**
 * @define {string}
 * all occurences should be removed by NodeEnvInline but for safety we keep it arround
 */
goog.define("shadow.js.NODE_ENV", "development");

/**
 * @define {boolean}
 */
goog.define("shadow.js.process.browser", false);


/**
 * @dict
 */
shadow.js.shims = {};


// https://github.com/defunctzombie/node-process/blob/master/browser.js

shadow.js.process["title"] = "browser";
shadow.js.process["argv"] = [];
shadow.js.process["cwd"] = function() { return '/'; };

shadow.js.process["version"] = ''; // empty string to avoid regexp issues
shadow.js.process["versions"] = {};
shadow.js.process["env"] = {"NODE_ENV":shadow.js.NODE_ENV};

shadow.js.process_noop = function() {}

shadow.js.process["on"] = shadow.js.process_noop;
shadow.js.process["addListener"] = shadow.js.process_noop;
shadow.js.process["once"] = shadow.js.process_noop;
shadow.js.process["off"] = shadow.js.process_noop;
shadow.js.process["removeListener"] = shadow.js.process_noop;
shadow.js.process["removeAllListeners"] = shadow.js.process_noop;
shadow.js.process["emit"] = shadow.js.process_noop;
shadow.js.process["prependListener"] = shadow.js.process_noop;
shadow.js.process["prependOnceListener"] = shadow.js.process_noop;

shadow.js.process["listeners"] = function (name) { return [] }

shadow.js.process["binding"] = function (name) {
    throw new Error('process.binding is not supported');
};

shadow.js.process["cwd"] = function () { return '/' };
shadow.js.process["chdir"] = function (dir) {
    throw new Error('process.chdir is not supported');
};
shadow.js.process["umask"] = function() { return 0; };

/**
 * @return {ShadowJS}
 */
shadow.js.jsRequire = function(name, opts) {
  var module = shadow.js.files[name];

  // module must be created before calling moduleFn due to circular deps
  if (module === undefined) {
    module = shadow.js.files[name] = {
      "exports": {}
    };
  }

  var moduleFn = shadow$provide[name];
  if (moduleFn) {
    delete shadow$provide[name];

    var process = goog.global.process || shadow.js.process;

    try {
      moduleFn.call(module, goog.global, process, shadow.js.jsRequire, module, module["exports"], shadow.js.shims);
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
