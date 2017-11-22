module.exports = function(config) {
  config.set({
    browsers: ['ChromeHeadless'],
    basePath: 'out/demo-test-dummy/public',
    files: ['js/test.js'],
    frameworks: ['cljs-test'],
    plugins: ['karma-cljs-test', 'karma-chrome-launcher'],
    colors: true,
    logLevel: config.LOG_INFO,
    client: {args: ["shadow.test.browser.init"],
    singleRun: true}
  })
};
