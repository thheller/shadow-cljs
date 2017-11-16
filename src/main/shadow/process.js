goog.provide("shadow.process");

/**
 * Utitily namespace to provide common JS idioms for Closure
 */
goog.provide("process");
goog.provide("process.env");

/**
 * @define {string}
 * FIXME: allow specifying env as a map in config and generate this file?
 */
goog.define("process.env.NODE_ENV", "development");

/**
 * @define {boolean}
 */
goog.define("process.browser", false);

