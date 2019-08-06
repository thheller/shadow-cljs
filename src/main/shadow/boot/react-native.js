var SHADOW_ENV = $CLJS.SHADOW_ENV = (function() {
    var env = {};

    var loadedFiles = {};

    env.setLoaded = function(name) {
        loadedFiles[name] = true;
    };

    env.load = function(opts, paths) {
        paths.forEach(function(name) {
            env.setLoaded(name);
        });
    };

    env.isLoaded = function(name) {
        // this is only used by live-reload checking if it should reload a file
        // since all files will always be loaded we don't really need to track this?
        return true;
        // return loadedFiles[name] || false;
    }

    env.evalLoad = function(name, code) {
        goog.globalEval(code);
    };

    return env;
})();

// object of require->fn to "trigger" the actual require
// mostly to remain semantics of "when" something is included
// rather than forcefully including everything at the top of the file
$CLJS.shadow$js = {};
$CLJS.shadow$jsRequire = function(name) {
  var fn = $CLJS.shadow$js[name];

  if (typeof fn == 'function') {
    return fn();
  } else {
    throw new Error("require " + name + " not found. If you require'd this manually you may need to reload the app so metro can process it.")
  }
};

// fake require function since metro replaced all static require calls already
// and require is actually undefined otherwise. Ideally CLJS would have emitted
// another function call as well but that would require adjusting the compiler more
global.require = function(name) {
    return $CLJS.shadow$jsRequire(name);
};
