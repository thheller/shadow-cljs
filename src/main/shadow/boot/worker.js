var SHADOW_ENV = (function () {
  var loadedFiles = {};

  var env = {};

  env.load = function (opts, paths) {
    paths.forEach(function (path) {
      if (!loadedFiles[path]) {
        loadedFiles[path] = true;
        var uri = 'cljs-runtime/' + path;
        importScripts(uri);
      }
    });
  }

  env.isLoaded = function (path) {
    return loadedFiles[path] || false; // false is better than undefined
  }

  env.setLoaded = function(path) {
    loadedFiles[path] = true;
  }

  env.evalLoad = function(path, sourceMap, code) {
    loadedFiles[path] = true;
    code += ("\n//# sourceURL=" + CLOSURE_BASE_PATH + path);
    if (sourceMap) {
      code += ("\n//# sourceMappingURL=" + path + ".map");
    }
    goog.globalEval(code);
 }

  return env;
}).call(this);