// https://github.com/roman01la/scrum-ssr-example/blob/react/externs/react.js#L1827-L1840

/**
 * React event system creates plugins and event properties dynamically.
 * These externs are needed when consuming React as a JavaScript module
 * in light of new ClojureScript compiler additions (as of version 1.9.456).
 * See the following link for an example.
 * https://github.com/facebook/react/blob/c7129c/src/renderers/dom/shared/eventPlugins/SimpleEventPlugin.js#L43
 */
var ResponderEventPlugin;
var SimpleEventPlugin;
var TapEventPlugin;
var EnterLeaveEventPlugin;
var ChangeEventPlugin;
var SelectEventPlugin;
var BeforeInputEventPlugin;