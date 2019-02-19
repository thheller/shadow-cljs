/**
 * these things are in cljs.core only they are only relevant to self-hosted
 * hopefully they will be moved out in the future
 *
 * @externs
 */

Error.prototype.number;
Error.prototype.columnNumber;

/**
 * @const
 */
var global;

/** @const {string} */
var __filename;

/** @const {string} */
var __dirname;

/**
 * @param {string} name
 * @return {?}
 */
function require(name) {}


/**
 * @dict
 */
var shadow$provide = {};

/**
 * @dict
 */
var shadow$modules = {};


var shadow$umd$export = {};


/**
 * @param {string} name
 * @const
 */
var Java = {};
Java.type = function(name) {};

/**
 * leaflet.js will always create a window.L global
 * this can lead to very hard to debug errors in :advanced builds
 * https://github.com/Leaflet/Leaflet/pull/2943
 *
 * just reserving it always doesn't harm builds that don't use it
 * @const
 */
var L = {};