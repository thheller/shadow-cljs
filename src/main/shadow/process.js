goog.provide("shadow.process");

if (goog.global["process"]) {
  console.warn("INCLUDED shadow.process but js/process exists!!!")
}

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
