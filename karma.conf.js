module.exports = function(config) {
  config.set({
    browsers: ['ChromeHeadless'],
    basePath: 'out/demo-karma',
    files: ['test.js'],
    frameworks: ['cljs-test'],
    plugins: ['karma-cljs-test', 'karma-chrome-launcher'],
    colors: true,
    logLevel: config.LOG_INFO,
    // FIXME: do we need this?
    client: {args: ["shadow.test.karma.init"],
    singleRun: true}
  })
};
