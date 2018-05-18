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

    return env;
})();
