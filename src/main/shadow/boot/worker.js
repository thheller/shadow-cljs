var SHADOW_ENV = (function () {
  var loadedFiles = {};

  var env = {};

  var scriptBase = self.location.href;
  scriptBase = scriptBase.substring(0, scriptBase.lastIndexOf("/"));
  env.scriptBase = scriptBase + "/cljs-runtime/";

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
    code += ("\n//# sourceURL=" + scriptBase + path);
    if (sourceMap) {
      code += ("\n//# sourceMappingURL=" + scriptBase + path + ".map");
    }
    try {
      goog.globalEval(code);
    } catch (e) {
        console.warn("failed to load", path, e);
    }
 }

  return env;
}).call(this);