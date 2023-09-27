var SHADOW_ENV = $CLJS.SHADOW_ENV = (function() {
    var env = {};

    var loadedFiles = env.loadedFiles = {};

    env.setLoaded = function(name) {
        loadedFiles[name] = true;
    };

    env.load = function(opts, paths) {
        paths.forEach(function(name) {
            env.setLoaded(name);
        });
    };

    env.isLoaded = function(name) {
        return loadedFiles[name] || false;
    }

    return env;
})();
