var SHADOW_ENV = function() {
  var loadedFiles = {};

  var env = {};

  var doc = goog.global.document;

  if (!doc) {
    throw new Error("browser bootstrap used in incorrect target");
  }

  var wentAsync = false;

  var canDocumentWrite = function() {
    return !wentAsync && doc.readyState == "loading";
  };

  var asyncLoad = (function() {
    var loadOrder = [];
    var loadState = {};

    function loadPending() {
      for (var i = 0, len = loadOrder.length; i < len; i++) {
        var uri = loadOrder[i];
        var state = loadState[uri];

        if (typeof state === "string") {
          loadState[uri] = true;
          if (state != "") {
            var code = state + "\n//# sourceURL=" + uri + "\n";
            goog.globalEval(code);
          }
        } else if (state === true) {
          continue;
        } else {
          break;
        }
      }
    }

    // ie11 doesn't have fetch, use xhr instead
    // FIXME: not sure if fetch provides any benefit over xhr
    if (typeof window.fetch === "undefined") {
      return function asyncXhr(uri) {
        loadOrder.push(uri);
        loadState[uri] = false;
        var req = new XMLHttpRequest();
        req.onload = function(e) {
          loadState[uri] = req.responseText;
          loadPending();
        };
        req.open("GET", uri);
        req.send();
      }
    } else {
      function responseText(response) {
        // FIXME: check status
        return response.text();
      }

      function evalFetch(uri) {
        return function(code) {
          loadState[uri] = code;
          loadPending();
        };
      }

      return function asyncFetch(uri) {
        if (loadState[uri] == undefined) {
          loadState[uri] = false;
          loadOrder.push(uri);
          fetch(uri)
            .then(responseText)
            .then(evalFetch(uri));
        }
      };
    }
  })();

  env.load = function(opts, paths) {
    var docWrite = opts.forceAsync ? false : canDocumentWrite();

    paths.forEach(function(path) {
      if (!loadedFiles[path]) {
        loadedFiles[path] = true;

        var uri = CLOSURE_BASE_PATH + path;

        if (docWrite) {
          document.write(
            "<script src='" + uri + "' type='text/javascript'></script>"
          );
        } else {
          // once async always async
          wentAsync = true;
          asyncLoad(uri);
        }
      }
    });
  };

  env.isLoaded = function(path) {
    return loadedFiles[path] || false; // false is better than undefined
  };

  env.setLoaded = function(path) {
    loadedFiles[path] = true;
  };

  env.evalLoad = function(path, sourceMap, code) {
    loadedFiles[path] = true;
    code += ("\n//# sourceURL=" + CLOSURE_BASE_PATH + path);
    if (sourceMap) {
      code += ("\n//# sourceMappingURL=" + path + ".map");
    }
    goog.globalEval(code);
  }

  return env;
}.call(this);
