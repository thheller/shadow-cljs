var SHADOW_ENV = (function () {
  var loadedFiles = {};

  var env = {};

  env.load = function (paths) {
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

  return env;
}).call(this);