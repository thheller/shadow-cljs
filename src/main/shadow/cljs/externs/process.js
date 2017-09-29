/**
 * these things are in cljs.core only they are only relevant to self-hosted
 * hopefully they will be moved out in the future
 *
 * @externs
 */

var process = {};

// these references must never be renamed since the JS deps might use them
// since shadow.js defines them :advanced might think it would be safe to
// rename them
process.browser;
process.env;
process.env.NODE_ENV;

// this is in cljs.core
process.hrtime;
