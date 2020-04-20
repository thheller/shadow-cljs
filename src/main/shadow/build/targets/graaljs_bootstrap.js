function graaljs_async_not_supported() {
  // FIXME: I'm hesitant to introduce threads from inside the JS context
  // need to figure out some kind of event loop from the JVM side
  throw new Error("async functions not supported in graaljs engine.");
}

function setInterval() {
  graaljs_async_not_supported();
}

function clearInterval(future) {
  graaljs_async_not_supported();
}

function setTimeout() {
  graaljs_async_not_supported();
}

function clearTimeout() {
  graaljs_async_not_supported();
}

function setImmediate() {
  graaljs_async_not_supported();
}

function clearImmediate(future) {
  graaljs_async_not_supported();
}
