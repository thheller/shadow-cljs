/**
 * Utitily namespace to provide common JS idioms for Closure
 */

goog.provide("shadow.build.npm_support");
goog.provide("process.env");

/**
 * @define {string}
 * FIXME: allow specifying env as a map in config and generate this file?
 */
goog.define("process.env.NODE_ENV", "development");