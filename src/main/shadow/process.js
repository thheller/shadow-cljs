goog.provide("shadow.process");

if (goog.global["process"]) {
  console.warn("INCLUDED shadow.process but js/process exists!!!")
}

goog.provide("process");
goog.provide("process.browser");
goog.provide("process.env.NODE_ENV");

/**
 * @define {string}
 * FIXME: allow specifying env as a map in config and generate this file?
 */
goog.define("process.env.NODE_ENV", "development");

/**
 * @define {boolean}
 */
goog.define("process.browser", false);


// https://github.com/defunctzombie/node-process/blob/master/browser.js

process.title = "browser";
process.argv = [];
process.cwd = function() { return '/'; };

process.version = ''; // empty string to avoid regexp issues
process.versions = {};

function process_noop() {}

process.on = process_noop;
process.addListener = process_noop;
process.once = process_noop;
process.off = process_noop;
process.removeListener = process_noop;
process.removeAllListeners = process_noop;
process.emit = process_noop;
process.prependListener = process_noop;
process.prependOnceListener = process_noop;

process.listeners = function (name) { return [] }

process.binding = function (name) {
    throw new Error('process.binding is not supported');
};

process.cwd = function () { return '/' };
process.chdir = function (dir) {
    throw new Error('process.chdir is not supported');
};
process.umask = function() { return 0; };