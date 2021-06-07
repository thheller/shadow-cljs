module.exports = function(config) {
  config.set({
    browsers: ['CustomChrome'],
    customLaunchers: {
        CustomChrome: {
          base: 'ChromeHeadless',
          flags: ['--no-sandbox', '--disable-features=VizDisplayCompositor']
        }
      },
    basePath: 'out/test-karma',
    files: ['script.js'],
    frameworks: ['cljs-test'],
    plugins: ['karma-cljs-test', 'karma-chrome-launcher'],
    colors: true,
    logLevel: config.LOG_INFO,
    // FIXME: do we need this?
    client: {args: ["shadow.test.karma.init"],
    singleRun: true}
  })
};
