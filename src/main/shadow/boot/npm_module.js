$CLJS.SHADOW_ENV = (function() {
    var env = {};

    var loadedFiles = {};

    env.setLoaded = function(name) {
        loadedFiles[name] = true;
    };

    env.load = function(paths) {
        // should never be called
        throw new Error("SHADOW_ENV.load not supported!");
    };

    env.isLoaded = function(name) {
        return loadedFiles[name] || false;
    }

    return env;
})();
