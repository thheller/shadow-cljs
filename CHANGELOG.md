# Changelog

## [2.20.3](https://github.com/thheller/shadow-cljs/compare/0b6854ee36f9de59f78fc073809e05db4f00d37b...e53240007b45ab891f23edcb96b2ed377fafc471) - 2022-10-09
- [ [`e5324`](https://github.com/thheller/shadow-cljs/commit/e53240007b45ab891f23edcb96b2ed377fafc471) ] fix goog.module requires in the REPL
- [ [`bf38b`](https://github.com/thheller/shadow-cljs/commit/bf38b2c4c68c2dbd634948155ca0572cefcf739b) ] also add throw
- [ [`0b685`](https://github.com/thheller/shadow-cljs/commit/0b6854ee36f9de59f78fc073809e05db4f00d37b) ] add undefined as known global so it doesn't end up in externs

## [2.20.2](https://github.com/thheller/shadow-cljs/compare/531859df882badc9601d5008ee3d98463296227e...531859df882badc9601d5008ee3d98463296227e) - 2022-09-16
- [ [`53185`](https://github.com/thheller/shadow-cljs/commit/531859df882badc9601d5008ee3d98463296227e) ] fix breakage when npm packages don't match their installed name

## [2.20.1](https://github.com/thheller/shadow-cljs/compare/a7b6bf2c4ba7c508fc6f1a279d4ca7108dfddd02...846446eb49e51daf9c25b6016603afe38b6d4768) - 2022-08-31
- [ [`84644`](https://github.com/thheller/shadow-cljs/commit/846446eb49e51daf9c25b6016603afe38b6d4768) ] fix debug macros
- [ [`a7b6b`](https://github.com/thheller/shadow-cljs/commit/a7b6bf2c4ba7c508fc6f1a279d4ca7108dfddd02) ] fix not catching enough exceptions

## [2.20.0](https://github.com/thheller/shadow-cljs/compare/47c3824634118f27a8276a70d6bf2da8752905bc...41ea12a4849e1bcd49fc54a814d460f6eac05f97) - 2022-08-31
- [ [`41ea1`](https://github.com/thheller/shadow-cljs/commit/41ea12a4849e1bcd49fc54a814d460f6eac05f97) ] dep bumps
- [ [`197fc`](https://github.com/thheller/shadow-cljs/commit/197fc8bb03254cf54b65edb06114465509d6a014) ] readme
- [ [`5e19b`](https://github.com/thheller/shadow-cljs/commit/5e19b265f1620b6b0d10e6710def9f35645f1735) ] readme
- [ [`20972`](https://github.com/thheller/shadow-cljs/commit/20972f816660b25041c1c6af700f7a0838cd2b81) ] readme
- [ [`0b819`](https://github.com/thheller/shadow-cljs/commit/0b819197dfcd5b944617745bc1320385df2230b4) ] reorg readme header
- [ [`3a231`](https://github.com/thheller/shadow-cljs/commit/3a231b51d55f9a1dc21daab119406443d611f03d) ] fix typo
- [ [`35edd`](https://github.com/thheller/shadow-cljs/commit/35edd7df69efbefc3d764315deb3b22ad94ab57a) ] hard bump to Java11 minimum
- [ [`47c38`](https://github.com/thheller/shadow-cljs/commit/47c3824634118f27a8276a70d6bf2da8752905bc) ] add check for running instances in project

## [2.19.9](https://github.com/thheller/shadow-cljs/compare/4115a2be68160b02e88a70409000f696008663e8...53421dabbcead5c9b26ea6544190bb62f5ca0e83) - 2022-08-14
- [ [`53421`](https://github.com/thheller/shadow-cljs/commit/53421dabbcead5c9b26ea6544190bb62f5ca0e83) ] dep bumps
- [ [`4760e`](https://github.com/thheller/shadow-cljs/commit/4760eac19a8312f7d4c653ad6a49ecfdac4969a1) ] fix :dump-closure-inputs debug option
- [ [`54e95`](https://github.com/thheller/shadow-cljs/commit/54e9574672711b4de40965f9f28da4c92e2b37d4) ] move all cljs hacks to delayed init to fix some AOT issues
- [ [`5cc1e`](https://github.com/thheller/shadow-cljs/commit/5cc1ed780f444fee0072ffae9d971ccc3270f615) ] fix css build
- [ [`e8e0c`](https://github.com/thheller/shadow-cljs/commit/e8e0cf9973bafcd472ffa1066bbd8ae8c83e5cab) ] go back to the old ESM hack
- [ [`a6d10`](https://github.com/thheller/shadow-cljs/commit/a6d1035b908fbbeabab9e6e4c229db9bacbdd6ea) ] more :target :esm tweaks
- [ [`70966`](https://github.com/thheller/shadow-cljs/commit/709668b94f6796287a14fd912a1edb649a90469b) ] closure ESM output seems to have some issues
- [ [`1a761`](https://github.com/thheller/shadow-cljs/commit/1a76102e05d9ec51115585fdb66087ee5fd7a3d9) ] rewrite ESM output to use Closure Compiler ESM output
- [ [`51d21`](https://github.com/thheller/shadow-cljs/commit/51d21f5b325d8cb18cd670a3edfab7063e91d477) ] remove try/catch for :init-fn call in release builds
- [ [`e043e`](https://github.com/thheller/shadow-cljs/commit/e043ec084d38a8d62c8ade1b5c70a87d01c1661c) ] skip unnecessary use strict in ESM output
- [ [`94b08`](https://github.com/thheller/shadow-cljs/commit/94b08b5ad92d68226ad4e0e03f2e3faa6520e881) ] update css build
- [ [`6757a`](https://github.com/thheller/shadow-cljs/commit/6757a71cbf2fa9ef2a22465ffa366b238b0fefcc) ] updating year in copyright (#1043)
- [ [`c2d49`](https://github.com/thheller/shadow-cljs/commit/c2d491f88419fe2a3b574585c1218ccba875e74e) ] fix README to indicate that Java 11 is now minimum
- [ [`c8884`](https://github.com/thheller/shadow-cljs/commit/c8884ddfcb878b97b95058de74fdd37234f35a5e) ] adjust UI for grove changes
- [ [`4115a`](https://github.com/thheller/shadow-cljs/commit/4115a2be68160b02e88a70409000f696008663e8) ] update css build functions

## [2.19.8](https://github.com/thheller/shadow-cljs/compare/2730fb0d98e33a1603f49dca319b2c764a91f570...2730fb0d98e33a1603f49dca319b2c764a91f570) - 2022-07-25
- [ [`2730f`](https://github.com/thheller/shadow-cljs/commit/2730fb0d98e33a1603f49dca319b2c764a91f570) ] drop the one CLJ use of shadow.css

## [2.19.7](https://github.com/thheller/shadow-cljs/compare/a692992acff41f7e747520793c95ca85072f3d2a...084b50b969e010b28a778a3ed4e4a6d53003a00b) - 2022-07-25
- [ [`084b5`](https://github.com/thheller/shadow-cljs/commit/084b50b969e010b28a778a3ed4e4a6d53003a00b) ] dep bump: transit-cljs, closure-compiler
- [ [`03506`](https://github.com/thheller/shadow-cljs/commit/0350658009f0cad684fb61da15c27e0a2e597944) ] add config option to disable namespace reset in watch
- [ [`4ce85`](https://github.com/thheller/shadow-cljs/commit/4ce854f1c8ba41c9eec8447ec23f77cb068d0f77) ] fix REPL require :reload not actually reloading anything
- [ [`fcfb2`](https://github.com/thheller/shadow-cljs/commit/fcfb2ec0f6b32e5d6a70acfb9785a6157ea9421c) ] css cleanup
- [ [`17dbe`](https://github.com/thheller/shadow-cljs/commit/17dbe0ee2ee849abba53c952dd7f453c60313048) ] Polyfill inherits (#1035)
- [ [`7b95c`](https://github.com/thheller/shadow-cljs/commit/7b95c2642b80d249d2ecec04587d64e4419ddc88) ] entirely remove tailwind and only use shadow.css
- [ [`de1ee`](https://github.com/thheller/shadow-cljs/commit/de1ee2f08f6e2e42f6ae6e128949b4d26cc6d1a6) ] port rest of UI css to shadow.css
- [ [`83b77`](https://github.com/thheller/shadow-cljs/commit/83b777032b4a72c67c040e5994cbcade1bd1b4f2) ] more UI css porting
- [ [`838b9`](https://github.com/thheller/shadow-cljs/commit/838b9d052dd522bc4e61cce0abce9960a054686c) ] extract shadow.css to its own project
- [ [`5b91d`](https://github.com/thheller/shadow-cljs/commit/5b91db91fab51b9136071c7fee5368bd9969cac9) ] prep for css optimizer, proof of concept appears to work
- [ [`4d51d`](https://github.com/thheller/shadow-cljs/commit/4d51d1824030284f660d6637e5eac1c10a08ac67) ] css getting to reasonable state
- [ [`f8f0c`](https://github.com/thheller/shadow-cljs/commit/f8f0ce5ba4a649c671762acde247c0447d07e9b1) ] more css
- [ [`da675`](https://github.com/thheller/shadow-cljs/commit/da675581578dda18e69ce1fd80b2c8a2aeb0efec) ] more css, begin trials in UI code
- [ [`da965`](https://github.com/thheller/shadow-cljs/commit/da9659d4da957b7a58e0c0b581cc2162cfb781f4) ] more css stuff
- [ [`d6e14`](https://github.com/thheller/shadow-cljs/commit/d6e14905022b719d2e240646417f53d9ee452e4d) ] add beginnings of shadow.css (from shadow-grove)
- [ [`361e5`](https://github.com/thheller/shadow-cljs/commit/361e592dc35c5332a0d9a80ff2a038822f286cc7) ] fix lingering goog.global = this || self for ESM builds
- [ [`a6929`](https://github.com/thheller/shadow-cljs/commit/a692992acff41f7e747520793c95ca85072f3d2a) ] fix esm build with :devtools {:enabled false}

## [2.19.6](https://github.com/thheller/shadow-cljs/compare/d29e56167c973c426ac8413cbe68dc28d5fa74dd...ff9668be8982182b5382d51cd35085fe279b887d) - 2022-07-14
- [ [`ff966`](https://github.com/thheller/shadow-cljs/commit/ff9668be8982182b5382d51cd35085fe279b887d) ] remove process use in :npm-module builds using :runtime :browser
- [ [`12dcc`](https://github.com/thheller/shadow-cljs/commit/12dcc769afb43723f282c910929e47a5ab353692) ] fix js-await catch
- [ [`73257`](https://github.com/thheller/shadow-cljs/commit/732577ea5257f6418d4926d10f5c0e3261303e8b) ] fix incorrect error source location when using :warning-as-errors
- [ [`19b6f`](https://github.com/thheller/shadow-cljs/commit/19b6f9a77197d106cf11c2c5cf09b316c87f7939) ] grove bump
- [ [`86394`](https://github.com/thheller/shadow-cljs/commit/8639487bf7d9403322202f12a6ef59fef8b8a822) ] fix UI code for grove ident change
- [ [`31948`](https://github.com/thheller/shadow-cljs/commit/3194818f071a64329e488561591e93fc96e463dc) ] also migrate build-report UI
- [ [`29def`](https://github.com/thheller/shadow-cljs/commit/29defe5788001a2fa2ee3aa93b927a025a386cae) ] migrate UI code to proper shadow-grove release
- [ [`1131a`](https://github.com/thheller/shadow-cljs/commit/1131a14c01d35f4a265e19f3b8c9e7b7956609ff) ] Update README.md (#1028)
- [ [`d29e5`](https://github.com/thheller/shadow-cljs/commit/d29e56167c973c426ac8413cbe68dc28d5fa74dd) ] nrepl-debug support for .port files

## [2.19.5](https://github.com/thheller/shadow-cljs/compare/96189a525a444a06a5b0e2b52024995d540201e1...c01ef922a505d0a7bf03073370ac27b898040310) - 2022-06-24
- [ [`c01ef`](https://github.com/thheller/shadow-cljs/commit/c01ef922a505d0a7bf03073370ac27b898040310) ] cljs bump
- [ [`96189`](https://github.com/thheller/shadow-cljs/commit/96189a525a444a06a5b0e2b52024995d540201e1) ] make build-report/generate accept a map for build config only

## [2.19.4](https://github.com/thheller/shadow-cljs/compare/22d875f9b0a8a9cd5f07ee8fad590eb0fd511558...c3a41911e49c27b9c98c4e0f14f8b0da9dff62ad) - 2022-06-21
- [ [`c3a41`](https://github.com/thheller/shadow-cljs/commit/c3a41911e49c27b9c98c4e0f14f8b0da9dff62ad) ] fix improperly initialized :compiler-env breaking stuff
- [ [`6614c`](https://github.com/thheller/shadow-cljs/commit/6614c50fb6c4ee8302c25d2abb9061d599814f8e) ] sync deps.end
- [ [`5eece`](https://github.com/thheller/shadow-cljs/commit/5eecea36a00faaaed34137bf30abe02e935293a1) ] don't use :build-hooks in build reports
- [ [`22d87`](https://github.com/thheller/shadow-cljs/commit/22d875f9b0a8a9cd5f07ee8fad590eb0fd511558) ] remove some old unused :foreign-lib related code

## [2.19.3](https://github.com/thheller/shadow-cljs/compare/1b459145974b1f9566a14c3b87e1f70b3b288738...3e8db0c57ae40d2f97bd9cddb82f945d00bf54e9) - 2022-06-11
- [ [`3e8db`](https://github.com/thheller/shadow-cljs/commit/3e8db0c57ae40d2f97bd9cddb82f945d00bf54e9) ] closure-compiler v20220601
- [ [`2506e`](https://github.com/thheller/shadow-cljs/commit/2506e49287b9b58b831af78bd1a6e9b90781b567) ] cljs 1.11.57
- [ [`e8c2f`](https://github.com/thheller/shadow-cljs/commit/e8c2fad22ca452468944e56b345c027b6f58e1ee) ] use :cljs variant from data_readers.cljc
- [ [`1b459`](https://github.com/thheller/shadow-cljs/commit/1b459145974b1f9566a14c3b87e1f70b3b288738) ] also collect class declaration as global for re-export

## [2.19.2](https://github.com/thheller/shadow-cljs/compare/c03364b6be69792e8f1bdb3d197bf256db1c9c4d...413d1d61c899dcc246b33f0e3f2923eb7821c7a7) - 2022-06-08
- [ [`413d1`](https://github.com/thheller/shadow-cljs/commit/413d1d61c899dcc246b33f0e3f2923eb7821c7a7) ] move temp guava dep up
- [ [`8088f`](https://github.com/thheller/shadow-cljs/commit/8088fac0c39bf5778ddac7de07666731fbbde0d8) ] actually still against polluting cljs.reader by default
- [ [`e70f1`](https://github.com/thheller/shadow-cljs/commit/e70f179702a3047810133b42511606ac797491b1) ] fix some data_readers issues
- [ [`f66ab`](https://github.com/thheller/shadow-cljs/commit/f66ab9bb3945b75ee06f8397d14e5787ff045574) ] add support for data_readers.cljc for CLJS compat
- [ [`c0336`](https://github.com/thheller/shadow-cljs/commit/c03364b6be69792e8f1bdb3d197bf256db1c9c4d) ] temporary declare guava as a dependency

## [2.19.1](https://github.com/thheller/shadow-cljs/compare/05f2cd1c525fea02781eef4329d5d7823be7f8a0...49fb078b834e64f63122e3a8ad3ddcd1f4485969) - 2022-06-01
- [ [`49fb0`](https://github.com/thheller/shadow-cljs/commit/49fb078b834e64f63122e3a8ad3ddcd1f4485969) ] add missing :output-feature-set options, bump default
- [ [`4e19b`](https://github.com/thheller/shadow-cljs/commit/4e19b28c30a01a0c4ec6c14c6148ddf2b207082e) ] add and fix tests for previous commit
- [ [`c2c18`](https://github.com/thheller/shadow-cljs/commit/c2c1804edd5dcdc0e188115dd95e693df48201ac) ] fix "browser" overrides with false in package.json not working
- [ [`b9992`](https://github.com/thheller/shadow-cljs/commit/b9992b4372d76eab690ea9f23093dffa49aed56e) ] fix REPL losing line information
- [ [`ea6e4`](https://github.com/thheller/shadow-cljs/commit/ea6e4eb581add248ed294514ca37516c99de9f45) ] stop watching .gitlibs for changes
- [ [`05f2c`](https://github.com/thheller/shadow-cljs/commit/05f2cd1c525fea02781eef4329d5d7823be7f8a0) ] add very basic js-await macro for promise interop

## [2.19.0](https://github.com/thheller/shadow-cljs/compare/508f833975f5423a02ed95402e09cafaefe8d773...653dfa0cea96c496acc9a204ce644de284d69c50) - 2022-05-16
- [ [`653df`](https://github.com/thheller/shadow-cljs/commit/653dfa0cea96c496acc9a204ce644de284d69c50) ] cljs + closure compiler bump
- [ [`0421c`](https://github.com/thheller/shadow-cljs/commit/0421c93732669a3cab4397dedb90e1ce149a8f2f) ] fix issue when using multiple symbol$suffix requires
- [ [`3af21`](https://github.com/thheller/shadow-cljs/commit/3af216620ad31320b1b6e6b6d053a161e535a9b7) ] reverd closure-compiler bump, have to wait for cljs release
- [ [`52c98`](https://github.com/thheller/shadow-cljs/commit/52c98f408a0700440e4eb32047a1a60c0b2473fb) ] dependency bump
- [ [`4deba`](https://github.com/thheller/shadow-cljs/commit/4debab4a2cd14bbe427ed47c1c71b3a9b3cfc623) ] remove unused old workspaces dep
- [ [`0f957`](https://github.com/thheller/shadow-cljs/commit/0f95716ddd98975aed33ddb6b712495bce2e8813) ] update clojure dep, older versions still fine
- [ [`1a8a1`](https://github.com/thheller/shadow-cljs/commit/1a8a1c901bc100da14d19410fde042bc1adee291) ] add warnings for misconfigured :build-hooks
- [ [`b1d56`](https://github.com/thheller/shadow-cljs/commit/b1d564f2987ee5ff281f8114f22644a2ab5f770e) ] fix REPL locking up after read failure
- [ [`0ef03`](https://github.com/thheller/shadow-cljs/commit/0ef03e8341c9786d2dfac37bb7ba49d5fe21f020) ] remove old unused&broken ultility script
- [ [`508f8`](https://github.com/thheller/shadow-cljs/commit/508f833975f5423a02ed95402e09cafaefe8d773) ] update caniuse-lite to it shuts up about being outdated

## [2.18.0](https://github.com/thheller/shadow-cljs/compare/051b5ecc7d701ef45b3bbc839685ac6ea03d4ad7...6de144a6d9e5830dd12d8e8400c9f64fa38dddbd) - 2022-04-04
- [ [`6de14`](https://github.com/thheller/shadow-cljs/commit/6de144a6d9e5830dd12d8e8400c9f64fa38dddbd) ] closure-compiler bump, macos directory-watcher bump
- [ [`e2ffc`](https://github.com/thheller/shadow-cljs/commit/e2ffccaf3ef94cc4d64ace0cbb3b97dd43189a07) ] remove leftover debug log
- [ [`bb2c5`](https://github.com/thheller/shadow-cljs/commit/bb2c511fdb6470a2041e5cb5ace91f06c55f1065) ] fix deps.edn
- [ [`c8994`](https://github.com/thheller/shadow-cljs/commit/c89949557e1006df36d9ed2dbd4d479fbf19a580) ] sync deps.edn file
- [ [`6daff`](https://github.com/thheller/shadow-cljs/commit/6daffeae50d0b7f029aad8e975997b20223955dc) ] dependency trim: graal-js, slightly less function npm-deps
- [ [`1af07`](https://github.com/thheller/shadow-cljs/commit/1af078975c359f293d722c7a0d2a1fac68358a0f) ] dependency trim: pathom, remove graph api
- [ [`051b5`](https://github.com/thheller/shadow-cljs/commit/051b5ecc7d701ef45b3bbc839685ac6ea03d4ad7) ] improve nrepl init failure error

## [2.17.8](https://github.com/thheller/shadow-cljs/compare/4110f44cf93d15d75eedba099aebf5607c58ec42...4110f44cf93d15d75eedba099aebf5607c58ec42) - 2022-03-07
- [ [`4110f`](https://github.com/thheller/shadow-cljs/commit/4110f44cf93d15d75eedba099aebf5607c58ec42) ] fix npm resolve regression for nested packages

## [2.17.7](https://github.com/thheller/shadow-cljs/compare/368b6d6252d31bcac95c89d8aaa8bd672f159add...f27699a875bb11377a3c21f57d92ad9ee7b41b0c) - 2022-03-06
- [ [`f2769`](https://github.com/thheller/shadow-cljs/commit/f27699a875bb11377a3c21f57d92ad9ee7b41b0c) ] add missing :js-package-dir for neste pkg
- [ [`0b03b`](https://github.com/thheller/shadow-cljs/commit/0b03b5d582bb218ecf8a9fe5682612e1ef9915f6) ] fix npm resolve not using browser overrides in nested packages
- [ [`06033`](https://github.com/thheller/shadow-cljs/commit/060337d696906da21a37d356b80e45fb5b210b3a) ] add UI debug helper
- [ [`73f0f`](https://github.com/thheller/shadow-cljs/commit/73f0fc5d0a73a5ddeff0b9854b04e21d31f0b927) ] use :stable meta in UI code
- [ [`368b6`](https://github.com/thheller/shadow-cljs/commit/368b6d6252d31bcac95c89d8aaa8bd672f159add) ] add missing test files

## [2.17.6](https://github.com/thheller/shadow-cljs/compare/69b43270c8a9fcdec899822f243a9e26824f1d5c...7cc3438ca14fc3ff3e5383ee3a5b88ae72b324cf) - 2022-03-03
- [ [`7cc34`](https://github.com/thheller/shadow-cljs/commit/7cc3438ca14fc3ff3e5383ee3a5b88ae72b324cf) ] fix npm resolve for nested package requiring other nested pkg
- [ [`d9bda`](https://github.com/thheller/shadow-cljs/commit/d9bda36cc4fa1eb4e0da36de2f51a08aed8a337a) ] fix build reports
- [ [`69b43`](https://github.com/thheller/shadow-cljs/commit/69b43270c8a9fcdec899822f243a9e26824f1d5c) ] fix externs inference warnings for goog.module imports

## [2.17.5](https://github.com/thheller/shadow-cljs/compare/41d56e07706e0da651cdefc7095fb7153d4789df...41d56e07706e0da651cdefc7095fb7153d4789df) - 2022-02-24
- [ [`41d56`](https://github.com/thheller/shadow-cljs/commit/41d56e07706e0da651cdefc7095fb7153d4789df) ] fix npm resolve using index.js over package.json in nested dirs

## [2.17.4](https://github.com/thheller/shadow-cljs/compare/e0a4d612cca337c55869be00a78a95da97424285...8ee15e0dbaa263b828339bdce9296d99cfe4de78) - 2022-02-20
- [ [`8ee15`](https://github.com/thheller/shadow-cljs/commit/8ee15e0dbaa263b828339bdce9296d99cfe4de78) ] fix error message for failed npm require
- [ [`54d4b`](https://github.com/thheller/shadow-cljs/commit/54d4bf46d7dafd8c8c047ee64e1287f582d629be) ] fix bootstrap not loading goog.module properly
- [ [`9b1f5`](https://github.com/thheller/shadow-cljs/commit/9b1f5295db8458d5d06890b4761e1ecf71c3a7a0) ] fix :bootstrap release not converting goog.module properly
- [ [`50e0a`](https://github.com/thheller/shadow-cljs/commit/50e0a31e1f328d6d9e728dce5c39f20a30202b1d) ] Bump tailwind-css (to 3.0.18) and associated packges (#984)
- [ [`e0a4d`](https://github.com/thheller/shadow-cljs/commit/e0a4d612cca337c55869be00a78a95da97424285) ] bump various dependencies

## [2.17.3](https://github.com/thheller/shadow-cljs/compare/7e98d971a79e1a98ed2ab13e44c74fda52119e62...56572a37cf94c8c380ef36edcf863d4a1df93d8c) - 2022-02-17
- [ [`56572`](https://github.com/thheller/shadow-cljs/commit/56572a37cf94c8c380ef36edcf863d4a1df93d8c) ] use eval loader in worker modules as well
- [ [`7e98d`](https://github.com/thheller/shadow-cljs/commit/7e98d971a79e1a98ed2ab13e44c74fda52119e62) ] add shadow-remote nrepl bridge

## [2.17.2](https://github.com/thheller/shadow-cljs/compare/e0ca8d2cde2710788ebe79f15d87403e66bea836...e0ca8d2cde2710788ebe79f15d87403e66bea836) - 2022-02-12
- [ [`e0ca8`](https://github.com/thheller/shadow-cljs/commit/e0ca8d2cde2710788ebe79f15d87403e66bea836) ] fix ns require alias conflicting with refer

## [2.17.1](https://github.com/thheller/shadow-cljs/compare/40eba83f6b6be9635f766d0b55c8f311accbcd3e...0e7fb921b0b8e5d7f133ecd032ca0916355224ad) - 2022-02-10
- [ [`0e7fb`](https://github.com/thheller/shadow-cljs/commit/0e7fb921b0b8e5d7f133ecd032ca0916355224ad) ] maybe fix npm/find-resource stackoverflow?
- [ [`40eba`](https://github.com/thheller/shadow-cljs/commit/40eba83f6b6be9635f766d0b55c8f311accbcd3e) ] relax ns alias check for cljs.js can compile again

## [2.17.0](https://github.com/thheller/shadow-cljs/compare/c2c9d6a12d093d8157e39fd7aaabec854ee702bd...39dc5397f8af8f7b7b2880bc85a41b8a22e81c07) - 2022-02-06
- [ [`39dc5`](https://github.com/thheller/shadow-cljs/commit/39dc5397f8af8f7b7b2880bc85a41b8a22e81c07) ] fix ReplaceCLJSConstants pass
- [ [`a48db`](https://github.com/thheller/shadow-cljs/commit/a48db40783b210fa20c68b4d8bafff4817eccae7) ] fix classpath require exception using unresolved paths
- [ [`ed908`](https://github.com/thheller/shadow-cljs/commit/ed908d820f5571ec5544f15aabd622cfca5b4f4e) ] rewrite npm resolve to prepare for package.json exports
- [ [`1259a`](https://github.com/thheller/shadow-cljs/commit/1259a026b0c9a310fd6b78d991b68afea9ba0e3f) ] fix broken alias check
- [ [`d976f`](https://github.com/thheller/shadow-cljs/commit/d976f2ee9976b6be8353dd2b22c07138ecb279d8) ] add some conflict checks for :as-alias
- [ [`0c1f2`](https://github.com/thheller/shadow-cljs/commit/0c1f2676a6df357cb605effb3f489ec894f6593a) ] fixup :as-alias support to match CLJ-2665
- [ [`7bc76`](https://github.com/thheller/shadow-cljs/commit/7bc7677a88d3068fd22258bc42c41444e89f215d) ] closure-compiler bump v20220104
- [ [`c2c9d`](https://github.com/thheller/shadow-cljs/commit/c2c9d6a12d093d8157e39fd7aaabec854ee702bd) ] fix shadow$bridge undeclared warning

## [2.16.12](https://github.com/thheller/shadow-cljs/compare/8a93b43b43f4242b6f5f0406171155f575648860...8a93b43b43f4242b6f5f0406171155f575648860) - 2022-01-02
- [ [`8a93b`](https://github.com/thheller/shadow-cljs/commit/8a93b43b43f4242b6f5f0406171155f575648860) ] Check filename instead of path (#975)

## [2.16.11](https://github.com/thheller/shadow-cljs/compare/1740364adb55ae08516a221698e8fa319a6d1fd2...f67da8342cfb0b1ea8969cfee4a4d39622f53759) - 2022-01-02
- [ [`f67da`](https://github.com/thheller/shadow-cljs/commit/f67da8342cfb0b1ea8969cfee4a4d39622f53759) ] ignore emacs temp files on windows
- [ [`17403`](https://github.com/thheller/shadow-cljs/commit/1740364adb55ae08516a221698e8fa319a6d1fd2) ] add date to changelog generator

## [2.16.10](https://github.com/thheller/shadow-cljs/compare/e9093aed586d5fb49efd94555787f0bef08fb164...e9093aed586d5fb49efd94555787f0bef08fb164) - 2021-12-28
- [ [`e9093`](https://github.com/thheller/shadow-cljs/commit/e9093aed586d5fb49efd94555787f0bef08fb164) ] add :dev/asset-load devtools hook

## [2.16.9](https://github.com/thheller/shadow-cljs/compare/69abb8e088cce7db7a901d9371225a0b98aa424b...9dabe73440dde2301b4429075b4d34642ca990a1) - 2021-12-20
- [ [`9dabe`](https://github.com/thheller/shadow-cljs/commit/9dabe73440dde2301b4429075b4d34642ca990a1) ] cljs bump 1.10.914
- [ [`69abb`](https://github.com/thheller/shadow-cljs/commit/69abb8e088cce7db7a901d9371225a0b98aa424b) ] fix inspect eval not showing errors properly

## [2.16.8](https://github.com/thheller/shadow-cljs/compare/a2b2e4bd509fc881da951e9a00da6e355dbf94b3...a2b2e4bd509fc881da951e9a00da6e355dbf94b3) - 2021-12-09
- [ [`a2b2e`](https://github.com/thheller/shadow-cljs/commit/a2b2e4bd509fc881da951e9a00da6e355dbf94b3) ] add timer for ReplaceCLJSConstants pass

## [2.16.7](https://github.com/thheller/shadow-cljs/compare/91a3889b154c8afedfa017325f69bc21f1588102...91a3889b154c8afedfa017325f69bc21f1588102) - 2021-12-05
- [ [`91a38`](https://github.com/thheller/shadow-cljs/commit/91a3889b154c8afedfa017325f69bc21f1588102) ] fix gitlib references on different disk breaking everything

## [2.16.6](https://github.com/thheller/shadow-cljs/compare/1d95fa224e23f9ff33a17bb97aa73ca0c8e772e4...086ee94f9a4e5fac66a9c4fa985364a40ab41ce4) - 2021-11-22
- [ [`086ee`](https://github.com/thheller/shadow-cljs/commit/086ee94f9a4e5fac66a9c4fa985364a40ab41ce4) ] add :js-options :hide-warnings-for to hide closure warnings
- [ [`1d95f`](https://github.com/thheller/shadow-cljs/commit/1d95fa224e23f9ff33a17bb97aa73ca0c8e772e4) ] fix legacy dev http servers not starting

## [2.16.5](https://github.com/thheller/shadow-cljs/compare/378028ddb79913a5f8104306b0a9c8a691a2977c...378028ddb79913a5f8104306b0a9c8a691a2977c) - 2021-11-19
- [ [`37802`](https://github.com/thheller/shadow-cljs/commit/378028ddb79913a5f8104306b0a9c8a691a2977c) ] adjust :dev-http config handling so it respects direct API config

## [2.16.4](https://github.com/thheller/shadow-cljs/compare/7e142f462c2a42e921eba9ac103b77c3c704e3f5...7e142f462c2a42e921eba9ac103b77c3c704e3f5) - 2021-11-12
- [ [`7e142`](https://github.com/thheller/shadow-cljs/commit/7e142f462c2a42e921eba9ac103b77c3c704e3f5) ] add workaround for google/closure-compiler#3890

## [2.16.3](https://github.com/thheller/shadow-cljs/compare/2a3292e69d7bdaea6e9101368580e5cb737e6488...2a3292e69d7bdaea6e9101368580e5cb737e6488) - 2021-11-11
- [ [`2a329`](https://github.com/thheller/shadow-cljs/commit/2a3292e69d7bdaea6e9101368580e5cb737e6488) ] work around certain npm filenames ending up too long for linux

## [2.16.2](https://github.com/thheller/shadow-cljs/compare/3eb99f1d368a441fa13075291199027e851fa17c...3eb99f1d368a441fa13075291199027e851fa17c) - 2021-11-10
- [ [`3eb99`](https://github.com/thheller/shadow-cljs/commit/3eb99f1d368a441fa13075291199027e851fa17c) ] fix storybook load issue related to goog.module and :npm-module

## [2.16.1](https://github.com/thheller/shadow-cljs/compare/1938e172305b4be506d9364235e498fd20a4b619...4a99d8a6ab911cd3bdf83d91a17ea888aa6f3e32) - 2021-11-10
- [ [`4a99d`](https://github.com/thheller/shadow-cljs/commit/4a99d8a6ab911cd3bdf83d91a17ea888aa6f3e32) ] workaround for npm incremental compile issues
- [ [`1938e`](https://github.com/thheller/shadow-cljs/commit/1938e172305b4be506d9364235e498fd20a4b619) ] bump shadow-undertow

## [2.16.0](https://github.com/thheller/shadow-cljs/compare/21ab24f47de5c5a01b88d51214037c2fd7e41ccf...21ab24f47de5c5a01b88d51214037c2fd7e41ccf) - 2021-11-06
- [ [`21ab2`](https://github.com/thheller/shadow-cljs/commit/21ab24f47de5c5a01b88d51214037c2fd7e41ccf) ] add :global-goog-object&array to cache affecting options

## [2.15.13](https://github.com/thheller/shadow-cljs/compare/1717e3fee9add61fd935c4d9ba835ad9396021b5...37574264eb1bf1e7f8b1bb594d0aa95fdc74d190) - 2021-11-04
- [ [`37574`](https://github.com/thheller/shadow-cljs/commit/37574264eb1bf1e7f8b1bb594d0aa95fdc74d190) ] core.async bump
- [ [`7fefe`](https://github.com/thheller/shadow-cljs/commit/7fefe5b3f604ff11d33539e4899e160dd1ff6eb0) ] cljs bump 1.10.891 + various closure updates
- [ [`4530e`](https://github.com/thheller/shadow-cljs/commit/4530ed1b71ffa63a0f0b8d496a474a82f4db942d) ] Fix server<->client Unicode data issue on MS-Windows (#945)
- [ [`1717e`](https://github.com/thheller/shadow-cljs/commit/1717e3fee9add61fd935c4d9ba835ad9396021b5) ] clear hud errors after compile success (#947)

## [2.15.12, fix bad 2.15.11 release](https://github.com/thheller/shadow-cljs/compare/...) - 2021-10-10

## [2.15.11](https://github.com/thheller/shadow-cljs/compare/22c3f41f86a5ba4754eb774da9c03ec91043d897...60d071ad968465ad69c2bef3ba64ff056ce09850) - 2021-10-10
- [ [`60d07`](https://github.com/thheller/shadow-cljs/commit/60d071ad968465ad69c2bef3ba64ff056ce09850) ] shadow-cljs pom should account for maven repositories
- [ [`a3ebb`](https://github.com/thheller/shadow-cljs/commit/a3ebb64e5524e387412af5ab674ce074e65bef71) ] fix react-native websocket not connecting properly
- [ [`22c3f`](https://github.com/thheller/shadow-cljs/commit/22c3f41f86a5ba4754eb774da9c03ec91043d897) ] attempt to make browser load errors more legible

## [2.15.10](https://github.com/thheller/shadow-cljs/compare/f06b383dd6ef888cc4ba357ce2b7f248f2238371...1708acb21bcdae244b50293d17633ce35a78a467) - 2021-09-19
- [ [`1708a`](https://github.com/thheller/shadow-cljs/commit/1708acb21bcdae244b50293d17633ce35a78a467) ] delay npm subsystem init until configure
- [ [`ca245`](https://github.com/thheller/shadow-cljs/commit/ca2451fafd5ed8235eec505edc2fb85518609ed6) ] explore support for CLJS
- [ [`b8466`](https://github.com/thheller/shadow-cljs/commit/b8466f176b6e8f6bf61cd70fbfbaf4762c581eb1) ] [UI] add experiment runtime explore
- [ [`9e6d8`](https://github.com/thheller/shadow-cljs/commit/9e6d8349a64396d35014406281057e8233f237a6) ] add preliminary support for CLJ-2123
- [ [`f06b3`](https://github.com/thheller/shadow-cljs/commit/f06b383dd6ef888cc4ba357ce2b7f248f2238371) ] make inspect sort slightly smarter

## [2.15.9](https://github.com/thheller/shadow-cljs/compare/724ba3dbb731fb5238c10d7b078de4026b3269d2...b2c58ecaf0ae05b356b73e54f4104bb3c8aa0cc1) - 2021-09-06
- [ [`b2c58`](https://github.com/thheller/shadow-cljs/commit/b2c58ecaf0ae05b356b73e54f4104bb3c8aa0cc1) ] added `,' to PS list of escape chars. (#931)
- [ [`724ba`](https://github.com/thheller/shadow-cljs/commit/724ba3dbb731fb5238c10d7b078de4026b3269d2) ] update babel & babel-worker

## [2.15.8](https://github.com/thheller/shadow-cljs/compare/f303b025d2c438e5c8aaa545571d61404643273b...f303b025d2c438e5c8aaa545571d61404643273b) - 2021-09-01
- [ [`f303b`](https://github.com/thheller/shadow-cljs/commit/f303b025d2c438e5c8aaa545571d61404643273b) ] fix greedy builds not actually picking up new files

## [2.15.7](https://github.com/thheller/shadow-cljs/compare/720600fa12c4c59de9d625eeab8238b79d255da7...720600fa12c4c59de9d625eeab8238b79d255da7) - 2021-09-01
- [ [`72060`](https://github.com/thheller/shadow-cljs/commit/720600fa12c4c59de9d625eeab8238b79d255da7) ] fix :npm-module :ns-regexp

## [2.15.6](https://github.com/thheller/shadow-cljs/compare/02ece2f93aa9954e6483faf12019cdf4d1388e0c...ae6fd0ea49f4495effc965d9bb61ae30cfec407c) - 2021-08-29
- [ [`ae6fd`](https://github.com/thheller/shadow-cljs/commit/ae6fd0ea49f4495effc965d9bb61ae30cfec407c) ] fix REPL interfering with hot-reload cycle
- [ [`a191c`](https://github.com/thheller/shadow-cljs/commit/a191c3fc59cab85754eb372b627351076c02ba1d) ] avoid writing to stdout when possible in npm cli script
- [ [`02ece`](https://github.com/thheller/shadow-cljs/commit/02ece2f93aa9954e6483faf12019cdf4d1388e0c) ] smarten up react-native websocket connect logic

## [2.15.5](https://github.com/thheller/shadow-cljs/compare/45be978b5ad55e3bdd0dc3ddcde57bb5622a2aed...8af80862c75f52009e76d8862d3cb3d4d94b0063) - 2021-08-19
- [ [`8af80`](https://github.com/thheller/shadow-cljs/commit/8af80862c75f52009e76d8862d3cb3d4d94b0063) ] fix file watch on macOS
- [ [`4dfb0`](https://github.com/thheller/shadow-cljs/commit/4dfb0b1080e5d66bbe9e9f6221e623577c17e91c) ] remove some leftover tap>
- [ [`45be9`](https://github.com/thheller/shadow-cljs/commit/45be978b5ad55e3bdd0dc3ddcde57bb5622a2aed) ] rewrite :npm-module to produce fewer "modules"

## [2.15.4](https://github.com/thheller/shadow-cljs/compare/e381b50d142dcb93bacc7bf8804c9a1db5325d4b...f7e5b47bc2cb0b7aeac895e8febfc61c31d1652f) - 2021-08-18
- [ [`f7e5b`](https://github.com/thheller/shadow-cljs/commit/f7e5b47bc2cb0b7aeac895e8febfc61c31d1652f) ] add faster file watcher for macOS
- [ [`d962c`](https://github.com/thheller/shadow-cljs/commit/d962c2a512fcd1d5b76114f4b8c1ba36fafda89a) ] Revert "add faster file watcher for macOS"
- [ [`a9bf3`](https://github.com/thheller/shadow-cljs/commit/a9bf3d973e8833b1c7738370380d9b4103475743) ] add faster file watcher for macOS
- [ [`e381b`](https://github.com/thheller/shadow-cljs/commit/e381b50d142dcb93bacc7bf8804c9a1db5325d4b) ] fix build-reports incorrectly marking CLJS deps as duplicates

## [2.15.3](https://github.com/thheller/shadow-cljs/compare/2dcb4b436a772764d38422383b43923871bdd94e...de52880090c7840ee30eb5369173c1bd7dd3055c) - 2021-08-08
- [ [`de528`](https://github.com/thheller/shadow-cljs/commit/de52880090c7840ee30eb5369173c1bd7dd3055c) ] remove some old obsolete foreign-libs references
- [ [`82100`](https://github.com/thheller/shadow-cljs/commit/8210087d56034487758769b5b5fb0b9fec0ab569) ] add :js-provider :import for :target :esm
- [ [`39334`](https://github.com/thheller/shadow-cljs/commit/3933495e8267bd7160dce77065b7641325caae15) ] merge CLI -d dependencies later
- [ [`d0ae3`](https://github.com/thheller/shadow-cljs/commit/d0ae3f63863588fb18b71eed14e1ae83810e3f41) ] fix :esm imports not working cross modules
- [ [`2dcb4`](https://github.com/thheller/shadow-cljs/commit/2dcb4b436a772764d38422383b43923871bdd94e) ] add module as a default externs, specifically for transit-js

## [2.15.2](https://github.com/thheller/shadow-cljs/compare/e297d9c66f4c00859a166305075697a89d65eb40...e297d9c66f4c00859a166305075697a89d65eb40) - 2021-07-19
- [ [`e297d`](https://github.com/thheller/shadow-cljs/commit/e297d9c66f4c00859a166305075697a89d65eb40) ] bump CLJS and some others deps

## [2.15.1](https://github.com/thheller/shadow-cljs/compare/d56bd6c3680e8e8a501ad6a0e79e82f7fe244a9a...d56bd6c3680e8e8a501ad6a0e79e82f7fe244a9a) - 2021-07-12
- [ [`d56bd`](https://github.com/thheller/shadow-cljs/commit/d56bd6c3680e8e8a501ad6a0e79e82f7fe244a9a) ] limit new node-resolve rules to actual node_modules files only

## [2.15.0](https://github.com/thheller/shadow-cljs/compare/844df3fa52bbb5c587b0eb949dc0822a5fe940a5...2c4b5a2682ef78bb80c56d75b67bd0ff742f2d61) - 2021-07-10
- [ [`2c4b5`](https://github.com/thheller/shadow-cljs/commit/2c4b5a2682ef78bb80c56d75b67bd0ff742f2d61) ] fixup build reports to show now possible duplicates
- [ [`a41df`](https://github.com/thheller/shadow-cljs/commit/a41dfc710db58c817d6cf36a31314a19d746db0d) ] cleanup
- [ [`addfd`](https://github.com/thheller/shadow-cljs/commit/addfdd84744e7038452c2dd4935503297a82dae0) ] oops, add uncommit npm check versions ns
- [ [`a1823`](https://github.com/thheller/shadow-cljs/commit/a18230853f3a4b3a923fb129b1ed5f61f2c0bc31) ] remove debug tap>
- [ [`122bf`](https://github.com/thheller/shadow-cljs/commit/122bf58397146597f69cce677af7ed7219425b85) ] move npm version check out of browser target
- [ [`844df`](https://github.com/thheller/shadow-cljs/commit/844df3fa52bbb5c587b0eb949dc0822a5fe940a5) ] change default npm resolve rules to match node/webpack

## [2.14.6](https://github.com/thheller/shadow-cljs/compare/bff923a5c0438326eebf0630706c35b644e66fc7...6587e85d993652e1675c060664fbfcf8a9890b97) - 2021-07-01
- [ [`6587e`](https://github.com/thheller/shadow-cljs/commit/6587e85d993652e1675c060664fbfcf8a9890b97) ] Add full stack single page app example (#911)
- [ [`cd280`](https://github.com/thheller/shadow-cljs/commit/cd28043bcc94e9d1a499791c377fe837734b2ec1) ] Add racing-game-cljs to Examples (#904)
- [ [`bff92`](https://github.com/thheller/shadow-cljs/commit/bff923a5c0438326eebf0630706c35b644e66fc7) ] change goog.provide/require overrides

## [2.14.5](https://github.com/thheller/shadow-cljs/compare/dce27ed2736cc80c38462849b8ff8431b6c740a6...bf5f474fab0da5b9fa61731484ec7c1c978f52bc) - 2021-06-13
- [ [`bf5f4`](https://github.com/thheller/shadow-cljs/commit/bf5f474fab0da5b9fa61731484ec7c1c978f52bc) ] add missing newline
- [ [`17b0c`](https://github.com/thheller/shadow-cljs/commit/17b0c06131ac8d3df78f5b28daaa82d2d269caf1) ] fix shadow-cljs start hanging when server fails to start
- [ [`16954`](https://github.com/thheller/shadow-cljs/commit/169544d83425422c45c5d28078a1adbd733bfcda) ] oops, missed on -M place
- [ [`c3f73`](https://github.com/thheller/shadow-cljs/commit/c3f738eab4aeae51d2b9ed23abfe5c35cea47275) ] use proper -M when using deps.edn
- [ [`205c8`](https://github.com/thheller/shadow-cljs/commit/205c8e734946b780ca72e7f755068860d3edd27c) ] enhance externs inference for aliased types
- [ [`2dec8`](https://github.com/thheller/shadow-cljs/commit/2dec89d372fd034ff71b0e478d7126262bcfaf06) ] fix build report hook
- [ [`721f7`](https://github.com/thheller/shadow-cljs/commit/721f71dce7ca55bf9b3c5d80b38709e68270ef4a) ] fix empty marker protocols causing infer warnings
- [ [`c9a05`](https://github.com/thheller/shadow-cljs/commit/c9a050940442f355664ea8dbfed729c4ee0df0be) ] add setup/test for current nested npm resolve rules
- [ [`766c0`](https://github.com/thheller/shadow-cljs/commit/766c04fd23708e93a5c4453135a0a34cacbcf39a) ] bump ws dep in shadow-cljs npm package
- [ [`20d28`](https://github.com/thheller/shadow-cljs/commit/20d287ec08d70ddf7e95b6e6943b15484a6c5720) ] CI cache fix?
- [ [`dce27`](https://github.com/thheller/shadow-cljs/commit/dce27ed2736cc80c38462849b8ff8431b6c740a6) ] karma: Add karma test to test-project run (#815)

## [2.14.4](https://github.com/thheller/shadow-cljs/compare/113a6b9c0a28311cbff31ddca1d15520e8ead784...18d99d0683a29b727c403747a14bf5b4da81c94e) - 2021-06-07
- [ [`18d99`](https://github.com/thheller/shadow-cljs/commit/18d99d0683a29b727c403747a14bf5b4da81c94e) ] fix build report html title
- [ [`be3de`](https://github.com/thheller/shadow-cljs/commit/be3de268eb93fa7d9ea37336c5468c0318abfbd8) ] further group Project Files by classpath in build report
- [ [`60d59`](https://github.com/thheller/shadow-cljs/commit/60d593c1f599c5e6887c937e853ad1eaec39c76f) ] fix :external index generation
- [ [`2da3c`](https://github.com/thheller/shadow-cljs/commit/2da3cc42816fe1971773b0be5f71cb53b0631520) ] restrict GlobalsAsVar hack to only names
- [ [`f17f6`](https://github.com/thheller/shadow-cljs/commit/f17f697bbfb225d714800bb8f6f0fb9d93844316) ] extend previous commit to :js-provider :external
- [ [`c2dd4`](https://github.com/thheller/shadow-cljs/commit/c2dd487c6933dae02712905df6ec2bc850c18e73) ] treat symbols as valid requires when listed in npm-deps
- [ [`8169e`](https://github.com/thheller/shadow-cljs/commit/8169e663c0338ab01b31726b0cfcd7005563c581) ] add pass rewriting let/const to var for rewritten ESM code
- [ [`113a6`](https://github.com/thheller/shadow-cljs/commit/113a6b9c0a28311cbff31ddca1d15520e8ead784) ] add failing test for #894, not sure how to fix it

## [2.14.3](https://github.com/thheller/shadow-cljs/compare/62a394fb009f4ba2203333123d1fcbe889c07bc3...07c5a498864a981489e760a599a89fd49a1dca1c) - 2021-06-04
- [ [`07c5a`](https://github.com/thheller/shadow-cljs/commit/07c5a498864a981489e760a599a89fd49a1dca1c) ] rewrite repl load-file to eval one form at a time
- [ [`e6c7d`](https://github.com/thheller/shadow-cljs/commit/e6c7d36dc4a870c0b64286e0b590e69c06c63d43) ] add workarround fix for google/closure-compiler#3825
- [ [`33fc6`](https://github.com/thheller/shadow-cljs/commit/33fc6f4ff2696f77c9e036f08fb1ad07ea2c6e9b) ] remove source map reset hack
- [ [`66c2b`](https://github.com/thheller/shadow-cljs/commit/66c2ba76b4e01ef107e489542d9b6a377d964315) ] fix shadow.loader warning
- [ [`62a39`](https://github.com/thheller/shadow-cljs/commit/62a394fb009f4ba2203333123d1fcbe889c07bc3) ] remove shared ui module

## [2.14.2](https://github.com/thheller/shadow-cljs/compare/cc8625f6c4c1aca9b51a84f1760e1c88dab7173b...f3c860517dc0736bece34d5e6a73ff29679ae2a0) - 2021-05-28
- [ [`f3c86`](https://github.com/thheller/shadow-cljs/commit/f3c860517dc0736bece34d5e6a73ff29679ae2a0) ] add shadow.loader.TEST define
- [ [`cc862`](https://github.com/thheller/shadow-cljs/commit/cc8625f6c4c1aca9b51a84f1760e1c88dab7173b) ] restore JS property collection

## [2.14.1](https://github.com/thheller/shadow-cljs/compare/4c22726277a9d8408d9b58a891e3951d587c8f3c...4c22726277a9d8408d9b58a891e3951d587c8f3c) - 2021-05-27
- [ [`4c227`](https://github.com/thheller/shadow-cljs/commit/4c22726277a9d8408d9b58a891e3951d587c8f3c) ] add missing cljs.analyzer/resolve-var hack arity

## [2.14.0 - BEWARE CLJS and Closure Compiler updates!](https://github.com/thheller/shadow-cljs/compare/244035daa32bef22c947f583e514a68c0d39952f...d445ff0d9c77bacb2c60cf342abbd9ad9bbfe0d2) - 2021-05-21
- [ [`d445f`](https://github.com/thheller/shadow-cljs/commit/d445ff0d9c77bacb2c60cf342abbd9ad9bbfe0d2) ] remove another dead closure pass
- [ [`7aaf6`](https://github.com/thheller/shadow-cljs/commit/7aaf6e7f3aed22b8f093c527749f8fcbf2b8ba5a) ] remove old unused closure compiler pass
- [ [`2e94d`](https://github.com/thheller/shadow-cljs/commit/2e94d8edc5b092eae8d88176462e28e74b3fe8a1) ] bump CLJS, closure-compiler
- [ [`99186`](https://github.com/thheller/shadow-cljs/commit/99186798e4bd0f822a2fc1696ab863eb09900e01) ] bump some deps
- [ [`24403`](https://github.com/thheller/shadow-cljs/commit/244035daa32bef22c947f583e514a68c0d39952f) ] Add re-frame-flow to Examples (#888)

## [2.13.0](https://github.com/thheller/shadow-cljs/compare/5b385e68bcfd2b821a08a794c7e6e60f920b9025...5b385e68bcfd2b821a08a794c7e6e60f920b9025) - 2021-05-13
- [ [`5b385`](https://github.com/thheller/shadow-cljs/commit/5b385e68bcfd2b821a08a794c7e6e60f920b9025) ] bump default :output-feature-set to :es6

## [2.12.7](https://github.com/thheller/shadow-cljs/compare/3f7b758f4854d23a89655c79bf9f83b8dbbec131...a592772560696d5f3b3658c4c6eb6c04e1fd46dd) - 2021-05-12
- [ [`a5927`](https://github.com/thheller/shadow-cljs/commit/a592772560696d5f3b3658c4c6eb6c04e1fd46dd) ] remove old compiler hack
- [ [`3f7b7`](https://github.com/thheller/shadow-cljs/commit/3f7b758f4854d23a89655c79bf9f83b8dbbec131) ] Add :browser-2020 and :browser-2021 options for :output-feature-set. (#884)

## [2.12.6](https://github.com/thheller/shadow-cljs/compare/795af55bc5417f9695658fe1089725a361775236...3ae294bb8adbe6e0d54e47afac4235502378b22d) - 2021-05-10
- [ [`3ae29`](https://github.com/thheller/shadow-cljs/commit/3ae294bb8adbe6e0d54e47afac4235502378b22d) ] fix build-report hook api
- [ [`726c1`](https://github.com/thheller/shadow-cljs/commit/726c185fd9fc70e278a205991e27a80739685962) ] make build report usable via :build-hooks
- [ [`160a4`](https://github.com/thheller/shadow-cljs/commit/160a4705c59b78741a9023a9cb29c269b2db621b) ] rewrite build-reports using shadow-grove, add trace feature
- [ [`13e40`](https://github.com/thheller/shadow-cljs/commit/13e407cd08cbd32c57fac2ab996128f82e098b65) ] bring back util ns search fn
- [ [`6b662`](https://github.com/thheller/shadow-cljs/commit/6b662c0e05fb560216984746818d658fea8b2963) ] add ns-var-clash check to regular parsing
- [ [`d5b5c`](https://github.com/thheller/shadow-cljs/commit/d5b5c2d7a81bd225c5f60a7367382380869c3391) ] bump a few dependencies
- [ [`795af`](https://github.com/thheller/shadow-cljs/commit/795af55bc5417f9695658fe1089725a361775236) ] adjust UI to use new init/start logic, just render now

## [2.12.5](https://github.com/thheller/shadow-cljs/compare/9137fe3b58e9c0fcfbb9d8cdb3271211fb860d49...6517b958b85f65203b7f9f63dafe8a741c106cd4) - 2021-04-13
- [ [`6517b`](https://github.com/thheller/shadow-cljs/commit/6517b958b85f65203b7f9f63dafe8a741c106cd4) ] also :force-library-injection in development builds
- [ [`9137f`](https://github.com/thheller/shadow-cljs/commit/9137fe3b58e9c0fcfbb9d8cdb3271211fb860d49) ] migrate UI code to new metadata approach, rename some stuff

## [2.12.4](https://github.com/thheller/shadow-cljs/compare/436b991ffb9a49c884ece0afd59ed41654d892eb...9a4144b65141ecdacc28fefed53fe968035363e5) - 2021-04-09
- [ [`9a414`](https://github.com/thheller/shadow-cljs/commit/9a4144b65141ecdacc28fefed53fe968035363e5) ] allow using files or resources for --config-merge
- [ [`436b9`](https://github.com/thheller/shadow-cljs/commit/436b991ffb9a49c884ece0afd59ed41654d892eb) ] remove uses to stipType* since Closure deprecated or removed it

## [2.12.3](https://github.com/thheller/shadow-cljs/compare/36b30359447391ac6fc360178ad5cd392aa1e1b2...36b30359447391ac6fc360178ad5cd392aa1e1b2) - 2021-04-09
- [ [`36b30`](https://github.com/thheller/shadow-cljs/commit/36b30359447391ac6fc360178ad5cd392aa1e1b2) ] fix faulty require logic regarding magic-syms

## [2.12.2](https://github.com/thheller/shadow-cljs/compare/72176ad9b33c2214cf91bd24b0f90f65aaa3a114...af40121e41ece2aac1fbb9072ebc5d71baa2579b) - 2021-04-08
- [ [`af401`](https://github.com/thheller/shadow-cljs/commit/af40121e41ece2aac1fbb9072ebc5d71baa2579b) ] fix CLJS REPL not handling non-error exceptions
- [ [`89026`](https://github.com/thheller/shadow-cljs/commit/890266310b14f72028c5ee05482887755ffebb0a) ] fix release! api fn
- [ [`57204`](https://github.com/thheller/shadow-cljs/commit/572042c7d4c1736adb356017cebceeaa45c10f81) ] add ignored package.json
- [ [`bbbac`](https://github.com/thheller/shadow-cljs/commit/bbbac5ede9b0cefb48224050a06c673203431685) ] UI fixes
- [ [`72176`](https://github.com/thheller/shadow-cljs/commit/72176ad9b33c2214cf91bd24b0f90f65aaa3a114) ] fix require issue with new $ sugar for :goog requires

## [2.12.1](https://github.com/thheller/shadow-cljs/compare/8521036d1f63317671a203435c028130fa893528...8521036d1f63317671a203435c028130fa893528) - 2021-04-01
- [ [`85210`](https://github.com/thheller/shadow-cljs/commit/8521036d1f63317671a203435c028130fa893528) ] fix npm packages using @export annotations

## [2.12.0 - closure-library update possibly breaking](https://github.com/thheller/shadow-cljs/compare/e043d5be60877bd1d367080a9a41fc9d12364b91...bd66852bd46303f90427eded3eac78549b9e9687) - 2021-03-31
- [ [`bd668`](https://github.com/thheller/shadow-cljs/commit/bd66852bd46303f90427eded3eac78549b9e9687) ] fix js-template not generating proper code
- [ [`864e2`](https://github.com/thheller/shadow-cljs/commit/864e2182da25f3ba95cad293c3f408fe2c1d172e) ] Add a link to updated day8/re-frame-template
- [ [`3f7f3`](https://github.com/thheller/shadow-cljs/commit/3f7f33d012c46b32ffaa1af41d839bd92c69b63f) ] bump cljs,closure-compiler,closure-library
- [ [`109ea`](https://github.com/thheller/shadow-cljs/commit/109ea8858b5180522895b1d32c17e47d8cfff90e) ] enchance js-template to allow tagged templates
- [ [`df04f`](https://github.com/thheller/shadow-cljs/commit/df04f69a024f922bc8c3ce2061d2d5661d80a501) ] adjust UI code for new history impl
- [ [`e043d`](https://github.com/thheller/shadow-cljs/commit/e043d5be60877bd1d367080a9a41fc9d12364b91) ] add experimental support for creating js template strings

## [2.11.26](https://github.com/thheller/shadow-cljs/compare/cda1b21b2b1c99e5dee3a8797316b1d444b67bd7...cda1b21b2b1c99e5dee3a8797316b1d444b67bd7) - 2021-03-26
- [ [`cda1b`](https://github.com/thheller/shadow-cljs/commit/cda1b21b2b1c99e5dee3a8797316b1d444b67bd7) ] allow selective disabling of ssl for :dev-http

## [2.11.25](https://github.com/thheller/shadow-cljs/compare/c292c808ea31af4a05bc2f07724227e68d0fd8b7...0afe7e4083e7a51285e336c2dddcdfb25c7904a3) - 2021-03-24
- [ [`0afe7`](https://github.com/thheller/shadow-cljs/commit/0afe7e4083e7a51285e336c2dddcdfb25c7904a3) ] fix inspect blowing up on misbehaving types
- [ [`27d9f`](https://github.com/thheller/shadow-cljs/commit/27d9f7280d21235e2513022ead9a8f535d658ac8) ] fix incorrect tabindex
- [ [`ac341`](https://github.com/thheller/shadow-cljs/commit/ac34160a2e1b6df2eb1bf23bc7a7815a421f9be1) ] restore basic UI keyboard control for taps
- [ [`18554`](https://github.com/thheller/shadow-cljs/commit/18554e841ec20211c261797a0c68f6ca6b80cb52) ] add packages used for testing
- [ [`c292c`](https://github.com/thheller/shadow-cljs/commit/c292c808ea31af4a05bc2f07724227e68d0fd8b7) ] UI move transit init

## [2.11.24](https://github.com/thheller/shadow-cljs/compare/5a7ec8b07ef90d951873f0e091b63753dbabf36e...a92bbe77bb2606ccffc88f3bd57865e698adc8b1) - 2021-03-21
- [ [`a92bb`](https://github.com/thheller/shadow-cljs/commit/a92bbe77bb2606ccffc88f3bd57865e698adc8b1) ] tailwind/jit for UI css
- [ [`5c51d`](https://github.com/thheller/shadow-cljs/commit/5c51d72282bbf8e606e19a2c155f9b2471e9c182) ] fix UI display issues
- [ [`f7a36`](https://github.com/thheller/shadow-cljs/commit/f7a360d52dfae654ff17d7a5335943beabaf163c) ] add more places for :local-ip config
- [ [`1a41a`](https://github.com/thheller/shadow-cljs/commit/1a41a41be3b4b3e21fc7effcf2d1dd5a3aab247a) ] UI style tweaks
- [ [`be902`](https://github.com/thheller/shadow-cljs/commit/be90276cfde9ca9746527b6517688087ab91d5b4) ] adapt to new event handling code
- [ [`512c1`](https://github.com/thheller/shadow-cljs/commit/512c17bd635cf0d9377a08a39d4869f6ad22126b) ] fix some indentation
- [ [`b0d44`](https://github.com/thheller/shadow-cljs/commit/b0d447fa251d90410d99bf377e2ebdbfc542a796) ] [UI] remove worker, remove streams
- [ [`8d116`](https://github.com/thheller/shadow-cljs/commit/8d116699074dcc5295ec1da94abba59f3f0c7a88) ] link building conduit playlist
- [ [`692a1`](https://github.com/thheller/shadow-cljs/commit/692a106c1ca4b9db5b893a1909b4a7084e9f0752) ] Add shadow-cljs + tailwindcss-jit example (#853)
- [ [`5a7ec`](https://github.com/thheller/shadow-cljs/commit/5a7ec8b07ef90d951873f0e091b63753dbabf36e) ] Add devcards example (#852)

## [2.11.23](https://github.com/thheller/shadow-cljs/compare/0b2b7466a158784878552b9d80d063b2a26fea57...62700720fc82e048442a4abc0a5b99d845d6545b) - 2021-03-11
- [ [`62700`](https://github.com/thheller/shadow-cljs/commit/62700720fc82e048442a4abc0a5b99d845d6545b) ] add :language-in :es-next-in and make it default
- [ [`0b2b7`](https://github.com/thheller/shadow-cljs/commit/0b2b7466a158784878552b9d80d063b2a26fea57) ] adjust UI for moved transit in shadow-grove

## [2.11.22](https://github.com/thheller/shadow-cljs/compare/fdb4ef2d9efb027f8f7697e61ff41fd41248b7b0...76fc5c295c69636cfd110c4837fe4d92e4f3693a) - 2021-03-08
- [ [`76fc5`](https://github.com/thheller/shadow-cljs/commit/76fc5c295c69636cfd110c4837fe4d92e4f3693a) ] properly pass opts for browser-repl/node-repl
- [ [`ece32`](https://github.com/thheller/shadow-cljs/commit/ece32ae8c410b78f778ec26e4473d21e67fbb458) ] add :prompt false config option to shadow/repl
- [ [`fdb4e`](https://github.com/thheller/shadow-cljs/commit/fdb4ef2d9efb027f8f7697e61ff41fd41248b7b0) ] make shadow.cljs.modern self-host compatible

## [2.11.21](https://github.com/thheller/shadow-cljs/compare/f5d8fec986afba3432019fe8d6eb7ef9ccf946aa...339d1606943fb960ed53ab25bb832dc63131c404) - 2021-03-06
- [ [`339d1`](https://github.com/thheller/shadow-cljs/commit/339d1606943fb960ed53ab25bb832dc63131c404) ] fix UI inspect string display
- [ [`f5d8f`](https://github.com/thheller/shadow-cljs/commit/f5d8fec986afba3432019fe8d6eb7ef9ccf946aa) ] fix issue with latest closure-compiler

## [2.11.20, UI fix](https://github.com/thheller/shadow-cljs/compare/8ef958f332c0e0648fd84eb2a3d5205602487f6b...ce5fc4196264c9b20ddb5f743eb19f22a8f15003) - 2021-03-03
- [ [`ce5fc`](https://github.com/thheller/shadow-cljs/commit/ce5fc4196264c9b20ddb5f743eb19f22a8f15003) ] minor UI tweak
- [ [`8ef95`](https://github.com/thheller/shadow-cljs/commit/8ef958f332c0e0648fd84eb2a3d5205602487f6b) ] remove severly outdated shadow.xhr use in HUD

## [2.11.19](https://github.com/thheller/shadow-cljs/compare/d0451aba279689bc74ae5340e83b2466e4bdb237...a5ed0eb465639d9a857640bc414df5eb64f92997) - 2021-03-01
- [ [`a5ed0`](https://github.com/thheller/shadow-cljs/commit/a5ed0eb465639d9a857640bc414df5eb64f92997) ] update UI for latest shadow-grove changes
- [ [`805aa`](https://github.com/thheller/shadow-cljs/commit/805aa215f0d762245db284c37ec323f25b8f0d14) ] add missing package-lock.json files
- [ [`afb9b`](https://github.com/thheller/shadow-cljs/commit/afb9b251024c7294bcd8e0df5b116546f25028ba) ] fix UI build
- [ [`2c005`](https://github.com/thheller/shadow-cljs/commit/2c005e564fa2adaf4cffa60a9a5a7173bad48224) ] add missing :output-feature-set options
- [ [`dd659`](https://github.com/thheller/shadow-cljs/commit/dd65952fd6539dac82f673bf5ca1c2b23d1ab26c) ] add util tap-hook
- [ [`f2482`](https://github.com/thheller/shadow-cljs/commit/f24823a017824a76fcfb1095dd7866d73840da80) ] bump browser-repl output-feature-set default
- [ [`9fe22`](https://github.com/thheller/shadow-cljs/commit/9fe223139dab0387f72a5d1905836b37d7be12a7) ] Fix little typo (#844)
- [ [`055dd`](https://github.com/thheller/shadow-cljs/commit/055dd89a2e2e5cab420b94ddef3da9af49f84f9f) ] add ESM braindump doc
- [ [`97802`](https://github.com/thheller/shadow-cljs/commit/9780241c9e839465cf4f2734952b0d8f3b1697fc) ] move shadow.undertow stuff into separate repo
- [ [`087f1`](https://github.com/thheller/shadow-cljs/commit/087f114d9c70bd5b411773dca496332b54cafe18) ] add little util "test" to help debug JsInspector
- [ [`d0451`](https://github.com/thheller/shadow-cljs/commit/d0451aba279689bc74ae5340e83b2466e4bdb237) ] prepare for new closure-compiler version bump

## [2.11.18](https://github.com/thheller/shadow-cljs/compare/c7c831e19851ff4ef1fa4967abb60b469fc76b98...58bc0aee1e3593cd9143a560aef2cf4dff003db5) - 2021-02-08
- [ [`58bc0`](https://github.com/thheller/shadow-cljs/commit/58bc0aee1e3593cd9143a560aef2cf4dff003db5) ] move cljs.core/exists? hack, more AOT friendly
- [ [`c7c83`](https://github.com/thheller/shadow-cljs/commit/c7c831e19851ff4ef1fa4967abb60b469fc76b98) ] improve missing instance error

## [2.11.17](https://github.com/thheller/shadow-cljs/compare/8b7f3964e732bc13a9981ba390908dd90f70f2e0...8b7f3964e732bc13a9981ba390908dd90f70f2e0) - 2021-02-04
- [ [`8b7f3`](https://github.com/thheller/shadow-cljs/commit/8b7f3964e732bc13a9981ba390908dd90f70f2e0) ] fix ESM .js code referencing other ESM .js code

## [2.11.16](https://github.com/thheller/shadow-cljs/compare/cabc30dae09b93ce57cdc002c91eb2325a244144...f3b89b5a3d0f35b78f0be9e457f78a70c0d7bf04) - 2021-02-03
- [ [`f3b89`](https://github.com/thheller/shadow-cljs/commit/f3b89b5a3d0f35b78f0be9e457f78a70c0d7bf04) ] remove hawk library used for file watching
- [ [`cabc3`](https://github.com/thheller/shadow-cljs/commit/cabc30dae09b93ce57cdc002c91eb2325a244144) ] Update demo url for karaoke player example in README (#830)

## [2.11.15](https://github.com/thheller/shadow-cljs/compare/9b9314186045da05370a8c398aaf0f051678cc63...a19aec26c93c03d8bbab8aa263d38f37269f2135) - 2021-01-26
- [ [`a19ae`](https://github.com/thheller/shadow-cljs/commit/a19aec26c93c03d8bbab8aa263d38f37269f2135) ] allow empty :entries for :npm-module again
- [ [`7575a`](https://github.com/thheller/shadow-cljs/commit/7575a7d8533be396cd6a630e6aa36848abebbf0d) ] add config option to keep import as is for :esm
- [ [`33d5d`](https://github.com/thheller/shadow-cljs/commit/33d5d6e31546c1b750e79a80cfa34a9afeee08a3) ] fix missing shadow$provide global in :npm-module
- [ [`05a4d`](https://github.com/thheller/shadow-cljs/commit/05a4d60c474875a2eb75f96ace8807f981857a4d) ] fix bad return value for bad .jar path
- [ [`bef29`](https://github.com/thheller/shadow-cljs/commit/bef295b4e63bc87ef696110dede42bc722c236d0) ] fix Release button not working
- [ [`9b931`](https://github.com/thheller/shadow-cljs/commit/9b9314186045da05370a8c398aaf0f051678cc63) ] UI cleanup and style tweaks, thanks @jacekschae

## [2.11.14](https://github.com/thheller/shadow-cljs/compare/7f80e6f88fd30d5d6e4eee4bb7295a79ce8b995e...23c751c6508cc2d5db7ac27e6a9c60cdd689a078) - 2021-01-14
- [ [`23c75`](https://github.com/thheller/shadow-cljs/commit/23c751c6508cc2d5db7ac27e6a9c60cdd689a078) ] use :ignore-warnings config for REPL as well
- [ [`7f80e`](https://github.com/thheller/shadow-cljs/commit/7f80e6f88fd30d5d6e4eee4bb7295a79ce8b995e) ] add missing css files to repo

## [2.11.13](https://github.com/thheller/shadow-cljs/compare/c3d81a5e56c3876a1ea63b6b90c4ffd4f592017e...c3d81a5e56c3876a1ea63b6b90c4ffd4f592017e) - 2021-01-10
- [ [`c3d81`](https://github.com/thheller/shadow-cljs/commit/c3d81a5e56c3876a1ea63b6b90c4ffd4f592017e) ] fix exception display in REPL

## [2.11.12](https://github.com/thheller/shadow-cljs/compare/1209522463361bf2bd4cfeaf25c52b81371fd0dc...87515b0268daa3a27da427970881c195c7fa5536) - 2021-01-08
- [ [`87515`](https://github.com/thheller/shadow-cljs/commit/87515b0268daa3a27da427970881c195c7fa5536) ] add built-in worker_threads node module
- [ [`c2885`](https://github.com/thheller/shadow-cljs/commit/c28859a8034bdeec3bd5c40846872c22fbcdd0f4) ] Cleanup (#819)
- [ [`d863c`](https://github.com/thheller/shadow-cljs/commit/d863c50fca4d74203cb3c170cc5a178030b53304) ] tweak npm version check done for browser builds
- [ [`ea54e`](https://github.com/thheller/shadow-cljs/commit/ea54e4b943c762645178303778aef094387f1a85) ] Remove unused requires (#813)
- [ [`12095`](https://github.com/thheller/shadow-cljs/commit/1209522463361bf2bd4cfeaf25c52b81371fd0dc) ] karma: If a test fails with exception, print the stacktrace (#814)

## [2.11.11](https://github.com/thheller/shadow-cljs/compare/0dd05af3fe830dbb8ea52e86eb2b963ce7861189...0dd05af3fe830dbb8ea52e86eb2b963ce7861189) - 2020-12-28
- [ [`0dd05`](https://github.com/thheller/shadow-cljs/commit/0dd05af3fe830dbb8ea52e86eb2b963ce7861189) ] fix REPL require issue

## [2.11.10](https://github.com/thheller/shadow-cljs/compare/6e1e1b786ba4e98516f722d56eb3c37107674f59...cb6790e14037abca0d1f524876e32146d280ea46) - 2020-12-21
- [ [`cb679`](https://github.com/thheller/shadow-cljs/commit/cb6790e14037abca0d1f524876e32146d280ea46) ] add :ui-options {:preferred-display-type :pprint}
- [ [`23dcd`](https://github.com/thheller/shadow-cljs/commit/23dcd7724681f9ba7c64530735385b06549266f6) ] allow selecting specific runtime for REPL again
- [ [`e2106`](https://github.com/thheller/shadow-cljs/commit/e21069690cf5555d88708abf9a0c93dba77f896f) ] consider build-defaults, target-defaults for :watch-dir
- [ [`00ceb`](https://github.com/thheller/shadow-cljs/commit/00ceb3efa1cdcc839ce117a694fbe75037adc100) ] somewhat handle obj-request-failed
- [ [`e07f3`](https://github.com/thheller/shadow-cljs/commit/e07f3d983f60f96c9d90d9a775b6650221a0d1d5) ] Strip 'Secure' flag from cookies passing through dev-http proxy (#810)
- [ [`6e1e1`](https://github.com/thheller/shadow-cljs/commit/6e1e1b786ba4e98516f722d56eb3c37107674f59) ] WIP: fix worker-start-arg (#805)

## [2.11.9](https://github.com/thheller/shadow-cljs/compare/ef164e2c46dfe390a86c0f30206b938a08989702...ef164e2c46dfe390a86c0f30206b938a08989702) - 2020-12-17
- [ [`ef164`](https://github.com/thheller/shadow-cljs/commit/ef164e2c46dfe390a86c0f30206b938a08989702) ] make esm a little more configurable

## [2.11.8](https://github.com/thheller/shadow-cljs/compare/c8acc440babe592b2c422b383cce5588ae4fd340...19a32f22bd272aee77200ac81d3952b361a341e6) - 2020-11-25
- [ [`19a32`](https://github.com/thheller/shadow-cljs/commit/19a32f22bd272aee77200ac81d3952b361a341e6) ] fix REPL default logic
- [ [`7ea88`](https://github.com/thheller/shadow-cljs/commit/7ea88c665642af53ac516ea7b955799c0284b5e2) ] add option to opt of using runtime for REPL
- [ [`c8acc`](https://github.com/thheller/shadow-cljs/commit/c8acc440babe592b2c422b383cce5588ae4fd340) ] add module entry key for package.json

## [2.11.7](https://github.com/thheller/shadow-cljs/compare/87d52becfdc0c9e932d77cc966b3207ec1018afa...87d52becfdc0c9e932d77cc966b3207ec1018afa) - 2020-11-04
- [ [`87d52`](https://github.com/thheller/shadow-cljs/commit/87d52becfdc0c9e932d77cc966b3207ec1018afa) ] allow disabling autoload in chrome-extension target

## [2.11.6](https://github.com/thheller/shadow-cljs/compare/5a38e7153c7770353a7997d78216effa6a638649...23947ee42f9854be156634f25238ca5991e8bd7d) - 2020-10-27
- [ [`23947`](https://github.com/thheller/shadow-cljs/commit/23947ee42f9854be156634f25238ca5991e8bd7d) ] ui tweak
- [ [`60770`](https://github.com/thheller/shadow-cljs/commit/60770a77ac02210d6f00b37bb3e3f29447c20063) ] remove unused query
- [ [`574ac`](https://github.com/thheller/shadow-cljs/commit/574acf591d945e00955a01ae0abef1f01fea3de7) ] update UI for grove changes, add inspect latest page
- [ [`5a38e`](https://github.com/thheller/shadow-cljs/commit/5a38e7153c7770353a7997d78216effa6a638649) ] migrate UI to event maps (from vectors)

## [2.11.5](https://github.com/thheller/shadow-cljs/compare/3f93713dba807157d2d612acc1c26a501f8f7e97...16727382e683bb9449a07068158e01f9f16827ea) - 2020-10-07
- [ [`16727`](https://github.com/thheller/shadow-cljs/commit/16727382e683bb9449a07068158e01f9f16827ea) ] safeguard css reload duplicating nodes
- [ [`699ce`](https://github.com/thheller/shadow-cljs/commit/699ceec8a95f545581313be86bdcd778cf21575d) ] improve error message about missing project install
- [ [`3e559`](https://github.com/thheller/shadow-cljs/commit/3e559b8f9ea80cc1ad1c51772fe4aa8c099a1159) ] add space for readable error message (#797)
- [ [`b6d1f`](https://github.com/thheller/shadow-cljs/commit/b6d1f4c0b6063dee40df182f2abee4a644cd332c) ] add configurable :devtools {:repl-timeout 1000000}
- [ [`d7cfd`](https://github.com/thheller/shadow-cljs/commit/d7cfd6ba7fd6b304563bd4ba818132fd0eb8cf40) ] small focus tweak
- [ [`3f937`](https://github.com/thheller/shadow-cljs/commit/3f93713dba807157d2d612acc1c26a501f8f7e97) ] more inspect keyboard control

## [2.11.4](https://github.com/thheller/shadow-cljs/compare/f7cc7ad4456b4116eeeb7c100edb7080615bdd44...f7cc7ad4456b4116eeeb7c100edb7080615bdd44) - 2020-09-11
- [ [`f7cc7`](https://github.com/thheller/shadow-cljs/commit/f7cc7ad4456b4116eeeb7c100edb7080615bdd44) ] remove bad conditional, breaking source maps

## [2.11.3](https://github.com/thheller/shadow-cljs/compare/d06bb17a9a8c653b6697f7ff16dfb412d44e7392...f28003cbb062fd27685048e8b4793eb05ca8f829) - 2020-09-10
- [ [`f2800`](https://github.com/thheller/shadow-cljs/commit/f28003cbb062fd27685048e8b4793eb05ca8f829) ] add :devtools {:log false} to disable shadow-cljs log messages
- [ [`c4e7c`](https://github.com/thheller/shadow-cljs/commit/c4e7ccd5e6f9ed4509d9962d714fd9b69c1e5fd8) ] optimize codemirror render a tiny bit
- [ [`106dc`](https://github.com/thheller/shadow-cljs/commit/106dcda807c048fbe56adaf00f35b7f196113b39) ] add experimental defclass
- [ [`d06bb`](https://github.com/thheller/shadow-cljs/commit/d06bb17a9a8c653b6697f7ff16dfb412d44e7392) ] cleanup

## [2.11.2](https://github.com/thheller/shadow-cljs/compare/21ff2766b44dae43ffee875ad718723e856be059...068b81b56b3fb79c19ced2e98ddfd17d73b95e30) - 2020-09-07
- [ [`068b8`](https://github.com/thheller/shadow-cljs/commit/068b81b56b3fb79c19ced2e98ddfd17d73b95e30) ] more tabindex
- [ [`14f2a`](https://github.com/thheller/shadow-cljs/commit/14f2addc846778ade408c1fac4080c2e77eeb6be) ] bit of tabindex handling for keyboard control
- [ [`57e62`](https://github.com/thheller/shadow-cljs/commit/57e62a18dba70f962a63a3fed1cfc5af74d70110) ] copy ex-data for cljs-eval errors
- [ [`0855a`](https://github.com/thheller/shadow-cljs/commit/0855a7a57912c4ab48b613168d1e5020012f4f2c) ] add function invoke optimization behind :shadow-tweaks setting
- [ [`019aa`](https://github.com/thheller/shadow-cljs/commit/019aafef4f715a218600f48c4c8b7b0346430d85) ] fix cljs_eval mishandling reader errors
- [ [`1bc1f`](https://github.com/thheller/shadow-cljs/commit/1bc1f9eac07361cf16d20c2a93b4ad5718a5e56a) ] get rid of last event function, data all the way
- [ [`9add5`](https://github.com/thheller/shadow-cljs/commit/9add52e89dc3c4aeb9ca9955ed35a8c3de8c9d26) ] rewrite keyboard handling
- [ [`20472`](https://github.com/thheller/shadow-cljs/commit/20472d8d6778191c979b236f3c4cda68d7ebc5f8) ] keyboard control is finicky, needs a rework
- [ [`af5c4`](https://github.com/thheller/shadow-cljs/commit/af5c497790e362c930dd57b2d84eeb52a8618ad0) ] bump closure-compiler
- [ [`d7eec`](https://github.com/thheller/shadow-cljs/commit/d7eec9fdd8469201920c3964f52efcfa3a1d8814) ] add util to print graaljs exception with source
- [ [`85e58`](https://github.com/thheller/shadow-cljs/commit/85e588aa1b70185cc88691655e595c7e1a012315) ] only need to copy one thing I guess
- [ [`be240`](https://github.com/thheller/shadow-cljs/commit/be240d33485a2163b451cf537d3c03c2f98ba23b) ] fix UI eval not showing result
- [ [`43a22`](https://github.com/thheller/shadow-cljs/commit/43a229c6f797030d2bb39382ab1ead465d36c46c) ] hacky attempt to fix source maps for generated code
- [ [`e96d5`](https://github.com/thheller/shadow-cljs/commit/e96d52941a13b655556f9babd617630ed2dc2a9f) ] return source-map file instance in graal helper
- [ [`c5fcb`](https://github.com/thheller/shadow-cljs/commit/c5fcb8d6c3433ac7c67ea5cca7e1037e955bc91c) ] add graaljs dev index source-maps
- [ [`3a7a5`](https://github.com/thheller/shadow-cljs/commit/3a7a51bd3f7335e3b8a488f0f192ea52fbae92e2) ] bump node-libs-browser
- [ [`96880`](https://github.com/thheller/shadow-cljs/commit/968808a7fea55ea3992a121d355103199d428633) ] slightly clean up inspect nav
- [ [`ae1b2`](https://github.com/thheller/shadow-cljs/commit/ae1b2c533131000f4a0a3613462350ea0ff9f533) ] add helper to source map graaljs errors
- [ [`21ff2`](https://github.com/thheller/shadow-cljs/commit/21ff2766b44dae43ffee875ad718723e856be059) ] move current inspect logic to state

## [2.11.1](https://github.com/thheller/shadow-cljs/compare/2e8c5f1d7483017a1860d0e49a1b3d4e8963e22d...89cb05394d49a5e8b71a5e411e455f92f2870cbf) - 2020-08-28
- [ [`89cb0`](https://github.com/thheller/shadow-cljs/commit/89cb05394d49a5e8b71a5e411e455f92f2870cbf) ] rewrite tap stream, add clear button
- [ [`48f1e`](https://github.com/thheller/shadow-cljs/commit/48f1e9ed7bf00d40cc39b2b7af63a638e35f555b) ] add :test-paths ["test/something"] options for test targets
- [ [`220ed`](https://github.com/thheller/shadow-cljs/commit/220edab166c208c82014a8fb3596568a9a7933bc) ] rewrite Inspect again ...
- [ [`2e8c5`](https://github.com/thheller/shadow-cljs/commit/2e8c5f1d7483017a1860d0e49a1b3d4e8963e22d) ] adjust CLJS-3233 impl

## [2.11.0](https://github.com/thheller/shadow-cljs/compare/28169be104149e496b31bad443be7ecb6d16cd4a...28169be104149e496b31bad443be7ecb6d16cd4a) - 2020-08-23
- [ [`28169`](https://github.com/thheller/shadow-cljs/commit/28169be104149e496b31bad443be7ecb6d16cd4a) ] [BREAKING] fix import of npm package in classpath-js

## [2.10.22](https://github.com/thheller/shadow-cljs/compare/6252e3b0843b21cfda49fe77bd0e51ad7b32782f...81c4656e5a9f7d99903b5b5ef344bf093eec3285) - 2020-08-20
- [ [`81c46`](https://github.com/thheller/shadow-cljs/commit/81c4656e5a9f7d99903b5b5ef344bf093eec3285) ] add :devtools {:use-document-protocol true} option
- [ [`4735b`](https://github.com/thheller/shadow-cljs/commit/4735be311964d5efa1abcd229d5d1e210a33c6e6) ] use the goog.global.location.protocol for get-base-url (#775)
- [ [`6f665`](https://github.com/thheller/shadow-cljs/commit/6f66535fddf4a601b0e7a7e4036cced1b5cf82b4) ] Canonicalize lib names in deps.edn (#778)
- [ [`d810a`](https://github.com/thheller/shadow-cljs/commit/d810a0f5e1b1c78f15e0365ce7a05cba16f0ca70) ] bump karma output-feature-set default
- [ [`5b329`](https://github.com/thheller/shadow-cljs/commit/5b329c4a4a422c42e108cbf7a3d9c32b16018f2e) ] restrict CLJS-3235 style requires to CLJS files only
- [ [`da18f`](https://github.com/thheller/shadow-cljs/commit/da18fb73ca274be4bcb8a7d9a2a007ecedb2d41e) ] various UI tweaks
- [ [`fcf0f`](https://github.com/thheller/shadow-cljs/commit/fcf0f48cd01d7ee23a4117c94ac90c331697c389) ] use updated defc macro in UI
- [ [`8583a`](https://github.com/thheller/shadow-cljs/commit/8583aa4844bba08742beee7b0822e3171e6f51d8) ] bump a few deps
- [ [`6252e`](https://github.com/thheller/shadow-cljs/commit/6252e3b0843b21cfda49fe77bd0e51ad7b32782f) ] Remove old link only after new one is loaded (#770)

## [2.10.21](https://github.com/thheller/shadow-cljs/compare/9f8ab0704ffd2bd4efe87a5ffb1f2a8e513e085b...9f8ab0704ffd2bd4efe87a5ffb1f2a8e513e085b) - 2020-08-08
- [ [`9f8ab`](https://github.com/thheller/shadow-cljs/commit/9f8ab0704ffd2bd4efe87a5ffb1f2a8e513e085b) ] oops, accidentally released some temp test code

## [2.10.20](https://github.com/thheller/shadow-cljs/compare/9df7b5f71b2ecf280b11064cba947547092a309c...f8ddfc3e0424f0cf695a005ad71879cca7a37b06) - 2020-08-08
- [ [`f8ddf`](https://github.com/thheller/shadow-cljs/commit/f8ddfc3e0424f0cf695a005ad71879cca7a37b06) ] add react-native boilerplate helper
- [ [`d4fcc`](https://github.com/thheller/shadow-cljs/commit/d4fcce43a5f4e8d61ca3941ba27d2201a82e90e1) ] add links to docs to REPL error msg
- [ [`9df7b`](https://github.com/thheller/shadow-cljs/commit/9df7b5f71b2ecf280b11064cba947547092a309c) ] slightly improve error message for malformed configs

## [2.10.19](https://github.com/thheller/shadow-cljs/compare/590dab2702f35510eed7b70a504838b7a747aace...6b2f241a8f9db04ecb00e69695e655a45ea5f929) - 2020-07-31
- [ [`6b2f2`](https://github.com/thheller/shadow-cljs/commit/6b2f241a8f9db04ecb00e69695e655a45ea5f929) ] actually make it take 2 args
- [ [`f0153`](https://github.com/thheller/shadow-cljs/commit/f01530fd158a4e2a84246767aa445e5ad77deafe) ] add more generic :proxy-predicate, remove :proxy-exclude
- [ [`0e1d8`](https://github.com/thheller/shadow-cljs/commit/0e1d8f86921c69745b68c8e127dc78af19167f05) ] add :proxy-exclude :dev-http option
- [ [`ad218`](https://github.com/thheller/shadow-cljs/commit/ad2186449b570ca6f87a4d41c73367c94de92ac9) ] [esm] only add module_loaded call for watch
- [ [`590da`](https://github.com/thheller/shadow-cljs/commit/590dab2702f35510eed7b70a504838b7a747aace) ] fix #shadow/env not taking default values in node

## [2.10.18](https://github.com/thheller/shadow-cljs/compare/2dc160abb0811d925c478ab3b14f2efa6c3e27d7...8d0addfe67e09e37b0f217fa3bcde42dd9972b78) - 2020-07-24
- [ [`8d0ad`](https://github.com/thheller/shadow-cljs/commit/8d0addfe67e09e37b0f217fa3bcde42dd9972b78) ] work arround not being able to ignore @define in npm files
- [ [`bfdda`](https://github.com/thheller/shadow-cljs/commit/bfdda1d472198a8f352468eafc2a1080e855b747) ] fix error message for errors during emit
- [ [`2dc16`](https://github.com/thheller/shadow-cljs/commit/2dc160abb0811d925c478ab3b14f2efa6c3e27d7) ] fix classpath-js cache issue

## [2.10.17](https://github.com/thheller/shadow-cljs/compare/4bf98f0df3668335d38877fa99a531baf31218fb...e14abccdbde36bf36739f75c210fd834e8a921ab) - 2020-07-20
- [ [`e14ab`](https://github.com/thheller/shadow-cljs/commit/e14abccdbde36bf36739f75c210fd834e8a921ab) ] avoid duplicate print output for node-repl
- [ [`91377`](https://github.com/thheller/shadow-cljs/commit/91377d2d702aea5dfa877f74f4128f5cc05608f2) ] ignore some more classpath files
- [ [`4bf98`](https://github.com/thheller/shadow-cljs/commit/4bf98f0df3668335d38877fa99a531baf31218fb) ] auto-add cider refactor middleware when found on classpath

## [2.10.16](https://github.com/thheller/shadow-cljs/compare/e7519c47d1fb5e62fb45884fee7df6d04df94cb1...53c1b6b3862e60531e505a8cb31250b98791c21c) - 2020-07-20
- [ [`53c1b`](https://github.com/thheller/shadow-cljs/commit/53c1b6b3862e60531e505a8cb31250b98791c21c) ] bump a few deps
- [ [`2668b`](https://github.com/thheller/shadow-cljs/commit/2668b833d54d3d02e05823f919cfed68e8a34cd4) ] use graaljs scriptengine by default
- [ [`a0b30`](https://github.com/thheller/shadow-cljs/commit/a0b305a07274fb746bd7e1bd0902fe6df53e4ce4) ] actually filter ...
- [ [`98425`](https://github.com/thheller/shadow-cljs/commit/984252ee6ff009cd6c5ed2104088ae6bb0e68769) ] don't look for resources in public/
- [ [`7f159`](https://github.com/thheller/shadow-cljs/commit/7f1590fd80665a25dff455192c9184bdd8348cc2) ] remove unused exports
- [ [`e7519`](https://github.com/thheller/shadow-cljs/commit/e7519c47d1fb5e62fb45884fee7df6d04df94cb1) ] use fipp instead of cljs.pprint

## [2.10.15](https://github.com/thheller/shadow-cljs/compare/1ce312fc57e9d2e002f4bf219f0e70fa8e76948c...2c3b45c594c5c7586e3afe953a6d54a0c05c3dbe) - 2020-07-16
- [ [`2c3b4`](https://github.com/thheller/shadow-cljs/commit/2c3b45c594c5c7586e3afe953a6d54a0c05c3dbe) ] fix misplaced form
- [ [`a72a9`](https://github.com/thheller/shadow-cljs/commit/a72a967338c947cdf57c04e0e503b1515996b855) ] add repl timeout
- [ [`457f9`](https://github.com/thheller/shadow-cljs/commit/457f9e88268320da5080935e645c879bd8058457) ] auto-restart node-repl node process if it crashes
- [ [`a3b8a`](https://github.com/thheller/shadow-cljs/commit/a3b8a0f8ad365c9a534c42ae688c68d8843edd83) ] reset nrepl session when worker died
- [ [`1ce31`](https://github.com/thheller/shadow-cljs/commit/1ce312fc57e9d2e002f4bf219f0e70fa8e76948c) ] allow :autoload in :npm-module builds

## [2.10.14](https://github.com/thheller/shadow-cljs/compare/b3f10a186d514ccde88c793f91994ed907caf886...95d3f9a0610c0773aab1602bf74dd11a273f77d8) - 2020-07-04
- [ [`95d3f`](https://github.com/thheller/shadow-cljs/commit/95d3f9a0610c0773aab1602bf74dd11a273f77d8) ] verify that resource namespace matches expected ns
- [ [`83b7d`](https://github.com/thheller/shadow-cljs/commit/83b7d5ac07bd3e9f776290453e2373e993f5dd55) ] fix REPL forgetting string aliases
- [ [`067f8`](https://github.com/thheller/shadow-cljs/commit/067f829d954073eb1935d5fb45234848f84e1339) ] fix invalid sourceURL
- [ [`d20e7`](https://github.com/thheller/shadow-cljs/commit/d20e7c0afba80eb44f37537a0e4f23cab30ea8aa) ] enable :infer-externs :auto by default
- [ [`01592`](https://github.com/thheller/shadow-cljs/commit/015926609e1c070de57efa71646c46f92f08d7d9) ] fix missing cljs.user ns in :bootstrap builds
- [ [`b3f10`](https://github.com/thheller/shadow-cljs/commit/b3f10a186d514ccde88c793f91994ed907caf886) ] fix cache issue for #::alias{:foo "bar"}

## [2.10.13](https://github.com/thheller/shadow-cljs/compare/859285074c4ea2c78e28c5786be4c680c189bd45...315bd5ac76999ac8798cd1a9cf9c7bc038c24f12) - 2020-06-22
- [ [`315bd`](https://github.com/thheller/shadow-cljs/commit/315bd5ac76999ac8798cd1a9cf9c7bc038c24f12) ] fix partial cache bug
- [ [`85928`](https://github.com/thheller/shadow-cljs/commit/859285074c4ea2c78e28c5786be4c680c189bd45) ] fix old :ex-rid reference, renamed to :ex-client-id

## [2.10.12](https://github.com/thheller/shadow-cljs/compare/3899fa90c6767d7b7d8251d9e0bb0e29ef19cb41...3899fa90c6767d7b7d8251d9e0bb0e29ef19cb41) - 2020-06-18
- [ [`3899f`](https://github.com/thheller/shadow-cljs/commit/3899fa90c6767d7b7d8251d9e0bb0e29ef19cb41) ] add missing reload-related things for :esm

## [2.10.11](https://github.com/thheller/shadow-cljs/compare/f1bad5e15754970263010d68c5898f097ccf5f74...f1bad5e15754970263010d68c5898f097ccf5f74) - 2020-06-18
- [ [`f1bad`](https://github.com/thheller/shadow-cljs/commit/f1bad5e15754970263010d68c5898f097ccf5f74) ] fix trying to write to non-existent dir

## [2.10.10](https://github.com/thheller/shadow-cljs/compare/e4153588976f06987592cfa285266377da0563e8...827c654793cf2867ff5a7186cf8134ccca873991) - 2020-06-17
- [ [`827c6`](https://github.com/thheller/shadow-cljs/commit/827c654793cf2867ff5a7186cf8134ccca873991) ] minor code gen tweak
- [ [`4ab18`](https://github.com/thheller/shadow-cljs/commit/4ab18eb16b23367fd3faeda2ccba3812a803d463) ] add support for dynamic import()
- [ [`fced6`](https://github.com/thheller/shadow-cljs/commit/fced62bfafcdedaa30817bd81181c8a2be1f2d26) ] fix node-script builds broken by previous commit
- [ [`e4153`](https://github.com/thheller/shadow-cljs/commit/e4153588976f06987592cfa285266377da0563e8) ] [WIP] add :target :esm

## [2.10.9](https://github.com/thheller/shadow-cljs/compare/9178e39a84108b1c0b9b26368b161a9501f4b39f...7f4fba35af17b4a863ef915bcf8c13ce64e2e910) - 2020-06-16
- [ [`7f4fb`](https://github.com/thheller/shadow-cljs/commit/7f4fba35af17b4a863ef915bcf8c13ce64e2e910) ] fix module$foo undeclared warnings for classpath-js
- [ [`d72f4`](https://github.com/thheller/shadow-cljs/commit/d72f40db4e671279c9d148c34dba4f93561b2ff2) ] ensure polyfills has correct output mode
- [ [`9178e`](https://github.com/thheller/shadow-cljs/commit/9178e39a84108b1c0b9b26368b161a9501f4b39f) ] fix missing shadow-js polyfills in release builds

## [2.10.8](https://github.com/thheller/shadow-cljs/compare/db55036fb6988fb3146fb91a9554da25932209c6...561093312292db8fc9e2a8a4a7f6fdad505dd7ed) - 2020-06-14
- [ [`56109`](https://github.com/thheller/shadow-cljs/commit/561093312292db8fc9e2a8a4a7f6fdad505dd7ed) ] [WIP] first part of lazy-seq browse
- [ [`f82cf`](https://github.com/thheller/shadow-cljs/commit/f82cffe1b8904e412ed8402b9f90b9d104599601) ] another attempt at polyfills
- [ [`28582`](https://github.com/thheller/shadow-cljs/commit/285829fcc9113ad34d5b0d5e99f4e6c78874e2a4) ] add nrepl.print support
- [ [`440d9`](https://github.com/thheller/shadow-cljs/commit/440d991a19d9925901cf62d9c1cff57df57e6d89) ] remove special char from console logs
- [ [`ad7c1`](https://github.com/thheller/shadow-cljs/commit/ad7c1541e6e5c226dcc2fda8a75d159f06008691) ] fix node_modules caching issue
- [ [`2f185`](https://github.com/thheller/shadow-cljs/commit/2f1857558123fa7a440acd8792299ffbe842ff4f) ] cleanup str->sym aliases on source reset
- [ [`9530e`](https://github.com/thheller/shadow-cljs/commit/9530e4b0c91db4f8415b4a7e4771fc46900c7468) ] basic relay tcp connector
- [ [`db550`](https://github.com/thheller/shadow-cljs/commit/db55036fb6988fb3146fb91a9554da25932209c6) ] remove unused core.async require

## [2.10.7](https://github.com/thheller/shadow-cljs/compare/2e3f28f44342f671b6be5a4cfc1a74708200aa07...5a32abb215c81c26268967ed4439303bb348e5d0) - 2020-06-13
- [ [`5a32a`](https://github.com/thheller/shadow-cljs/commit/5a32abb215c81c26268967ed4439303bb348e5d0) ] remove invalid into
- [ [`886ea`](https://github.com/thheller/shadow-cljs/commit/886eaa809383720d123402eae4708ce4dd483f45) ] fix UI not showing disconnected ws
- [ [`d7bf6`](https://github.com/thheller/shadow-cljs/commit/d7bf6a6f244686cef103313ffe56a798314186c0) ] fix spaces in classpath resource urls
- [ [`540b5`](https://github.com/thheller/shadow-cljs/commit/540b5a0a60fbdcd5fd3c2649a4f95c34f79cc7b0) ] add back ws reconnect
- [ [`ec9ab`](https://github.com/thheller/shadow-cljs/commit/ec9ab13ceae294651cc1b5d38b40859e13fbb794) ] use graaljs scriptengine when available
- [ [`2e3f2`](https://github.com/thheller/shadow-cljs/commit/2e3f28f44342f671b6be5a4cfc1a74708200aa07) ] remove old preload, no longer required

## [2.10.6](https://github.com/thheller/shadow-cljs/compare/fb2bb1930f58daa34a6151e2f6b03fe1a891120f...aa815ed0b02e25bff3b587bdc8fdff250adba594) - 2020-06-10
- [ [`aa815`](https://github.com/thheller/shadow-cljs/commit/aa815ed0b02e25bff3b587bdc8fdff250adba594) ] fix CLJS repl read problem ::alias/kw
- [ [`448a5`](https://github.com/thheller/shadow-cljs/commit/448a5f8166d6612423fdcdab301de6f89ef53ed8) ] a little ws tuning. can't figure out good reconnect logic.
- [ [`fb2bb`](https://github.com/thheller/shadow-cljs/commit/fb2bb1930f58daa34a6151e2f6b03fe1a891120f) ] use global location not document.location

## [2.10.5](https://github.com/thheller/shadow-cljs/compare/e2196c2d14ea206b40a1f2078dc851a940ffae72...6550a987115b0ed3ef7f4584e870912d0b69beb9) - 2020-06-08
- [ [`6550a`](https://github.com/thheller/shadow-cljs/commit/6550a987115b0ed3ef7f4584e870912d0b69beb9) ] fix inaccurate source maps
- [ [`535f0`](https://github.com/thheller/shadow-cljs/commit/535f0ad265ee187466c96a8c68004357ca45d0ec) ] remove useless sub-close msg
- [ [`e2196`](https://github.com/thheller/shadow-cljs/commit/e2196c2d14ea206b40a1f2078dc851a940ffae72) ] move UI api-ws to use relay instead

## [2.10.4](https://github.com/thheller/shadow-cljs/compare/abff5b6eb963cbb833c01e9d83bbcbb819b60725...cb29efdfcd98298b71c7fe4434810f8ce35c6f10) - 2020-06-06
- [ [`cb29e`](https://github.com/thheller/shadow-cljs/commit/cb29efdfcd98298b71c7fe4434810f8ce35c6f10) ] bump shadow lib, fixes andare issues
- [ [`5b354`](https://github.com/thheller/shadow-cljs/commit/5b354c5e0748c23ff46d4dcb179037d8c8a5ce2a) ] move shadow-cljs CLI to :node-library
- [ [`abff5`](https://github.com/thheller/shadow-cljs/commit/abff5b6eb963cbb833c01e9d83bbcbb819b60725) ] make #shadow/env a little more flexible

## [2.10.3](https://github.com/thheller/shadow-cljs/compare/e4e8801b8c520b4c064311ffe4a5aa3ab7ab420b...578bd4ad543619b80f52bc864f135cc5a4f18781) - 2020-06-05
- [ [`578bd`](https://github.com/thheller/shadow-cljs/commit/578bd4ad543619b80f52bc864f135cc5a4f18781) ] also capture :ns for api/cljs-eval
- [ [`ac882`](https://github.com/thheller/shadow-cljs/commit/ac8820648bc54618831ddd67fd496e63324d058f) ] respect :ns for nrepl eval messages
- [ [`5299a`](https://github.com/thheller/shadow-cljs/commit/5299a1e0552f9b3a1f1e4178946d551a54db6a31) ] fix nrepl not remembering ns changes
- [ [`e4e88`](https://github.com/thheller/shadow-cljs/commit/e4e8801b8c520b4c064311ffe4a5aa3ab7ab420b) ] only run :build-notify when actually configured

## [2.10.2](https://github.com/thheller/shadow-cljs/compare/3594d6ce8fbfae3ad6b4eda187ed1214e7830a84...7a81f0f9d7910c906832cd6e92355936d17a3c16) - 2020-06-05
- [ [`7a81f`](https://github.com/thheller/shadow-cljs/commit/7a81f0f9d7910c906832cd6e92355936d17a3c16) ] add support for data_readers.cljc footgun
- [ [`eb625`](https://github.com/thheller/shadow-cljs/commit/eb6259c94ebd6b4d3824dce212d826af74b5cc63) ] make Inspect a little clearer where taps come from
- [ [`3594d`](https://github.com/thheller/shadow-cljs/commit/3594d6ce8fbfae3ad6b4eda187ed1214e7830a84) ] preliminary cljs-eval API fn

## [2.10.1](https://github.com/thheller/shadow-cljs/compare/22af0027c7f4baf011ca67810c239186303c4c12...44a9b07fa715b3de727db70cf42a7c73e5a92052) - 2020-06-05
- [ [`44a9b`](https://github.com/thheller/shadow-cljs/commit/44a9b07fa715b3de727db70cf42a7c73e5a92052) ] fix faulty relay ping logic
- [ [`22af0`](https://github.com/thheller/shadow-cljs/commit/22af0027c7f4baf011ca67810c239186303c4c12) ] properly display print failures in REPL

## [2.10.0](https://github.com/thheller/shadow-cljs/compare/f86bb4f8cde27c7613b4cd7643597cf23720e0d1...f1385f0c6816afbbad9f179c8f91a5276517a275) - 2020-06-04
- [ [`f1385`](https://github.com/thheller/shadow-cljs/commit/f1385f0c6816afbbad9f179c8f91a5276517a275) ] enforce ping/pong for remote relay connections
- [ [`76d34`](https://github.com/thheller/shadow-cljs/commit/76d34665cd5d29dcf4f55e9ba5f3579d8cd0160a) ] move ping logic to relay
- [ [`422a4`](https://github.com/thheller/shadow-cljs/commit/422a49e874dcb5768f913240cf916de320324e58) ] reduce :repl/init message size
- [ [`5c84b`](https://github.com/thheller/shadow-cljs/commit/5c84b9d1a70abe175662bf72192332c930c4e082) ] delay repl-init loading sources until actually needed
- [ [`d4874`](https://github.com/thheller/shadow-cljs/commit/d48747731f8863bd69b822c2f99e37ce87c4865e) ] simplify nrepl setup
- [ [`4a9b1`](https://github.com/thheller/shadow-cljs/commit/4a9b16f8497a91b4810a623bc9c16714d2d167f4) ] adjust relay connection channel handling
- [ [`66588`](https://github.com/thheller/shadow-cljs/commit/6658803815e80b7c3dddcb25ce80029a7926f00d) ] cleanup
- [ [`50461`](https://github.com/thheller/shadow-cljs/commit/50461fe703dcbc0b2ec34a4969051636cfa3eb0a) ] update out of date docs
- [ [`d6e2a`](https://github.com/thheller/shadow-cljs/commit/d6e2a8405979e1dc9898a72d7047bc39a224e8f8) ] piggieback exclusions
- [ [`36e63`](https://github.com/thheller/shadow-cljs/commit/36e63a7801a2bafe0140fe9e49dbada9233335f8) ] migrate everything to use shadow.remote
- [ [`67748`](https://github.com/thheller/shadow-cljs/commit/6774819630a73a21012614c788f2a770c979a9f6) ] bring back project-install check and warning
- [ [`75172`](https://github.com/thheller/shadow-cljs/commit/75172f28dbdadeb6e5796b2a13b75d1b9ac5cf53) ] always provide error :report for now
- [ [`057c8`](https://github.com/thheller/shadow-cljs/commit/057c85fb26030c40fa30faad75a6ae03a2f60132) ] fix race condition
- [ [`389d3`](https://github.com/thheller/shadow-cljs/commit/389d3d6420a006c2440495c1356fe34105fd9576) ] expose global CLJS_EVAL fn via shadow.remote
- [ [`953cb`](https://github.com/thheller/shadow-cljs/commit/953cba11bd6717dcf73674df5d4a16f22cd4ce98) ] remove fixed nrepl port config
- [ [`33a9d`](https://github.com/thheller/shadow-cljs/commit/33a9d7c517b584a712127fdd4cbbd70ec44e5775) ] nil inspect so it doesn't throw
- [ [`45360`](https://github.com/thheller/shadow-cljs/commit/4536053f7097a378539f8e9d189f4ccdf6af950f) ] cross-runtime inspect navigation
- [ [`d80ee`](https://github.com/thheller/shadow-cljs/commit/d80ee477020c91fa441bb205f52967c1100e2526) ] remove cljs.test hack
- [ [`d1990`](https://github.com/thheller/shadow-cljs/commit/d1990792afad4a8e5fa0d0cc96073e131e6bab48) ] bump cljs
- [ [`f86bb`](https://github.com/thheller/shadow-cljs/commit/f86bb4f8cde27c7613b4cd7643597cf23720e0d1) ] experimental way to auto-add namespaces to build

## [2.9.10](https://github.com/thheller/shadow-cljs/compare/f2088bddd27d5d646cdfe62647fb86b37fa7c1e9...db38f5968a8ecefcd10cd62412333028ee13959b) - 2020-05-26
- [ [`db38f`](https://github.com/thheller/shadow-cljs/commit/db38f5968a8ecefcd10cd62412333028ee13959b) ] fix broken .exist call
- [ [`82d80`](https://github.com/thheller/shadow-cljs/commit/82d80775addee2d7ca9353a040350f5387e8fa0b) ] fix url->file conversion
- [ [`3b394`](https://github.com/thheller/shadow-cljs/commit/3b39467dba4191f4d070dd9005639dfc6ba296c3) ] datafy for JS errors
- [ [`13025`](https://github.com/thheller/shadow-cljs/commit/130252ce9a3ae7befd850545cb6685c3deb2b728) ] add back autostart
- [ [`198da`](https://github.com/thheller/shadow-cljs/commit/198da5a2ac0a61ae3cfa17e0a6a325f87fbc561a) ] move more shadow.remote stuff arround
- [ [`f2088`](https://github.com/thheller/shadow-cljs/commit/f2088bddd27d5d646cdfe62647fb86b37fa7c1e9) ] rewrite most of shadow.remote.runtime.cljs

## [2.9.9](https://github.com/thheller/shadow-cljs/compare/78df3b4e0db5138298a8b7897086ef6597b82955...be474ede2bebf3afa63d941460c62c08538cca3d) - 2020-05-25
- [ [`be474`](https://github.com/thheller/shadow-cljs/commit/be474ede2bebf3afa63d941460c62c08538cca3d) ] disable codemirror autofocus
- [ [`0ae6e`](https://github.com/thheller/shadow-cljs/commit/0ae6e5174727275e67eb2cc47cc1433b7dd791f0) ] add couple more :npm-deps config options
- [ [`ab973`](https://github.com/thheller/shadow-cljs/commit/ab97342e958e799068ac3d568b440917f3bccfc4) ] [UI] properly show Loading ... state
- [ [`0f9d9`](https://github.com/thheller/shadow-cljs/commit/0f9d9a0d034cddc5dece731526f0b9cedd879554) ] [UI] use codemirror for pprint/edn views
- [ [`39a68`](https://github.com/thheller/shadow-cljs/commit/39a68cbe1c9f1f64bfa39cdc2618c62ecc235354) ] [UI] start adding keyboard shortcuts
- [ [`b4fef`](https://github.com/thheller/shadow-cljs/commit/b4fef4102cc30037833817dd978d6c7ebe481e22) ] tweak shadow.remote things
- [ [`e0c31`](https://github.com/thheller/shadow-cljs/commit/e0c317d068057d68df84240a4fa9890069d853db) ] enable websocket compression
- [ [`2590c`](https://github.com/thheller/shadow-cljs/commit/2590caad5d289a864545d91cfa21cf364bdc7352) ] [UI] fix Runtimes tab not highlighting
- [ [`e9c81`](https://github.com/thheller/shadow-cljs/commit/e9c8125bfbc6c081c823f6109d8ebbb7d686d739) ] [UI] add generic minimal error popups
- [ [`459e4`](https://github.com/thheller/shadow-cljs/commit/459e446f9d3ec4e49e0c1029a3698a18532022c2) ] fix closure renaming issue
- [ [`b74d0`](https://github.com/thheller/shadow-cljs/commit/b74d05b53191855dc5b805a06e6aeb125139e81d) ] remove useless goog.require
- [ [`290b6`](https://github.com/thheller/shadow-cljs/commit/290b69bbed02b8a16a9326284d03d39ad5cb908d) ] experimental CLJS-3235 support
- [ [`78df3`](https://github.com/thheller/shadow-cljs/commit/78df3b4e0db5138298a8b7897086ef6597b82955) ] improve UI inspect eval and result handling

## [2.9.8](https://github.com/thheller/shadow-cljs/compare/a8d5dfd64363b8f2ca0cee0f43766e5c877c921e...52d220135613ff101ddead3e995a50d4b690746c) - 2020-05-19
- [ [`52d22`](https://github.com/thheller/shadow-cljs/commit/52d220135613ff101ddead3e995a50d4b690746c) ] fixes warnings for :build-notify (#718)
- [ [`a8d5d`](https://github.com/thheller/shadow-cljs/commit/a8d5dfd64363b8f2ca0cee0f43766e5c877c921e) ] remove some noise

## [2.9.7](https://github.com/thheller/shadow-cljs/compare/22b078a77444cc99ba22a87277f82286f0f90437...6160238ed9bbbdb5ec098d54d2ba3bd64180cd8e) - 2020-05-17
- [ [`61602`](https://github.com/thheller/shadow-cljs/commit/6160238ed9bbbdb5ec098d54d2ba3bd64180cd8e) ] add little utility to dump closure inputs
- [ [`60196`](https://github.com/thheller/shadow-cljs/commit/60196a27725cecf177f617a9298c3d326a7006ff) ] change defonce hack
- [ [`22b07`](https://github.com/thheller/shadow-cljs/commit/22b078a77444cc99ba22a87277f82286f0f90437) ] avoid transpiling classpath-js twice

## [2.9.6](https://github.com/thheller/shadow-cljs/compare/5edcf416231846d60e014af400662841e8334179...c5ce449c5749c9a7dd7ed0f9d58ec5f78b826e03) - 2020-05-16
- [ [`c5ce4`](https://github.com/thheller/shadow-cljs/commit/c5ce449c5749c9a7dd7ed0f9d58ec5f78b826e03) ] fix js reload reloading too much
- [ [`659e6`](https://github.com/thheller/shadow-cljs/commit/659e6eb72f7ed0f2acaf57e5d4502bf24884646c) ] fix goog.module hot-reload
- [ [`e92f8`](https://github.com/thheller/shadow-cljs/commit/e92f891810e5e5a34c83bfa69e740332f29c0e16) ] add missing file
- [ [`5edcf`](https://github.com/thheller/shadow-cljs/commit/5edcf416231846d60e014af400662841e8334179) ] fix classpath-js index/reload

## [2.9.5](https://github.com/thheller/shadow-cljs/compare/ca82d7209cf015d3e438d30e544ded33765b2aff...ad8ea9d4aefc5d1c0ab371613a9c96c17da3d447) - 2020-05-16
- [ [`ad8ea`](https://github.com/thheller/shadow-cljs/commit/ad8ea9d4aefc5d1c0ab371613a9c96c17da3d447) ] increase default output-feature-set for targets
- [ [`79724`](https://github.com/thheller/shadow-cljs/commit/797241d7936a25994b2b4635ac76238ac75494d6) ] start tracking more polyfills infos
- [ [`89890`](https://github.com/thheller/shadow-cljs/commit/89890dac708bcb2127dff056ee0cd1cf3c89083c) ] add missing CLI remote fn
- [ [`ca82d`](https://github.com/thheller/shadow-cljs/commit/ca82d7209cf015d3e438d30e544ded33765b2aff) ] add cli-actual to aot

## [2.9.4](https://github.com/thheller/shadow-cljs/compare/f7a552306ba2b86fad20e84d25e989c1ea1fbaa7...5363ffab9c388871a6b4e8d641e73b260f8ff9f3) - 2020-05-15
- [ [`5363f`](https://github.com/thheller/shadow-cljs/commit/5363ffab9c388871a6b4e8d641e73b260f8ff9f3) ] make cli devtools backwards compatible
- [ [`36682`](https://github.com/thheller/shadow-cljs/commit/36682facddf068b786ee814ffb55ee1c80c56fd2) ] fix misplaced docstrings (#713)
- [ [`c2a42`](https://github.com/thheller/shadow-cljs/commit/c2a427c91f5ebc01571e545be56e07bee2b7341f) ] add missing REPL specials
- [ [`5ee16`](https://github.com/thheller/shadow-cljs/commit/5ee163fecd852b04db70f5a82a263a806acf3e0d) ] fix ^:const related watch issue
- [ [`f7a55`](https://github.com/thheller/shadow-cljs/commit/f7a552306ba2b86fad20e84d25e989c1ea1fbaa7) ] minor tweak

## [2.9.3](https://github.com/thheller/shadow-cljs/compare/5806c75bc9f84ab88eec135cf39e7a7caaf1e1fd...ec63c34ae493a7e75e9fe7d5f3ab7cadd1fecfe8) - 2020-05-13
- [ [`ec63c`](https://github.com/thheller/shadow-cljs/commit/ec63c34ae493a7e75e9fe7d5f3ab7cadd1fecfe8) ] "hide" non-exported defs in :npm-module
- [ [`76639`](https://github.com/thheller/shadow-cljs/commit/766398b043f2442685574ea3a7aeb6183f132a10) ] try to provide some guidance when loading fails
- [ [`94867`](https://github.com/thheller/shadow-cljs/commit/9486745a27c14a8db73583768278a8791dd7581b) ] use proper filename in dummy test
- [ [`b630d`](https://github.com/thheller/shadow-cljs/commit/b630df7510683cb31eee4b37ad63428dca89e1a6) ] fix REPL timing issue
- [ [`5806c`](https://github.com/thheller/shadow-cljs/commit/5806c75bc9f84ab88eec135cf39e7a7caaf1e1fd) ] fix classpath-js reload

## [2.9.2](https://github.com/thheller/shadow-cljs/compare/65625a61f0064463a8be1533be96c7352663f8ca...65625a61f0064463a8be1533be96c7352663f8ca) - 2020-05-12
- [ [`65625`](https://github.com/thheller/shadow-cljs/commit/65625a61f0064463a8be1533be96c7352663f8ca) ] add generic :js-options :package-overrides

## [2.9.1](https://github.com/thheller/shadow-cljs/compare/06015e1c4d5d8b0127b27abb5b7112dc0d760af6...873d446d0823399d6a391639fd6cf0962ec1bedc) - 2020-05-12
- [ [`873d4`](https://github.com/thheller/shadow-cljs/commit/873d446d0823399d6a391639fd6cf0962ec1bedc) ] bump core.async to fixed version
- [ [`cd2ff`](https://github.com/thheller/shadow-cljs/commit/cd2ffba5bc9a3fc21472dd993970d1f5e043c0e5) ] downgrade core.async
- [ [`9f79c`](https://github.com/thheller/shadow-cljs/commit/9f79c967a9cb3f07ce2a72baf1ecb24a2ca49e3a) ] add new :devtools :build-notify option
- [ [`2c99f`](https://github.com/thheller/shadow-cljs/commit/2c99f926045caa910ea8334425b6f0e5a88fe0fa) ] use link to currently used version
- [ [`39100`](https://github.com/thheller/shadow-cljs/commit/3910033b214772a189ec0124b4630d1362310c43) ] fix incremental JS compile issue
- [ [`3fcea`](https://github.com/thheller/shadow-cljs/commit/3fceac2e9cc231b369f1051f10033499c7f9dcb0) ] fix classpath JS hot reload
- [ [`2a74e`](https://github.com/thheller/shadow-cljs/commit/2a74ec02e0c9c464736c94268d3176a85e7ead08) ] bump deps
- [ [`06015`](https://github.com/thheller/shadow-cljs/commit/06015e1c4d5d8b0127b27abb5b7112dc0d760af6) ] filter core.async from :dependencies as well

## [2.9.0](https://github.com/thheller/shadow-cljs/compare/6de1525f0101697abf4ae30e8be65af0355735f0...2725d0a11a19b081b5e2477576c42db28792b753) - 2020-05-09
- [ [`2725d`](https://github.com/thheller/shadow-cljs/commit/2725d0a11a19b081b5e2477576c42db28792b753) ] make :entries mandatory for :npm-module
- [ [`683a4`](https://github.com/thheller/shadow-cljs/commit/683a4fe564e434abd2c8c514841263dd553c66db) ] allow turning off source-map for react-native
- [ [`3b837`](https://github.com/thheller/shadow-cljs/commit/3b837904aeefb3b181aeeb193c5f839e3599ccaa) ] bump closure-compiler
- [ [`ff634`](https://github.com/thheller/shadow-cljs/commit/ff634cb373b50845bdf78a451216c7592efc48fc) ] [WIP] fix test targets
- [ [`82d67`](https://github.com/thheller/shadow-cljs/commit/82d67f19e128866379ea247733f0ab4f8857ce66) ] comment out unused classpath resolver fow now
- [ [`e62c8`](https://github.com/thheller/shadow-cljs/commit/e62c82a95d6d51515d0c6b0959a93397685894c8) ] fix classpath watch/reload
- [ [`1b5ea`](https://github.com/thheller/shadow-cljs/commit/1b5ea53bc55b9f7952658793d87ae5f563a4c8f6) ] expand jar quarantine
- [ [`7428c`](https://github.com/thheller/shadow-cljs/commit/7428c212b246f2ce179b2da39678f5d32931b772) ] get rid of shared classpath indexing
- [ [`75697`](https://github.com/thheller/shadow-cljs/commit/75697acadcb887ad97b2abc74b2a53f1476c501e) ] some UI work
- [ [`803ae`](https://github.com/thheller/shadow-cljs/commit/803ae73dd63ce4dc873305ce5809dd4bce43f3c7) ] Fix typo in deps.edn. (#699)
- [ [`59aef`](https://github.com/thheller/shadow-cljs/commit/59aefd79293ae43a6e196c76e6dd68c88d17c013) ] get rid of undefined ShadowJS warning
- [ [`1b9b9`](https://github.com/thheller/shadow-cljs/commit/1b9b95bc9353b8df7446f15c5f707d3a5f7c4e41) ] default to fail on asset require
- [ [`21e59`](https://github.com/thheller/shadow-cljs/commit/21e5960a73ee1e9f7e1efdf786ea582e99d6a8ea) ] fix log message overflow issue
- [ [`6de15`](https://github.com/thheller/shadow-cljs/commit/6de1525f0101697abf4ae30e8be65af0355735f0) ] make sure cljs.user is always compiled

## [2.8.110](https://github.com/thheller/shadow-cljs/compare/d3017ca3590aceb660fb6b5795967d77d5a6aede...3650d6c14311e8ca754136aa73aea420e31b141a) - 2020-05-04
- [ [`3650d`](https://github.com/thheller/shadow-cljs/commit/3650d6c14311e8ca754136aa73aea420e31b141a) ] bump cljs
- [ [`3e4a8`](https://github.com/thheller/shadow-cljs/commit/3e4a81cdef1f5111c409dfd9d9cfc736a027b844) ] replace "Missing." status
- [ [`daf95`](https://github.com/thheller/shadow-cljs/commit/daf95e9dde0245112fc4c844098a9a0e7fcf8f75) ] allow configuring repl-runtime select logic
- [ [`80d56`](https://github.com/thheller/shadow-cljs/commit/80d56a807af3b7a921f10b858e38ff20f4de9bfe) ] revert lazy REPL init
- [ [`995ff`](https://github.com/thheller/shadow-cljs/commit/995ff403df1c937bda470b881d76760471f2f6ef) ] [WIP] test report tweak
- [ [`66a97`](https://github.com/thheller/shadow-cljs/commit/66a9762e78a909f469a9267d7bfa20adf4fd26a8) ] move UI-DRIVEN define to test env
- [ [`d6f21`](https://github.com/thheller/shadow-cljs/commit/d6f218e23a1c04405d88689c26065a9331a482aa) ] tweak devtools-info helper for browser ext
- [ [`e030d`](https://github.com/thheller/shadow-cljs/commit/e030d902cd1ef0ce5ea788a93c8cff07b8427eef) ] [WIP] --list command to list known tests
- [ [`2a5c0`](https://github.com/thheller/shadow-cljs/commit/2a5c03c8b150d4fea2448b37d2fa41988293591d) ] [WIP] tweak :node-test target
- [ [`c2e5d`](https://github.com/thheller/shadow-cljs/commit/c2e5d937ab40637180fb610db8d2d361369d8cd8) ] [UI] fix svg path error
- [ [`015e3`](https://github.com/thheller/shadow-cljs/commit/015e302213aeb7fc21d9f8b0bb300bd52d1d0acf) ] try making load errors a little more understandable
- [ [`e8938`](https://github.com/thheller/shadow-cljs/commit/e893808a3bd37d60b9ad5afaea8aafa9afa7e56b) ] remove some reflective calls
- [ [`d3017`](https://github.com/thheller/shadow-cljs/commit/d3017ca3590aceb660fb6b5795967d77d5a6aede) ] add :pre assert to prevent incorrect node-repl use

## [2.8.109](https://github.com/thheller/shadow-cljs/compare/04e636404e976761fb1eac675db55e5d12518246...04e636404e976761fb1eac675db55e5d12518246) - 2020-04-28
- [ [`04e63`](https://github.com/thheller/shadow-cljs/commit/04e636404e976761fb1eac675db55e5d12518246) ] actually fix $jscomp issue

## [2.8.108](https://github.com/thheller/shadow-cljs/compare/99cf055e361fd865f464f852a2aa8174b7696697...99cf055e361fd865f464f852a2aa8174b7696697) - 2020-04-28
- [ [`99cf0`](https://github.com/thheller/shadow-cljs/commit/99cf055e361fd865f464f852a2aa8174b7696697) ] fix polyfill $jscomp scoping issue

## [2.8.107](https://github.com/thheller/shadow-cljs/compare/c78c5b0651ab14c922d43b1dec3d89e4f5e18d99...cebb62be1338aef344a6482f6fd8aaa2ad20e0d9) - 2020-04-28
- [ [`cebb6`](https://github.com/thheller/shadow-cljs/commit/cebb62be1338aef344a6482f6fd8aaa2ad20e0d9) ] fix node-repl rejecting self-signed SSL certs
- [ [`c78c5`](https://github.com/thheller/shadow-cljs/commit/c78c5b0651ab14c922d43b1dec3d89e4f5e18d99) ] [UI] add warning box when ws is disconnected

## [2.8.106](https://github.com/thheller/shadow-cljs/compare/1b6fd82a5ac4d418c90a6860a383cacde618288b...1b6fd82a5ac4d418c90a6860a383cacde618288b) - 2020-04-27
- [ [`1b6fd`](https://github.com/thheller/shadow-cljs/commit/1b6fd82a5ac4d418c90a6860a383cacde618288b) ] fix another npm resolve problem

## [2.8.105](https://github.com/thheller/shadow-cljs/compare/4796a1090eb64de818d947aef356bcd0fecca863...4796a1090eb64de818d947aef356bcd0fecca863) - 2020-04-27
- [ [`4796a`](https://github.com/thheller/shadow-cljs/commit/4796a1090eb64de818d947aef356bcd0fecca863) ] relax jar quarantine rules

## [2.8.104](https://github.com/thheller/shadow-cljs/compare/e4c4748d6af24056991cd80e2f80efc96783f899...4debb27bb4191c1a1e2ae1c58e88d0e4a55c52e9) - 2020-04-26
- [ [`4debb`](https://github.com/thheller/shadow-cljs/commit/4debb27bb4191c1a1e2ae1c58e88d0e4a55c52e9) ] fix npm require regression
- [ [`8f88f`](https://github.com/thheller/shadow-cljs/commit/8f88f32969f9f8992ae5d04bf55bc3d27905e18b) ] smarter way to detect and filter bad jar files
- [ [`e4c47`](https://github.com/thheller/shadow-cljs/commit/e4c4748d6af24056991cd80e2f80efc96783f899) ] add support for Class-Path helper jars

## [2.8.103](https://github.com/thheller/shadow-cljs/compare/92d4dcb16b36660e3573c63767c08f177c082ee2...ebd27e49245dba89cd59d7fb795e6e9d69f7ceed) - 2020-04-26
- [ [`ebd27`](https://github.com/thheller/shadow-cljs/commit/ebd27e49245dba89cd59d7fb795e6e9d69f7ceed) ] use file content checksum for caching purposes
- [ [`ede40`](https://github.com/thheller/shadow-cljs/commit/ede401f6cfdb178472995a24b5fa465697e315b5) ] log REPL errors as warnings
- [ [`92d4d`](https://github.com/thheller/shadow-cljs/commit/92d4dcb16b36660e3573c63767c08f177c082ee2) ] fix bad :fn-arity warning

## [2.8.102](https://github.com/thheller/shadow-cljs/compare/3d331160fab53ea6b31477311540896ff532968e...3d331160fab53ea6b31477311540896ff532968e) - 2020-04-25
- [ [`3d331`](https://github.com/thheller/shadow-cljs/commit/3d331160fab53ea6b31477311540896ff532968e) ] fix package.json browser overrides for nested files

## [2.8.101](https://github.com/thheller/shadow-cljs/compare/a4a0039d19e3a23ca39420907493842baecd58e7...a4a0039d19e3a23ca39420907493842baecd58e7) - 2020-04-24
- [ [`a4a00`](https://github.com/thheller/shadow-cljs/commit/a4a0039d19e3a23ca39420907493842baecd58e7) ] bump cljs, closure-compiler

## [2.8.100](https://github.com/thheller/shadow-cljs/compare/61cba9399764a34c1c07c78cbf9d7c9f7860eb45...61cba9399764a34c1c07c78cbf9d7c9f7860eb45) - 2020-04-24
- [ [`61cba`](https://github.com/thheller/shadow-cljs/commit/61cba9399764a34c1c07c78cbf9d7c9f7860eb45) ] add :cross-chunk-method-motion compiler options

## [2.8.99](https://github.com/thheller/shadow-cljs/compare/a7e08f37a1abb4e5f02035da6e312c2800939800...022e1edab5908271992cda0d42300b4226c492ce) - 2020-04-21
- [ [`022e1`](https://github.com/thheller/shadow-cljs/commit/022e1edab5908271992cda0d42300b4226c492ce) ] add basic graaljs target
- [ [`da6dd`](https://github.com/thheller/shadow-cljs/commit/da6ddedbd393d1b4664226458f678bf849f62023) ] tweak config spec
- [ [`0ca37`](https://github.com/thheller/shadow-cljs/commit/0ca37f02eb4ce602d9c048a5c7517cfd83c43961) ] tweak missing-js error to include dependency trace
- [ [`7dad6`](https://github.com/thheller/shadow-cljs/commit/7dad6c0266d7d061ca3f508a10d3462e62c31fd7) ] looking for UI designer ...
- [ [`6ce4c`](https://github.com/thheller/shadow-cljs/commit/6ce4c6eb8c0d36e840cf1ee78519c8c93616b88d) ] filter more unwanted compile output in .jar libs
- [ [`98334`](https://github.com/thheller/shadow-cljs/commit/983344fec7752a3af8f06e0bde1076eca714a06d) ] make par-compile error reports readable
- [ [`076aa`](https://github.com/thheller/shadow-cljs/commit/076aa2bbbb8607367ed13074d6094f7c5718181c) ] make REPL init restore previous build sources
- [ [`90ef3`](https://github.com/thheller/shadow-cljs/commit/90ef32882e290437902bbeeb05327413b4a572c7) ] tweak UI so error reports are scrollable
- [ [`0a677`](https://github.com/thheller/shadow-cljs/commit/0a677317560511012d5002220af8210213bb3a86) ] tweak deps.edn
- [ [`72035`](https://github.com/thheller/shadow-cljs/commit/720358fb898499dbb4c62017ed9757e3eb32d73f) ] fix incorrect datatype
- [ [`f7470`](https://github.com/thheller/shadow-cljs/commit/f7470f5d69a1329a197a6d290ed9af04b0fb0ca2) ] update deps.edn
- [ [`a7e08`](https://github.com/thheller/shadow-cljs/commit/a7e08f37a1abb4e5f02035da6e312c2800939800) ] use proper name

## [2.8.98](https://github.com/thheller/shadow-cljs/compare/f62463633927f900fbdea02c321cfcedc38837c9...f62463633927f900fbdea02c321cfcedc38837c9) - 2020-04-18
- [ [`f6246`](https://github.com/thheller/shadow-cljs/commit/f62463633927f900fbdea02c321cfcedc38837c9) ] ensure REPL sources are written to disk

## [2.8.97](https://github.com/thheller/shadow-cljs/compare/368d33a99522b5f0acd828e05ebe4fc546bdb0c4...222c4f8132c5abbbd3a137993210b26b340d2053) - 2020-04-18
- [ [`222c4`](https://github.com/thheller/shadow-cljs/commit/222c4f8132c5abbbd3a137993210b26b340d2053) ] fix REPL ns resulting in too many results
- [ [`dd7d5`](https://github.com/thheller/shadow-cljs/commit/dd7d5b0e2d0b7959a156ebf7e81cff960d785789) ] fix REPL timing issue added in 2.8.95
- [ [`368d3`](https://github.com/thheller/shadow-cljs/commit/368d33a99522b5f0acd828e05ebe4fc546bdb0c4) ] remove unused REPL code

## [2.8.96](https://github.com/thheller/shadow-cljs/compare/ef9c468b2097e11b39f66ff72eb2800e9f4b2164...3d4ad998dc114118dc0fa2e85d7a314319eefc5c) - 2020-04-16
- [ [`3d4ad`](https://github.com/thheller/shadow-cljs/commit/3d4ad998dc114118dc0fa2e85d7a314319eefc5c) ] fix incorrect vec being passed to reset-resources
- [ [`ef9c4`](https://github.com/thheller/shadow-cljs/commit/ef9c468b2097e11b39f66ff72eb2800e9f4b2164) ] change browser devtools inject method

## [2.8.95](https://github.com/thheller/shadow-cljs/compare/3d9b3b4e904102586fbe5f96e0f037a590d43b98...a169a7d694ff433f6c277cc1e0635954c5bbca49) - 2020-04-12
- [ [`a169a`](https://github.com/thheller/shadow-cljs/commit/a169a7d694ff433f6c277cc1e0635954c5bbca49) ] begin changing REPL init logic
- [ [`48edc`](https://github.com/thheller/shadow-cljs/commit/48edcf1e7cacdda8d5340afd0793eee4cc9efa1b) ] Merge branch 'master' of github.com:thheller/shadow-cljs
- [ [`ffed9`](https://github.com/thheller/shadow-cljs/commit/ffed901eae54872e079b66256fde556ba99bee0a) ] fix CI, use simplified externs for npm sources
- [ [`c792f`](https://github.com/thheller/shadow-cljs/commit/c792fa149b511db5aac05b1808afe90890cc00dd) ] fix CI
- [ [`a58d9`](https://github.com/thheller/shadow-cljs/commit/a58d9081f188ad337dde6687f717c47b556b568a) ] Update README.md (#663)
- [ [`88928`](https://github.com/thheller/shadow-cljs/commit/88928d97a2b508b22b2913068ff1673619a1c57f) ] bump some deps
- [ [`832bc`](https://github.com/thheller/shadow-cljs/commit/832bc069b19397f4719d843627712bbbed94c158) ] add http2 node module
- [ [`c0ed2`](https://github.com/thheller/shadow-cljs/commit/c0ed2c691de34af0e94c4b9687a97d09f9e78a71) ] fix indent
- [ [`3d9b3`](https://github.com/thheller/shadow-cljs/commit/3d9b3b4e904102586fbe5f96e0f037a590d43b98) ] remove leftover prn

## [2.8.94](https://github.com/thheller/shadow-cljs/compare/96c890cad7acac0e415b7bd80915c7ea5ed2f424...5de30211616185952c6f19725144813fd30038c4) - 2020-03-27
- [ [`5de30`](https://github.com/thheller/shadow-cljs/commit/5de30211616185952c6f19725144813fd30038c4) ] slightly improve UI builds page
- [ [`8b70d`](https://github.com/thheller/shadow-cljs/commit/8b70dee5faf12ab801e5b591349db8b2f7e5c982) ] Lock down build-helper-maven-plugin version for pom.xml generation (#668)
- [ [`b9af1`](https://github.com/thheller/shadow-cljs/commit/b9af1b2255c20f974d3512aeaa1bb56761b63aa4) ] drop mkdirp dependency
- [ [`96c89`](https://github.com/thheller/shadow-cljs/commit/96c890cad7acac0e415b7bd80915c7ea5ed2f424) ] fix IllegalStateException for :npm-module builds

## [2.8.93](https://github.com/thheller/shadow-cljs/compare/458b032430a753687c56593cd6d7cb427b33108d...458b032430a753687c56593cd6d7cb427b33108d) - 2020-03-16
- [ [`458b0`](https://github.com/thheller/shadow-cljs/commit/458b032430a753687c56593cd6d7cb427b33108d) ] fix invoke tweak bug for IIIK

## [2.8.92](https://github.com/thheller/shadow-cljs/compare/47d7cc24bbcfe0e7a2d94e8eaf26e42ad7635266...f8db27e9184392cd8a379160b4c065981e1630e6) - 2020-03-10
- [ [`f8db2`](https://github.com/thheller/shadow-cljs/commit/f8db27e9184392cd8a379160b4c065981e1630e6) ] remove cli version print
- [ [`d61b1`](https://github.com/thheller/shadow-cljs/commit/d61b1845b43e69c23a09d7a7359ccfdd75b02219) ] Merge branch 'master' of github.com:thheller/shadow-cljs
- [ [`47d7c`](https://github.com/thheller/shadow-cljs/commit/47d7cc24bbcfe0e7a2d94e8eaf26e42ad7635266) ] Fix for --config-merge "{:main foo/bar}" (#666)

## [2.8.91](https://github.com/thheller/shadow-cljs/compare/3b6561f1ce927a2c00b875b1ca32c2f3c2acb0e0...fdbfe7cc02eb44d23eccd4fd4ba6e857d48c2ce2) - 2020-03-06
- [ [`fdbfe`](https://github.com/thheller/shadow-cljs/commit/fdbfe7cc02eb44d23eccd4fd4ba6e857d48c2ce2) ] support :log-chan option in watch
- [ [`f053e`](https://github.com/thheller/shadow-cljs/commit/f053e7673611e9702de5b134c4938231e94c931c) ] tweak error message
- [ [`e4621`](https://github.com/thheller/shadow-cljs/commit/e46214b87ce1bbad2fb48c19588c50e972310812) ] tweak defonce to make it more DCE friendly
- [ [`3b656`](https://github.com/thheller/shadow-cljs/commit/3b6561f1ce927a2c00b875b1ca32c2f3c2acb0e0) ] put Math.imul fix behind a flag

## [2.8.90](https://github.com/thheller/shadow-cljs/compare/c7b83911c837c0f3a06e3e6d39cad00d72bb4d00...ea1e933169c6068e61a3e7b349f738fe54b876e0) - 2020-02-28
- [ [`ea1e9`](https://github.com/thheller/shadow-cljs/commit/ea1e933169c6068e61a3e7b349f738fe54b876e0) ] fix SSL support for UI
- [ [`c7b83`](https://github.com/thheller/shadow-cljs/commit/c7b83911c837c0f3a06e3e6d39cad00d72bb4d00) ] add support for :display-name in :dev-http config

## [2.8.89](https://github.com/thheller/shadow-cljs/compare/3ff6b4f853c68a1006b2dec538b94c33f0813568...c73ec20adfd4eb7fac5ed8079165a3d47e5312a2) - 2020-02-27
- [ [`c73ec`](https://github.com/thheller/shadow-cljs/commit/c73ec20adfd4eb7fac5ed8079165a3d47e5312a2) ] oops. remove debug tap
- [ [`8d70d`](https://github.com/thheller/shadow-cljs/commit/8d70dc59af4bf4815a5b646fedb2c2f4d9e5bcf3) ] also allow :trusted-hosts in user-config
- [ [`3ff6b`](https://github.com/thheller/shadow-cljs/commit/3ff6b4f853c68a1006b2dec538b94c33f0813568) ] add ipv6 loopback default

## [2.8.88](https://github.com/thheller/shadow-cljs/compare/88cf43aca33c2985057899638794fbb58ea5b8a6...420be5f79cb5f740346135a4a23688e71284565a) - 2020-02-26
- [ [`420be`](https://github.com/thheller/shadow-cljs/commit/420be5f79cb5f740346135a4a23688e71284565a) ] add missing module-loaded calls for react-native
- [ [`93674`](https://github.com/thheller/shadow-cljs/commit/93674858801867449308823bf9f9de372199a939) ] restrict access to tool ws
- [ [`88cf4`](https://github.com/thheller/shadow-cljs/commit/88cf43aca33c2985057899638794fbb58ea5b8a6) ] change nrepl default to localhost only

## [2.8.87](https://github.com/thheller/shadow-cljs/compare/b81ea1a1da62e2918ae61c78708eb8e6f9d29ec5...b81ea1a1da62e2918ae61c78708eb8e6f9d29ec5) - 2020-02-26
- [ [`b81ea`](https://github.com/thheller/shadow-cljs/commit/b81ea1a1da62e2918ae61c78708eb8e6f9d29ec5) ] make reload-strategy work with react-native builds

## [2.8.86 - fix bad ui bundle](https://github.com/thheller/shadow-cljs/compare/...) - 2020-02-24

## [2.8.85](https://github.com/thheller/shadow-cljs/compare/6f57f807c3a542742271c9f225c8f55176f0666e...712bf4c941875fbdd14ee7907eb2abaa8c78c6ce) - 2020-02-24
- [ [`712bf`](https://github.com/thheller/shadow-cljs/commit/712bf4c941875fbdd14ee7907eb2abaa8c78c6ce) ] add :devtools {:reload-strategy :full} config
- [ [`6f57f`](https://github.com/thheller/shadow-cljs/commit/6f57f807c3a542742271c9f225c8f55176f0666e) ] fix :source-map-inline issue with :chrome-extension

## [2.8.84](https://github.com/thheller/shadow-cljs/compare/7b66943c5cc412b5e21a98c5bbc893c01a8c905d...348da009647e6289bab8f6e2edac2f46b7eacff7) - 2020-02-24
- [ [`348da`](https://github.com/thheller/shadow-cljs/commit/348da009647e6289bab8f6e2edac2f46b7eacff7) ] [UI] minor tweaks
- [ [`8c59c`](https://github.com/thheller/shadow-cljs/commit/8c59c9b6703f1490fc26aa51354db36e7b336d4a) ] add test for previous commit
- [ [`e18b1`](https://github.com/thheller/shadow-cljs/commit/e18b134777a0c321e15a10fc04520fc1261ea033) ] handle :refer + :rename properly
- [ [`ba596`](https://github.com/thheller/shadow-cljs/commit/ba5968a8105d0da4e9b3cc3a20eef8060b3d473f) ] bump core.async
- [ [`7149f`](https://github.com/thheller/shadow-cljs/commit/7149f680ddb4f79fa3f71fe55d08e14d019a19a1) ] bump core.async
- [ [`94c99`](https://github.com/thheller/shadow-cljs/commit/94c99bd609bf674e24b47964d37c89e57e838414) ] add source-map-inline for chrome-exts
- [ [`0c1aa`](https://github.com/thheller/shadow-cljs/commit/0c1aaee02bb3f89dcabaad8a78f487c1adf0ac5d) ] UI stuff, not sure where this is going
- [ [`89892`](https://github.com/thheller/shadow-cljs/commit/89892d6df6440ff1c0f7db308ba1f34665b65929) ] use atom instead of volatile!
- [ [`3ea78`](https://github.com/thheller/shadow-cljs/commit/3ea7806bcc1a09464d349498918f451931f74efc) ] [UI] experimental eval support for inspect
- [ [`7bb00`](https://github.com/thheller/shadow-cljs/commit/7bb0080fe39569cf8caa42342c9c666a00b5b0b1) ] [UI] format build warnings/log a little bit
- [ [`1f3e2`](https://github.com/thheller/shadow-cljs/commit/1f3e21268befd582015dabf53ec871eeb4079b33) ] fix double compiles for .cljc files with macros
- [ [`d2b56`](https://github.com/thheller/shadow-cljs/commit/d2b56b3e61d36eabb7c153465c56e4289965270b) ] remove repeated error message
- [ [`bea2d`](https://github.com/thheller/shadow-cljs/commit/bea2d7d464a1ef2f2f2704ff995cb426b10a0447) ] Provide descriptive error message on failed load-extern-properties (#641)
- [ [`2b442`](https://github.com/thheller/shadow-cljs/commit/2b44244ade917d7636c9b2c78d1805989379b5fb) ] check that shadow.loader API is not called before init (#647)
- [ [`81516`](https://github.com/thheller/shadow-cljs/commit/815161041463c5cd846f7a310a8044f032643da3) ] gonna do some not-UI things for a while ...
- [ [`666c4`](https://github.com/thheller/shadow-cljs/commit/666c42b6a61f6f8995767d05a7b8164b75d33061) ] [UI] I'm still no good at building UI/UX
- [ [`1907d`](https://github.com/thheller/shadow-cljs/commit/1907d0bb6452c10798b7367aee0aeb597d868ce0) ] add optimized MultiFn invoke path
- [ [`cc562`](https://github.com/thheller/shadow-cljs/commit/cc5629c2a9ee5f984bd7bd61b9d95edda9c5cebe) ] remove leftover debug tap
- [ [`2381c`](https://github.com/thheller/shadow-cljs/commit/2381c572654b3838c88d13a5692de182089939b6) ] actually fix tests
- [ [`38a71`](https://github.com/thheller/shadow-cljs/commit/38a71a737d5287d7662cd51c705fb0f4be880547) ] fix tests
- [ [`f5134`](https://github.com/thheller/shadow-cljs/commit/f513413db2bb8b7a3fbd746527f93389a4370b9c) ] add invoke tweaks
- [ [`8ac9e`](https://github.com/thheller/shadow-cljs/commit/8ac9e5ee12587ed752a3987fc26d14c37cf40cbd) ] disable extra injected src for now too
- [ [`ecccd`](https://github.com/thheller/shadow-cljs/commit/ecccd020d7f0e4779e2d181306eec71408d39298) ] disable previous tweaks for now
- [ [`182bc`](https://github.com/thheller/shadow-cljs/commit/182bcae82f4c232ef7488f0a6090cc07a63cc747) ] warning for ignored :source-paths/:dependencies
- [ [`b0b25`](https://github.com/thheller/shadow-cljs/commit/b0b25399e014e596a21e2cf164e9ac1ff04d4832) ] defprotocol tweak
- [ [`11480`](https://github.com/thheller/shadow-cljs/commit/1148058e263b2c2caacdec1334b91e5c3c713d6b) ] further invoke tweaks, cljs tests
- [ [`c94c7`](https://github.com/thheller/shadow-cljs/commit/c94c77d5a62af490fe5aae06e24e656052daec57) ] add a few more test filtering options
- [ [`e77f6`](https://github.com/thheller/shadow-cljs/commit/e77f60936cb5f63ba664212e5aa68b157e2fafc5) ] make tests happy
- [ [`7024f`](https://github.com/thheller/shadow-cljs/commit/7024ff149b9e6ae0e91aa98f8f3c67a2a2a54542) ] replacing cljs.analyzer/parse-invoke*
- [ [`682d2`](https://github.com/thheller/shadow-cljs/commit/682d27a76d2af35cac6c62aa01df4a0cdb990e78) ] [UI] minor tweaks
- [ [`9cfb4`](https://github.com/thheller/shadow-cljs/commit/9cfb40d2bd57bad90fc9062d516500cb10532cf2) ] compiler hack for function invoke
- [ [`72069`](https://github.com/thheller/shadow-cljs/commit/72069ecf35f18af328b1625b1108174a3cf7d924) ] [UI] inspect vlist using grid
- [ [`98ae0`](https://github.com/thheller/shadow-cljs/commit/98ae065d015d1ddef38fa66e44d67699acc427cd) ] [UI] fix vlist slice generation, fix db-explorer scroll
- [ [`78f9b`](https://github.com/thheller/shadow-cljs/commit/78f9b253b5a95a6a5848af45533887e542f667fb) ] more UI work
- [ [`983db`](https://github.com/thheller/shadow-cljs/commit/983dbaab062c5bf50aec488352c672aed9400edf) ] first basic cards prototype
- [ [`06182`](https://github.com/thheller/shadow-cljs/commit/061826de78f9e8bc936de5dcf599816c03806575) ] more cleanup
- [ [`37bc5`](https://github.com/thheller/shadow-cljs/commit/37bc55124d3a8d5370c167e0ba30e02abf5ee34f) ] more moving, cleanup
- [ [`7e1c0`](https://github.com/thheller/shadow-cljs/commit/7e1c0060f5f379af4f3c8bcb1b8d99b8dd05d30d) ] delete some old unused files
- [ [`347bd`](https://github.com/thheller/shadow-cljs/commit/347bd3c6bb110a229275c3bfea6db51be80e827d) ] Update README.md (#639)
- [ [`cac23`](https://github.com/thheller/shadow-cljs/commit/cac234df62c5077544a1f6be78ebebf2d085eab7) ] remove debug logs
- [ [`249be`](https://github.com/thheller/shadow-cljs/commit/249be1f212f344b622d8ad649fd5b51919de716b) ] ui cleanup, need to figure out a cleaner structure
- [ [`df85a`](https://github.com/thheller/shadow-cljs/commit/df85a74b38cf7e81dbf396a3e6aed4540dc0c7dd) ] updated cljs-karaoke-client description (#626)
- [ [`d1edb`](https://github.com/thheller/shadow-cljs/commit/d1edb35143b99a36e04e35ad332caf1529fed346) ] revert previous change for workers
- [ [`76a80`](https://github.com/thheller/shadow-cljs/commit/76a8034ff208eb6ac047edec3311f4bfd508ea80) ] use full scriptURL for eval sources
- [ [`63089`](https://github.com/thheller/shadow-cljs/commit/63089e9ea9c3d9db525c2aa092a234a2438312c5) ] shadow-keywords only works in browser builds for now
- [ [`fd362`](https://github.com/thheller/shadow-cljs/commit/fd362699ca748d20cc452ca19ad1d4079663d56d) ] try starting the UI worker ASAP
- [ [`90853`](https://github.com/thheller/shadow-cljs/commit/90853ff671efcffb7a80d56e32a7def672530391) ] remove fulcro deps
- [ [`1ed7e`](https://github.com/thheller/shadow-cljs/commit/1ed7ee30a8c37ab91e27d3444054ad32de5514a0) ] rewrite UI using experimental arborist/grove lib
- [ [`511e0`](https://github.com/thheller/shadow-cljs/commit/511e0b8fde0a23792e6f369b8c2c4469b4b127d5) ] bump shadow-cljs jar downloader
- [ [`7b669`](https://github.com/thheller/shadow-cljs/commit/7b66943c5cc412b5e21a98c5bbc893c01a8c905d) ] update clojars repo URL

## [2.8.83](https://github.com/thheller/shadow-cljs/compare/2bceed2b6ac3b8cf5cae110d319553394aba8156...79e9d9c6344a3818f0d6024e5ca53b6bdf1aec91) - 2019-12-12
- [ [`79e9d`](https://github.com/thheller/shadow-cljs/commit/79e9d9c6344a3818f0d6024e5ca53b6bdf1aec91) ] stop repl reconnect attempts if worker isn't running
- [ [`5ee9e`](https://github.com/thheller/shadow-cljs/commit/5ee9efcb7cae4ec558227bd04aa9d083c8aefc7c) ] fix spec cache issue
- [ [`2bcee`](https://github.com/thheller/shadow-cljs/commit/2bceed2b6ac3b8cf5cae110d319553394aba8156) ] Support ws reconnect in browser (#614)

## [2.8.82](https://github.com/thheller/shadow-cljs/compare/fb10d231b7a0ec673dde70a37e5a1425fd1e126b...8d5458c029a2f4274dfd44e387c872c63b609a03) - 2019-12-11
- [ [`8d545`](https://github.com/thheller/shadow-cljs/commit/8d5458c029a2f4274dfd44e387c872c63b609a03) ] fix :npm-module source maps
- [ [`fb10d`](https://github.com/thheller/shadow-cljs/commit/fb10d231b7a0ec673dde70a37e5a1425fd1e126b) ] optimize shadow.json a bit

## [2.8.81](https://github.com/thheller/shadow-cljs/compare/ef63315e2657540f884366063375f4b3de1793f6...fda0dc02d8199d5475a3ab42a3d2fd247c237235) - 2019-12-06
- [ [`fda0d`](https://github.com/thheller/shadow-cljs/commit/fda0dc02d8199d5475a3ab42a3d2fd247c237235) ] update goog.global replacement code
- [ [`1556e`](https://github.com/thheller/shadow-cljs/commit/1556e7badc537299fc2501a839ff8a8dddd8203e) ] add :source-map-suffix ".foo" for source map files
- [ [`8e44b`](https://github.com/thheller/shadow-cljs/commit/8e44b926256c60e2cc044b5a52562562cea00fb2) ] remove leftover debug tap>
- [ [`ef633`](https://github.com/thheller/shadow-cljs/commit/ef63315e2657540f884366063375f4b3de1793f6) ] fix nrepl-select not using :runtime-id if set

## [2.8.80](https://github.com/thheller/shadow-cljs/compare/89b27e4fab4d3efdb993e9901f2a28ed0cf33331...89b27e4fab4d3efdb993e9901f2a28ed0cf33331) - 2019-12-04
- [ [`89b27`](https://github.com/thheller/shadow-cljs/commit/89b27e4fab4d3efdb993e9901f2a28ed0cf33331) ] add support for :build-defaults/:target-defaults

## [2.8.79](https://github.com/thheller/shadow-cljs/compare/9e893b1f21f41004c45124911a4562ccb0f826a0...8ba19031515b25fc0a0fb4cf06868ca20ac90cd4) - 2019-12-04
- [ [`8ba19`](https://github.com/thheller/shadow-cljs/commit/8ba19031515b25fc0a0fb4cf06868ca20ac90cd4) ] put experimental compiler tweaks behind a flag
- [ [`44217`](https://github.com/thheller/shadow-cljs/commit/442176c5c3135c36364386ecdca5762a1d633773) ] fix analyze-top hack
- [ [`9e893`](https://github.com/thheller/shadow-cljs/commit/9e893b1f21f41004c45124911a4562ccb0f826a0) ] bump core.async

## [2.8.78](https://github.com/thheller/shadow-cljs/compare/8a90e62c6af3ece959d39b8d26c49d3bc72159b6...5778dba91516f8be3ed6ea9f8de4c879b37dedae) - 2019-12-03
- [ [`5778d`](https://github.com/thheller/shadow-cljs/commit/5778dba91516f8be3ed6ea9f8de4c879b37dedae) ] fix :closure-defines in :npm-module dev builds
- [ [`8a90e`](https://github.com/thheller/shadow-cljs/commit/8a90e62c6af3ece959d39b8d26c49d3bc72159b6) ] tweak web worker support a bit

## [2.8.77](https://github.com/thheller/shadow-cljs/compare/cb0805c64637c564bfcc3c056aeaecd121cb6203...420dcba03a58d6a514cc579c42622319cfa822db) - 2019-12-02
- [ [`420dc`](https://github.com/thheller/shadow-cljs/commit/420dcba03a58d6a514cc579c42622319cfa822db) ] adjust ifn hack again
- [ [`0ab73`](https://github.com/thheller/shadow-cljs/commit/0ab73ce78b94d229f230f680c4cb0985e9ff4169) ] allow :compiler-options {:source-map-comment false}
- [ [`2155f`](https://github.com/thheller/shadow-cljs/commit/2155f7938f577d7bd4bd9d9c786bfb331b7cce72) ] add :release-version option
- [ [`07c52`](https://github.com/thheller/shadow-cljs/commit/07c523c58400d822989334f0786c2c9804d0e948) ] tweak IFn invoke hack
- [ [`66217`](https://github.com/thheller/shadow-cljs/commit/66217b0515255ec01be00446e02fe9eba6d68e46) ] remove all log color. works better with dark mode
- [ [`bf19a`](https://github.com/thheller/shadow-cljs/commit/bf19a8b6a074ff6d18620887cb99e444bf5ba453) ] make browser log more dark-mode friendly
- [ [`7f30f`](https://github.com/thheller/shadow-cljs/commit/7f30fcc872a3e42fcd9b160040459af2fcbb9ac4) ] remove defonce override
- [ [`cb080`](https://github.com/thheller/shadow-cljs/commit/cb0805c64637c564bfcc3c056aeaecd121cb6203) ] switch to use binding instead of env

## [2.8.76](https://github.com/thheller/shadow-cljs/compare/704df4441db0a7df08fb131600af30bf4bd17afc...704df4441db0a7df08fb131600af30bf4bd17afc) - 2019-11-25
- [ [`704df`](https://github.com/thheller/shadow-cljs/commit/704df4441db0a7df08fb131600af30bf4bd17afc) ] fix goog-define replacement not working in AOT

## [2.8.75](https://github.com/thheller/shadow-cljs/compare/f7b801e3d13fdecbbd4add486d6d45e1f0446d1b...5a8425965b5715eca966625141833131bd2247bb) - 2019-11-25
- [ [`5a842`](https://github.com/thheller/shadow-cljs/commit/5a8425965b5715eca966625141833131bd2247bb) ] experimental IFn optmization for known IFn impls
- [ [`711a5`](https://github.com/thheller/shadow-cljs/commit/711a5776525afc9968346479b709e1975452a47a) ] tweak shadow-keywords to skip emitting the hash
- [ [`de44f`](https://github.com/thheller/shadow-cljs/commit/de44f6cc6261b02c9f448bed67519f9b1ea2dcb0) ] add micro opt for namespaced keywords
- [ [`b6e54`](https://github.com/thheller/shadow-cljs/commit/b6e547da284225918d47e883ee45610a1a47df00) ] add experimental support for macro "lifting"
- [ [`5672f`](https://github.com/thheller/shadow-cljs/commit/5672f8d0cab1b3ae1f811cc84667d893eeb14823) ] apply CLJS-3003
- [ [`76819`](https://github.com/thheller/shadow-cljs/commit/76819073c03cea7c7dffceff313e3795bf40e36d) ] npm-module dummy build
- [ [`e1ac1`](https://github.com/thheller/shadow-cljs/commit/e1ac146ff22eb792f08f99fcab9c5043b279615d) ] properly remove css requires from processed code
- [ [`0b358`](https://github.com/thheller/shadow-cljs/commit/0b3589190759a9079311f47dc93bcdda50d82944) ] update babel-worker to use newer babel and ncc
- [ [`f7b80`](https://github.com/thheller/shadow-cljs/commit/f7b801e3d13fdecbbd4add486d6d45e1f0446d1b) ] fix invoke tweak

## [2.8.74](https://github.com/thheller/shadow-cljs/compare/bb1cdc59224cc35ece12b3039f442faaacc0ae22...4793d2eb915edf4d0d2cc19ffe53276e474ebe61) - 2019-11-20
- [ [`4793d`](https://github.com/thheller/shadow-cljs/commit/4793d2eb915edf4d0d2cc19ffe53276e474ebe61) ] attempt to fix undeclared $jscomp issue
- [ [`bd2bf`](https://github.com/thheller/shadow-cljs/commit/bd2bf1935b1714b05a63744c0c998d44afd6c1ed) ] remove goog.define override for :npm-module
- [ [`6ba84`](https://github.com/thheller/shadow-cljs/commit/6ba842355fc094cce12e2621ef3191e013678fae) ] add experimental function invoke optimization
- [ [`9aefd`](https://github.com/thheller/shadow-cljs/commit/9aefd9931d840643947dd3929d5aeb1f961e81ff) ] fix another core.async blocking check issue
- [ [`f60ae`](https://github.com/thheller/shadow-cljs/commit/f60aebf7d114dd528ed171fbb9470644edbf6296) ] Merge branch 'master' of github.com:thheller/shadow-cljs
- [ [`a39eb`](https://github.com/thheller/shadow-cljs/commit/a39eb1630ae40bfa3f2ac85415b429367ab47239) ] fix old references using :system-config
- [ [`bb1cd`](https://github.com/thheller/shadow-cljs/commit/bb1cdc59224cc35ece12b3039f442faaacc0ae22) ] Added support to load log level from user config (#600)

## [2.8.73](https://github.com/thheller/shadow-cljs/compare/8d3f52d53120a070a1182975b86d9db509ce3870...8d3f52d53120a070a1182975b86d9db509ce3870) - 2019-11-19
- [ [`8d3f5`](https://github.com/thheller/shadow-cljs/commit/8d3f52d53120a070a1182975b86d9db509ce3870) ] fix :npm-module issue with COMPILED var missing

## [2.8.72](https://github.com/thheller/shadow-cljs/compare/870272600726fb2f61ea57f95ce36636f31cc956...870272600726fb2f61ea57f95ce36636f31cc956) - 2019-11-19
- [ [`87027`](https://github.com/thheller/shadow-cljs/commit/870272600726fb2f61ea57f95ce36636f31cc956) ] bump cljs 1.10.597

## [2.8.71](https://github.com/thheller/shadow-cljs/compare/28ab3b3914f3eee1e187ab689869664b38730b07...28ab3b3914f3eee1e187ab689869664b38730b07) - 2019-11-19
- [ [`28ab3`](https://github.com/thheller/shadow-cljs/commit/28ab3b3914f3eee1e187ab689869664b38730b07) ] replace clj-check once again

## [2.8.70](https://github.com/thheller/shadow-cljs/compare/3a48f47d61b5a34915a89f20445aa28d7a5ee384...f2289230b916142d0f8f6e21ca408d3813046300) - 2019-11-18
- [ [`f2289`](https://github.com/thheller/shadow-cljs/commit/f2289230b916142d0f8f6e21ca408d3813046300) ] use a regular file for CLI aliveness check
- [ [`c3f89`](https://github.com/thheller/shadow-cljs/commit/c3f89674f9820a0af31b0fa409193a270ef40b13) ] remove runtime-eval from UI for now
- [ [`2eac1`](https://github.com/thheller/shadow-cljs/commit/2eac119c9e4be332493b50c4424e4e83a0fd201f) ] add :devtools {:autobuild false} config option
- [ [`8f332`](https://github.com/thheller/shadow-cljs/commit/8f3324d1f15cd94f1709f6749eb5927e6bebd719) ] make node-library exports map reloadable in dev
- [ [`b4145`](https://github.com/thheller/shadow-cljs/commit/b41458048874296ea25d605d3adb4db525578875) ] remove unreleased dep
- [ [`420a4`](https://github.com/thheller/shadow-cljs/commit/420a42f2886bccd6ad5f810284cbef32bac035b0) ] bump core.async, fix go-checking issues
- [ [`f0c61`](https://github.com/thheller/shadow-cljs/commit/f0c61f61f92442f64705ee29ac7c9ac1bf2e851f) ] [WIP] eval-cljs kinda working
- [ [`43050`](https://github.com/thheller/shadow-cljs/commit/43050cf14338236a30d823a247e51e8cdf1a51ec) ] allow configuring :transport-fn for nrepl
- [ [`b7994`](https://github.com/thheller/shadow-cljs/commit/b79948ae897bbbe6979b63d4a8139a8d82de85dc) ] optimize defonce for release builds
- [ [`7af6e`](https://github.com/thheller/shadow-cljs/commit/7af6e7fc290871431611b88e31bd3f56e88c58e3) ] allow passing :runtime-id via nrepl-select opts
- [ [`c5a63`](https://github.com/thheller/shadow-cljs/commit/c5a638e028bf0a1c7cf49993f30d9550d1a5b563) ] make map inspect a bit more compact
- [ [`71927`](https://github.com/thheller/shadow-cljs/commit/719279d63b4b8eec04c4d76170f3bf8b8ce2260e) ] begin eval support for CLJS runtimes
- [ [`5f20e`](https://github.com/thheller/shadow-cljs/commit/5f20e4cee68526b54a218a6cfb4d015b99b05b5b) ] tweak inspect UI
- [ [`c93a3`](https://github.com/thheller/shadow-cljs/commit/c93a36609e5e0abd0b1a068f6218242262e026ad) ] slightly improve REPL display of compile errors
- [ [`dc7ba`](https://github.com/thheller/shadow-cljs/commit/dc7baf8f33528da70bfd89e252b3e4bee158a2c2) ] fix load-file conflicting with ns defined in REPL
- [ [`60ddb`](https://github.com/thheller/shadow-cljs/commit/60ddb6f53b8015d403ab1bc9e7c35a07090ab12f) ] fix find-js-require-pass not finding js/require created by macros
- [ [`3a48f`](https://github.com/thheller/shadow-cljs/commit/3a48f47d61b5a34915a89f20445aa28d7a5ee384) ] Use :val instead of :msg and pr-str the :ns (#585)

## [2.8.69](https://github.com/thheller/shadow-cljs/compare/d38ad42d30e232de4354e7bd3d525b55763691c1...859809dba535f30788cc382a45cb1d045b24f008) - 2019-11-01
- [ [`85980`](https://github.com/thheller/shadow-cljs/commit/859809dba535f30788cc382a45cb1d045b24f008) ] fix missing process inject for classpath
- [ [`314b4`](https://github.com/thheller/shadow-cljs/commit/314b403e6c8eea573605555c93b7d01477c781ef) ] fix Inspect page sometimes missing updates
- [ [`da713`](https://github.com/thheller/shadow-cljs/commit/da71335d13e5d895a529abd3f1b4be276fb24d0a) ] make prepl return :val as string
- [ [`6c9c5`](https://github.com/thheller/shadow-cljs/commit/6c9c58dc64d42eb9ced0ab8cf019827b19e735b3) ] cut UI build size by almost 1/4
- [ [`f46e0`](https://github.com/thheller/shadow-cljs/commit/f46e04d8325814109d3a71acef5238efac7d964d) ] fix shadow.js load issues of modules that aren't provided
- [ [`aaa3a`](https://github.com/thheller/shadow-cljs/commit/aaa3afd3027bc9457d53fe452aae717e8de77315) ] update to latest closure-compiler, closure-library
- [ [`fd3f9`](https://github.com/thheller/shadow-cljs/commit/fd3f94d7e0e15cc95ef2dafe7e55e8393476d0aa) ] bump fulcro
- [ [`d38ad`](https://github.com/thheller/shadow-cljs/commit/d38ad42d30e232de4354e7bd3d525b55763691c1) ] fix debug macros

## [2.8.68](https://github.com/thheller/shadow-cljs/compare/a2ec357f06ed0eaaba911155ebe8c2ea13ff4b85...829fefb4fbbca283ba8d253b4c6eba3b8906b6e0) - 2019-10-29
- [ [`829fe`](https://github.com/thheller/shadow-cljs/commit/829fefb4fbbca283ba8d253b4c6eba3b8906b6e0) ] swap out some core.async use
- [ [`c03b6`](https://github.com/thheller/shadow-cljs/commit/c03b6bedb617a16e224e36e64ddbc6cf87b760f0) ] optimize reload-npm
- [ [`f3953`](https://github.com/thheller/shadow-cljs/commit/f395334fbe759ba33f91545ab8d251cb8ce978ad) ] optimize reload-macros
- [ [`e7edc`](https://github.com/thheller/shadow-cljs/commit/e7edc1a3c7356bca3e1ea13fffc871fa0e375c6a) ] temp fix for REPL with :npm-module and webpack
- [ [`2996e`](https://github.com/thheller/shadow-cljs/commit/2996ec1bd957a2b13ce4e5e73fb444d150cf0bf7) ] make :output-wrapper for multi-module builds as well
- [ [`1c006`](https://github.com/thheller/shadow-cljs/commit/1c006b80859a1f832795f955457a2b6c9a822da4) ] fix inspect for sets
- [ [`06746`](https://github.com/thheller/shadow-cljs/commit/06746be58921ce969cd36b6332a2b7961b785f8c) ] treat deps.edn git deps as if from .jar
- [ [`89214`](https://github.com/thheller/shadow-cljs/commit/8921431d92fc26c51ae2c416b049576869f5ff73) ] basic eval support for inspect UI
- [ [`d0de1`](https://github.com/thheller/shadow-cljs/commit/d0de1386487d57dce3e30e2f1af47234fd227894) ] [WIP] rewrite shadow.remote.runtime impl
- [ [`37fc4`](https://github.com/thheller/shadow-cljs/commit/37fc47dee9235c9fe413858c336b92f96b249cca) ] more Inspect tweaks, small debug helper macros
- [ [`a2ec3`](https://github.com/thheller/shadow-cljs/commit/a2ec357f06ed0eaaba911155ebe8c2ea13ff4b85) ] more inspect UI tweaks

## [2.8.67](https://github.com/thheller/shadow-cljs/compare/ea109e5bd3dfee2143b2b5131b79a5a7b17ba9dc...3f76854c800aafab6135c498062ef52ce81b5e22) - 2019-10-24
- [ [`3f768`](https://github.com/thheller/shadow-cljs/commit/3f76854c800aafab6135c498062ef52ce81b5e22) ] [WIP] implement more inspect UI
- [ [`ea109`](https://github.com/thheller/shadow-cljs/commit/ea109e5bd3dfee2143b2b5131b79a5a7b17ba9dc) ] [WIP] first preview of shadow.remote

## [2.8.66](https://github.com/thheller/shadow-cljs/compare/2d233b7b87005993bbc3d5faca58ca3655b745dc...2d233b7b87005993bbc3d5faca58ca3655b745dc) - 2019-10-21
- [ [`2d233`](https://github.com/thheller/shadow-cljs/commit/2d233b7b87005993bbc3d5faca58ca3655b745dc) ] adjust nrepl once more

## [2.8.65](https://github.com/thheller/shadow-cljs/compare/c80de197a4ee2eca74a29c2f9830fcbdd9daeb67...e890c3d08224f2d622a20f4ae29db67188581806) - 2019-10-21
- [ [`e890c`](https://github.com/thheller/shadow-cljs/commit/e890c3d08224f2d622a20f4ae29db67188581806) ] fix nrepl middleware related to clone
- [ [`99599`](https://github.com/thheller/shadow-cljs/commit/9959972c3cd48cadf5a8ac1fc3e5f33726c57519) ] tweak nrepl-debug util
- [ [`f685e`](https://github.com/thheller/shadow-cljs/commit/f685e4160e235d6ce08f7dc5067a38c4431b2c47) ] enable github sponsors?
- [ [`427d0`](https://github.com/thheller/shadow-cljs/commit/427d04cbf3669c062cfe518e8453a8c1dd3cb0df) ] delay loading closure default externs until needed
- [ [`46bbb`](https://github.com/thheller/shadow-cljs/commit/46bbbf008258de2f4a26cbe0047db83c7dbb7d2a) ] better validation of :modules in :browser config
- [ [`2d0af`](https://github.com/thheller/shadow-cljs/commit/2d0af30dd064c28e5c74846af5272a9e24276fb0) ] add example shadow.html/copy-file build hook
- [ [`c80de`](https://github.com/thheller/shadow-cljs/commit/c80de197a4ee2eca74a29c2f9830fcbdd9daeb67) ] decrease CLI port checker frequency

## [2.8.64](https://github.com/thheller/shadow-cljs/compare/4adb112b29ab308c09075aca9b95af492b1372a2...4adb112b29ab308c09075aca9b95af492b1372a2) - 2019-10-11
- [ [`4adb1`](https://github.com/thheller/shadow-cljs/commit/4adb112b29ab308c09075aca9b95af492b1372a2) ] oops, that was only changed in newest closure release

## [2.8.63](https://github.com/thheller/shadow-cljs/compare/eb38468a12d4f384c30a9a9ddeae96d2852ad86e...6bf321fd4bda0fb06288a2331bc23bac0afd1985) - 2019-10-11
- [ [`6bf32`](https://github.com/thheller/shadow-cljs/commit/6bf321fd4bda0fb06288a2331bc23bac0afd1985) ] fix react-native issue using commonjs
- [ [`eb384`](https://github.com/thheller/shadow-cljs/commit/eb38468a12d4f384c30a9a9ddeae96d2852ad86e) ] use getters on JSError

## [2.8.62](https://github.com/thheller/shadow-cljs/compare/649da79bc49cc1204b62b5ba7e6c4a08e97050e6...dd6627d9b33b1ccba85f775ce616488b76badce3) - 2019-10-09
- [ [`dd662`](https://github.com/thheller/shadow-cljs/commit/dd6627d9b33b1ccba85f775ce616488b76badce3) ] tweak externs inference to not warn about goog.*
- [ [`a120b`](https://github.com/thheller/shadow-cljs/commit/a120bc7afe81254d300eadac4c94fa6bb970ac36) ] removed unused :extra from warnings struct
- [ [`649da`](https://github.com/thheller/shadow-cljs/commit/649da79bc49cc1204b62b5ba7e6c4a08e97050e6) ] slightly better error for :refer conflicts

## [2.8.61](https://github.com/thheller/shadow-cljs/compare/d3272ac017bb84738ea973af96263ded3848e937...fdd52537d8787e0fe18fde83e984fd0694653088) - 2019-10-08
- [ [`fdd52`](https://github.com/thheller/shadow-cljs/commit/fdd52537d8787e0fe18fde83e984fd0694653088) ] fix node-repl eval issue
- [ [`0a44b`](https://github.com/thheller/shadow-cljs/commit/0a44be4e2173ba0448f3ecd03c2d9b00a734c774) ] that wasn't supposed to be part of the last commit
- [ [`75cd8`](https://github.com/thheller/shadow-cljs/commit/75cd88c2300794b4b1b9a0d97c764b1cd382d585) ] fix accidental invokable-ns breakage
- [ [`3fbc8`](https://github.com/thheller/shadow-cljs/commit/3fbc830c77bf117f5c2f1070c4bdb2deec746bf4) ] fix NPE in path resolve
- [ [`2f4d8`](https://github.com/thheller/shadow-cljs/commit/2f4d86b37b8f0c3417268faf373b0620051507fe) ] prevent creating circular deps in the REPL
- [ [`373f2`](https://github.com/thheller/shadow-cljs/commit/373f207323cd4618c1635747651e71285b09d116) ] remove some dead code
- [ [`d3272`](https://github.com/thheller/shadow-cljs/commit/d3272ac017bb84738ea973af96263ded3848e937) ] safe some redundant symbol calls

## [2.8.60](https://github.com/thheller/shadow-cljs/compare/55fe8d21346b77a816563cc7ed1d7fad96550061...ba424c578f281b737d1a601a94fb111aa7438abe) - 2019-10-06
- [ [`ba424`](https://github.com/thheller/shadow-cljs/commit/ba424c578f281b737d1a601a94fb111aa7438abe) ] Merge branch 'master' of github.com:thheller/shadow-cljs
- [ [`2ed96`](https://github.com/thheller/shadow-cljs/commit/2ed96831229aa924242334d6b7515946cbb8fd82) ] add clj-async-profiler
- [ [`0411a`](https://github.com/thheller/shadow-cljs/commit/0411aa6032f47adc6666f78ade73339444b70dc6) ] bump shadow-cljsjs
- [ [`efd7d`](https://github.com/thheller/shadow-cljs/commit/efd7d443ce7735405e41994543c37cb25189b367) ] fix cache invalidation for convert-goog sources
- [ [`4aa1a`](https://github.com/thheller/shadow-cljs/commit/4aa1a475742a755c1cd6eb65fe2ee73efb3c6830) ] add try/catch in case transit-str fails
- [ [`98959`](https://github.com/thheller/shadow-cljs/commit/989590bb373e56b1d0ccf3a4029e2d3ec294ffe3) ] add :js-provider :external util
- [ [`a1333`](https://github.com/thheller/shadow-cljs/commit/a1333b9000eedf505783a62fcb65965c8514ebe5) ] more config options for :warnings-as-errors
- [ [`55fe8`](https://github.com/thheller/shadow-cljs/commit/55fe8d21346b77a816563cc7ed1d7fad96550061) ] add :devtools {:log-style "color: red;"}

## [2.8.59](https://github.com/thheller/shadow-cljs/compare/46a87be77ae5fa46caaae409a815127d8632036d...2705af1595a8c424602d6116963238d5ffd10876) - 2019-09-26
- [ [`2705a`](https://github.com/thheller/shadow-cljs/commit/2705af1595a8c424602d6116963238d5ffd10876) ] fix broken :chrome-extension target
- [ [`56389`](https://github.com/thheller/shadow-cljs/commit/56389d94c034ff6aadd54f69f92e44dd980e5edd) ] add quick hack to make CI fail with bad targets
- [ [`46a87`](https://github.com/thheller/shadow-cljs/commit/46a87be77ae5fa46caaae409a815127d8632036d) ] [WIP] more prepl work

## [2.8.58](https://github.com/thheller/shadow-cljs/compare/3c1b5d24e4a1f041feb5b6ec2ab0bb1e9dbbb85f...3c1b5d24e4a1f041feb5b6ec2ab0bb1e9dbbb85f) - 2019-09-24
- [ [`3c1b5`](https://github.com/thheller/shadow-cljs/commit/3c1b5d24e4a1f041feb5b6ec2ab0bb1e9dbbb85f) ] add :warnings-as-errors compiler options

## [2.8.57](https://github.com/thheller/shadow-cljs/compare/e63b4d4383a16c0e15dfad9ee4d858d303ffb30e...fda9f8f02892342f07bffd26ab81fdcc79fadfa4) - 2019-09-24
- [ [`fda9f`](https://github.com/thheller/shadow-cljs/commit/fda9f8f02892342f07bffd26ab81fdcc79fadfa4) ] dummy goog.module code for experiments
- [ [`e63b4`](https://github.com/thheller/shadow-cljs/commit/e63b4d4383a16c0e15dfad9ee4d858d303ffb30e) ] let :build-hooks throw, don't just log exceptions

## [2.8.56](https://github.com/thheller/shadow-cljs/compare/ff2f7dcbf56fe96439bbaaa9d7f4b28e36b10d49...c75fa3376efdbc8405e5f978db1db5f748861c55) - 2019-09-23
- [ [`c75fa`](https://github.com/thheller/shadow-cljs/commit/c75fa3376efdbc8405e5f978db1db5f748861c55) ] change :npm-module dev output
- [ [`ff2f7`](https://github.com/thheller/shadow-cljs/commit/ff2f7dcbf56fe96439bbaaa9d7f4b28e36b10d49) ] update expo keep awake for Expo 33+ (#565)

## [2.8.55](https://github.com/thheller/shadow-cljs/compare/92354ca376470c1acd23d36d8b0a6fe035462388...92354ca376470c1acd23d36d8b0a6fe035462388) - 2019-09-20
- [ [`92354`](https://github.com/thheller/shadow-cljs/commit/92354ca376470c1acd23d36d8b0a6fe035462388) ] cider is looking for shadow.repl

## [2.8.54](https://github.com/thheller/shadow-cljs/compare/5d76b2ceb0ac2c42a2298c7cd991f8d3a4ff28dc...5d76b2ceb0ac2c42a2298c7cd991f8d3a4ff28dc) - 2019-09-19
- [ [`5d76b`](https://github.com/thheller/shadow-cljs/commit/5d76b2ceb0ac2c42a2298c7cd991f8d3a4ff28dc) ] cache last-modified lookups for jar files

## [2.8.53](https://github.com/thheller/shadow-cljs/compare/c0a0d611e20cb34bce4fafa01bc586b560865795...356289b3ae44753099d9cbe5f79765af5cdf69e2) - 2019-09-19
- [ [`35628`](https://github.com/thheller/shadow-cljs/commit/356289b3ae44753099d9cbe5f79765af5cdf69e2) ] expand try/catch block in caching code
- [ [`92bb8`](https://github.com/thheller/shadow-cljs/commit/92bb8076f6d1774bdbcf32dc8b0e14ce949c769a) ] rewrite nrepl support, remove piggieback emulation
- [ [`f711e`](https://github.com/thheller/shadow-cljs/commit/f711e781f12ca4bd85435e9e3eda0cfc5887a07b) ] add nrepl debug proxy util
- [ [`d9d00`](https://github.com/thheller/shadow-cljs/commit/d9d0002c30eaf725051a0483b248696eeb731bc8) ] add `shadow-cljs classpath` to print classpath
- [ [`60e09`](https://github.com/thheller/shadow-cljs/commit/60e0966f1ec087eb6aba06b1fde9e93f9a10ab46) ] Install transitive dependencies with an exact version (#555)
- [ [`dae59`](https://github.com/thheller/shadow-cljs/commit/dae59acfd605dc230346ebd093785833f36c3080) ] WIP prepl beginnings ...
- [ [`61af1`](https://github.com/thheller/shadow-cljs/commit/61af1cce91398c77f941a3b057cbb840b384eaf6) ] some internal REPL cleanup, remove old unused code
- [ [`9269c`](https://github.com/thheller/shadow-cljs/commit/9269cb36b0dfd51eab03957b3245f99ee2eb0b42) ] cleanup REPL heartbeat, expose extra API fns
- [ [`c0a0d`](https://github.com/thheller/shadow-cljs/commit/c0a0d611e20cb34bce4fafa01bc586b560865795) ] make :loade-mode :eval the default for :browser

## [2.8.52](https://github.com/thheller/shadow-cljs/compare/7c54996a0f67ffad112d5ddd6cc913136c1fd5cd...92fd1ba37772d572c70b2df8b43a2d695b635d7b) - 2019-08-27
- [ [`92fd1`](https://github.com/thheller/shadow-cljs/commit/92fd1ba37772d572c70b2df8b43a2d695b635d7b) ] remove log spam when CLJ macro files were deleted
- [ [`fd9c6`](https://github.com/thheller/shadow-cljs/commit/fd9c6756f30d32b3720617e32a53c14106668157) ] slightly improve rn ws connect error
- [ [`511bb`](https://github.com/thheller/shadow-cljs/commit/511bbe6b7597c280b0041f2caa3eea9094eb1333) ] browser-repl should start fresh when browser was closed
- [ [`42886`](https://github.com/thheller/shadow-cljs/commit/42886120f095725d7cc62fd2a31b9cd738cb3c35) ] bump closure compiler
- [ [`804da`](https://github.com/thheller/shadow-cljs/commit/804dabed7d0c922795a34e22422cc4a22f7dedad) ] add :form-size-threshold as debug utility
- [ [`7c549`](https://github.com/thheller/shadow-cljs/commit/7c54996a0f67ffad112d5ddd6cc913136c1fd5cd) ] update build config to use :js-package-dirs

## [2.8.51](https://github.com/thheller/shadow-cljs/compare/14546f3e879a7ec249d57062b9d7816d3aaec304...6bc2e92136c7ed66541aab82ec7ba2002fb81aa5) - 2019-08-19
- [ [`6bc2e`](https://github.com/thheller/shadow-cljs/commit/6bc2e92136c7ed66541aab82ec7ba2002fb81aa5) ] allow global :js-package-dirs
- [ [`c3cfb`](https://github.com/thheller/shadow-cljs/commit/c3cfb17ead694670056b03d49a24562981b4cf7c) ] introduce :js-package-dirs
- [ [`34249`](https://github.com/thheller/shadow-cljs/commit/3424955f7b7992bd468025c9dba2b9506accfcdc) ] expose :ns-aliases as config option
- [ [`14546`](https://github.com/thheller/shadow-cljs/commit/14546f3e879a7ec249d57062b9d7816d3aaec304) ] bump some deps

## [2.8.50](https://github.com/thheller/shadow-cljs/compare/34cb0f390426350009079cf04e49a1cc78cfb4f0...34cb0f390426350009079cf04e49a1cc78cfb4f0) - 2019-08-17
- [ [`34cb0`](https://github.com/thheller/shadow-cljs/commit/34cb0f390426350009079cf04e49a1cc78cfb4f0) ] drop more old piggieback references

## [2.8.49](https://github.com/thheller/shadow-cljs/compare/dfc8021bbad39272d7ddca89b0bde826e50b26ff...9518406cc9e6c04f2ff54c1f31f6cbeb0ec9ed97) - 2019-08-17
- [ [`95184`](https://github.com/thheller/shadow-cljs/commit/9518406cc9e6c04f2ff54c1f31f6cbeb0ec9ed97) ] add back missing fake piggieback descriptor
- [ [`dfc80`](https://github.com/thheller/shadow-cljs/commit/dfc8021bbad39272d7ddca89b0bde826e50b26ff) ] drop leftover nrepl init call

## [2.8.48](https://github.com/thheller/shadow-cljs/compare/a4fc198a2296fdc53590b28d7afaefec03909e0e...96e7929fdf5467c21a489fcc7d7117bff4c8b45a) - 2019-08-15
- [ [`96e79`](https://github.com/thheller/shadow-cljs/commit/96e7929fdf5467c21a489fcc7d7117bff4c8b45a) ] CLI parsing should stop at clj-run/run
- [ [`a4fc1`](https://github.com/thheller/shadow-cljs/commit/a4fc198a2296fdc53590b28d7afaefec03909e0e) ] nrepl cleanup, proper repl result handling

## [2.8.47](https://github.com/thheller/shadow-cljs/compare/38d336621ecae9df5da81f9c7fc20cf40374b6d8...1aa5f56dd2f5ebe8ea4acb448b9aa0f3fd84a728) - 2019-08-14
- [ [`1aa5f`](https://github.com/thheller/shadow-cljs/commit/1aa5f56dd2f5ebe8ea4acb448b9aa0f3fd84a728) ] actually invalidate macros properly
- [ [`5ed54`](https://github.com/thheller/shadow-cljs/commit/5ed54ca577cb2944896c0c084d9ceeb714001e43) ] Merge branch 'master' of github.com:thheller/shadow-cljs
- [ [`44301`](https://github.com/thheller/shadow-cljs/commit/44301a4b28c78668b116547261146648148efd49) ] Update README.md (#543)
- [ [`34d8b`](https://github.com/thheller/shadow-cljs/commit/34d8b2d9b4c4b6709e5efb8c599fffdcd59a6e77) ] bump shadow-cljsjs
- [ [`38d33`](https://github.com/thheller/shadow-cljs/commit/38d336621ecae9df5da81f9c7fc20cf40374b6d8) ] fix REPL result buffering so it actually buffers

## [2.8.46](https://github.com/thheller/shadow-cljs/compare/810a3f6831a48178f20d855ee315c6a2b1ea4e54...60195d79d096b3843bcea1d06918ad5e0837fc2b) - 2019-08-12
- [ [`60195`](https://github.com/thheller/shadow-cljs/commit/60195d79d096b3843bcea1d06918ad5e0837fc2b) ] properly handle warnings in nrepl load-file as well
- [ [`fd736`](https://github.com/thheller/shadow-cljs/commit/fd7360dd334119283242eb85340ed04aa16310ac) ] make js/global accessible in react-native dev builds
- [ [`1d3ad`](https://github.com/thheller/shadow-cljs/commit/1d3ad7ce422fde4d595868b0e9ed8ade94ee175e) ] fix REPL issues where output was misdirected
- [ [`fd568`](https://github.com/thheller/shadow-cljs/commit/fd568f0f76bee83c760601da9e0b01c4b4501474) ] Explicitly specify HUD text color to avoid it being overwritten (#541)
- [ [`810a3`](https://github.com/thheller/shadow-cljs/commit/810a3f6831a48178f20d855ee315c6a2b1ea4e54) ] print warnings from REPL require, load-file

## [2.8.45](https://github.com/thheller/shadow-cljs/compare/678e5ad12ccd94394868d8cee6d4f9259c00b3f7...8e519f23446ca091bf42e17fd0c6ef6bd43b4454) - 2019-08-07
- [ [`8e519`](https://github.com/thheller/shadow-cljs/commit/8e519f23446ca091bf42e17fd0c6ef6bd43b4454) ] fix incorrect analyze parameter
- [ [`19e1a`](https://github.com/thheller/shadow-cljs/commit/19e1a8d89e455908f3e465a88d7c4522503e5400) ] update deps.edn
- [ [`a21c3`](https://github.com/thheller/shadow-cljs/commit/a21c3fcc42c48aef1d3734fe4c88936d51775e43) ] improve macro reloading in watch
- [ [`678e5`](https://github.com/thheller/shadow-cljs/commit/678e5ad12ccd94394868d8cee6d4f9259c00b3f7) ] add support :skip-goog-provide in ns meta

## [2.8.44](https://github.com/thheller/shadow-cljs/compare/189194dff69028332e22af328703b25e8cfeeb94...189194dff69028332e22af328703b25e8cfeeb94) - 2019-08-06
- [ [`18919`](https://github.com/thheller/shadow-cljs/commit/189194dff69028332e22af328703b25e8cfeeb94) ] fix :target :react-native using bad ns alias

## [2.8.43](https://github.com/thheller/shadow-cljs/compare/5128e2464792fadc27fd08a657e5370cd4498d54...b63ad9c5397872fdfe14893e4fdc0d14c670442d) - 2019-08-06
- [ [`b63ad`](https://github.com/thheller/shadow-cljs/commit/b63ad9c5397872fdfe14893e4fdc0d14c670442d) ] fix config using wrong var
- [ [`5bbf1`](https://github.com/thheller/shadow-cljs/commit/5bbf1e1b835481dff29c0cabf71900bfd2c88e37) ] fix rn code-split not working in dev builds
- [ [`3e2cc`](https://github.com/thheller/shadow-cljs/commit/3e2cc6c566de8f38f2b9d2731ef6884d72670b6b) ] fix flush issue in :npm-module builds
- [ [`c3198`](https://github.com/thheller/shadow-cljs/commit/c319854f353a1cfa11a0ef21f1e78d4dea43066a) ] basic react-native code-splitting support
- [ [`1470c`](https://github.com/thheller/shadow-cljs/commit/1470c6d2d06195d01ba2b486ef6f730c36b51840) ] fix REPL issue (race condition in ns, scope issue)
- [ [`d9d62`](https://github.com/thheller/shadow-cljs/commit/d9d62fb3a95b724f021fedc948d6c737c3542153) ] fix repl ns not loading JS deps correctly
- [ [`433e6`](https://github.com/thheller/shadow-cljs/commit/433e6d16b3179254cb0d52fd46c7b48633571c91) ] tweaks for react-native builds, require support
- [ [`f3066`](https://github.com/thheller/shadow-cljs/commit/f30668c23eda356649fb0015c2992ee44038cf85) ] change how :react-native dev builds load code
- [ [`5128e`](https://github.com/thheller/shadow-cljs/commit/5128e2464792fadc27fd08a657e5370cd4498d54) ] fix console.log issue for older chrome versions

## [2.8.42](https://github.com/thheller/shadow-cljs/compare/cb1e053816e4264c55f91257354798e92127f51b...d1fbab36d8931a8989bea8767f08642040ed1a04) - 2019-07-30
- [ [`d1fba`](https://github.com/thheller/shadow-cljs/commit/d1fbab36d8931a8989bea8767f08642040ed1a04) ] fix argument quoting for windows powershell
- [ [`71a71`](https://github.com/thheller/shadow-cljs/commit/71a71cc292c0e26a36314d5de806c2f460ced13a) ] clear a bit of package.json data before including
- [ [`66fa7`](https://github.com/thheller/shadow-cljs/commit/66fa7d42827008e7f7362d4a70b719f7ece20e3b) ] fix REPL issue with ns defined at the REPL
- [ [`cb1e0`](https://github.com/thheller/shadow-cljs/commit/cb1e053816e4264c55f91257354798e92127f51b) ] Added karaoke example to README.md (#531)

## [2.8.41](https://github.com/thheller/shadow-cljs/compare/cad286ac7f1a33c0bcb7e26f9ff3b2c20242135f...01988f44b6dd3ec0fec2b0148379841181605a55) - 2019-07-21
- [ [`01988`](https://github.com/thheller/shadow-cljs/commit/01988f44b6dd3ec0fec2b0148379841181605a55) ] add some more config specs
- [ [`b2124`](https://github.com/thheller/shadow-cljs/commit/b212408baf108c2557ffa9f3caa47379dcea5d92) ] make autoload the default
- [ [`2d348`](https://github.com/thheller/shadow-cljs/commit/2d34803ac3847e2940157eb5eb4b5c13633bf1ea) ] get rid of all electron launcher related code
- [ [`4c973`](https://github.com/thheller/shadow-cljs/commit/4c973a3ac8a4299e040dd0428bd07c58050795fe) ] make .nrepl-port more reliable
- [ [`6f3c3`](https://github.com/thheller/shadow-cljs/commit/6f3c34e1b1f75a409a968614e74a351d25e478f1) ] fix require in node-script REPL
- [ [`121f1`](https://github.com/thheller/shadow-cljs/commit/121f14e567807ed8ec903c4a73eb7d19d9ef326f) ] fix race condition in REPL code
- [ [`cad28`](https://github.com/thheller/shadow-cljs/commit/cad286ac7f1a33c0bcb7e26f9ff3b2c20242135f) ] remove outdated comment

## [2.8.40](https://github.com/thheller/shadow-cljs/compare/6b849739e3b61ecf9b74581f3b98688438ecdff5...1826542c63e0b1571162013590aa0b2597b86112) - 2019-07-06
- [ [`18265`](https://github.com/thheller/shadow-cljs/commit/1826542c63e0b1571162013590aa0b2597b86112) ] use proper thread-bound? check for nrepl vars
- [ [`b5526`](https://github.com/thheller/shadow-cljs/commit/b552603679a2c2874a85b01e28b238dbcd34f2ab) ] get rid of overeager GH security alert
- [ [`ca70c`](https://github.com/thheller/shadow-cljs/commit/ca70c522a8a914de3b2fad50648631ab10bddbf9) ] safer detection logic
- [ [`631ee`](https://github.com/thheller/shadow-cljs/commit/631eec6853bda2b97b61000fb1223bf59a800676) ] optimize process polyfill detection
- [ [`f2b93`](https://github.com/thheller/shadow-cljs/commit/f2b93790c88a7bedac343d508ea71cedcb5bcae8) ] oops, actually fix it
- [ [`18af5`](https://github.com/thheller/shadow-cljs/commit/18af598b8def30a3c3e9532c153e0071805f9f68) ] fix CI test
- [ [`fd7ba`](https://github.com/thheller/shadow-cljs/commit/fd7ba399f3be117fb4206dda17b1c28dacd8687e) ] bump closure-compiler
- [ [`89acb`](https://github.com/thheller/shadow-cljs/commit/89acb664f800af48344288e963873bf8972ee9fb) ] Change default deps.edn inject behaviour!
- [ [`dc28b`](https://github.com/thheller/shadow-cljs/commit/dc28b782c948c4413d0b064bf4f72a13be64d02e) ] invoke :deps clojure via powershell on windows
- [ [`573e6`](https://github.com/thheller/shadow-cljs/commit/573e632260c3d5de40b37abb537eb8ce540a0ec4) ] bump deps
- [ [`d462e`](https://github.com/thheller/shadow-cljs/commit/d462ecae36650dc46c038e48928df658cd30a5a7) ] improve error for constant move pass
- [ [`3f98c`](https://github.com/thheller/shadow-cljs/commit/3f98c42c69e239952f5c68824664c30c0b638f47) ] Allows bootstrapping to work in web-workers (#506)
- [ [`6b849`](https://github.com/thheller/shadow-cljs/commit/6b849739e3b61ecf9b74581f3b98688438ecdff5) ] fix github config

## [2.8.39 - fix bad .38 release](https://github.com/thheller/shadow-cljs/compare/...) - 2019-06-05

## [2.8.38](https://github.com/thheller/shadow-cljs/compare/d93af50a6b5e54553762ab273102a3cd16139b6d...6254cd24fd02248b83ff9a24634eedfb6a2c08ba) - 2019-06-05
- [ [`6254c`](https://github.com/thheller/shadow-cljs/commit/6254cd24fd02248b83ff9a24634eedfb6a2c08ba) ] make CI happy
- [ [`9d8b5`](https://github.com/thheller/shadow-cljs/commit/9d8b5f50cc00d4c7f004e5c7b92614e739c43136) ] change how node implicit globals are handled
- [ [`d93af`](https://github.com/thheller/shadow-cljs/commit/d93af50a6b5e54553762ab273102a3cd16139b6d) ] Create FUNDING.yml

## [2.8.37](https://github.com/thheller/shadow-cljs/compare/4839d3d5a77ab633a0f3a7f22adadc47ae25f92a...87f5e47258f6fe317fb3eebe93fca775c09f9d3c) - 2019-05-16
- [ [`87f5e`](https://github.com/thheller/shadow-cljs/commit/87f5e47258f6fe317fb3eebe93fca775c09f9d3c) ] bump shadow-cljsjs
- [ [`4839d`](https://github.com/thheller/shadow-cljs/commit/4839d3d5a77ab633a0f3a7f22adadc47ae25f92a) ] add support for :preloads in :react-native

## [2.8.36](https://github.com/thheller/shadow-cljs/compare/4f1d74cd8088dc457d2b0ec92aac0526ee1a8805...bddb3cc01735c2b9a83d041d430604f51010d2e4) - 2019-05-01
- [ [`bddb3`](https://github.com/thheller/shadow-cljs/commit/bddb3cc01735c2b9a83d041d430604f51010d2e4) ] fix :karma target not picking up new/removed tests
- [ [`14554`](https://github.com/thheller/shadow-cljs/commit/14554750df2b59fa63f377ebf3e34bd1105d7065) ] update dev-http example in readme
- [ [`4f1d7`](https://github.com/thheller/shadow-cljs/commit/4f1d74cd8088dc457d2b0ec92aac0526ee1a8805) ] bump shadow-cljsjs

## [2.8.35](https://github.com/thheller/shadow-cljs/compare/15e2cef45b695e53596d6e23ece4df308736bb38...15e2cef45b695e53596d6e23ece4df308736bb38) - 2019-04-26
- [ [`15e2c`](https://github.com/thheller/shadow-cljs/commit/15e2cef45b695e53596d6e23ece4df308736bb38) ] normalize :node-module-dir to support relative paths properly

## [2.8.34](https://github.com/thheller/shadow-cljs/compare/a724e6e4fbb40f4d0db81c4a53b0f95ce71fae4b...a724e6e4fbb40f4d0db81c4a53b0f95ce71fae4b) - 2019-04-26
- [ [`a724e`](https://github.com/thheller/shadow-cljs/commit/a724e6e4fbb40f4d0db81c4a53b0f95ce71fae4b) ] ensure we don't pass nil as babel-preset-config

## [2.8.33](https://github.com/thheller/shadow-cljs/compare/de863939d75f6dc6040abc393ea6a53512a965e0...de863939d75f6dc6040abc393ea6a53512a965e0) - 2019-04-22
- [ [`de863`](https://github.com/thheller/shadow-cljs/commit/de863939d75f6dc6040abc393ea6a53512a965e0) ] revert :fn-invoke-direct default due to problems in reagent

## [2.8.32](https://github.com/thheller/shadow-cljs/compare/91881d967924883f320b943492dc50428ea1f71e...2a013d952c9c7d562c937e79d7da85f35ffd4d9e) - 2019-04-20
- [ [`2a013`](https://github.com/thheller/shadow-cljs/commit/2a013d952c9c7d562c937e79d7da85f35ffd4d9e) ] allow specifying maven config in ~/.shadow-cljs/config.edn
- [ [`5dda4`](https://github.com/thheller/shadow-cljs/commit/5dda42a353b48ec1f0014bcba2e4986c06351ace) ] accept :chunks as an alias for :modules
- [ [`36930`](https://github.com/thheller/shadow-cljs/commit/369300e431be278054fe71601c6309d640bb3057) ] fix missing ui updates (compile, release didn't show progress)
- [ [`964dd`](https://github.com/thheller/shadow-cljs/commit/964ddfa2411885b4ad5e7932671fa1511a8d964f) ] forget moved/deleted macros, don't try to reload them
- [ [`5a440`](https://github.com/thheller/shadow-cljs/commit/5a440c4deba795e62cf0889d0ed2c5a71ee5f4d9) ] avoid trying to package ws in one-file node bundle dev builds
- [ [`0d72e`](https://github.com/thheller/shadow-cljs/commit/0d72e0edb0d8583b965e2ae6042a5aee72190448) ] use :fn-invoke-direct by default
- [ [`37a05`](https://github.com/thheller/shadow-cljs/commit/37a05342f9f247f3490a7dd8e13a92290116a4e2) ] use project folder name in web UI title
- [ [`91881`](https://github.com/thheller/shadow-cljs/commit/91881d967924883f320b943492dc50428ea1f71e) ] specify utf-8 to prevent SyntaxError (#480)

## [2.8.31](https://github.com/thheller/shadow-cljs/compare/320a4f19c8248c07dd655c780946ffc3efb88cd6...320a4f19c8248c07dd655c780946ffc3efb88cd6) - 2019-04-11
- [ [`320a4`](https://github.com/thheller/shadow-cljs/commit/320a4f19c8248c07dd655c780946ffc3efb88cd6) ] add notifications to web ui

## [2.8.30](https://github.com/thheller/shadow-cljs/compare/aa7bc3c6f9c28a93f26f5f6279bf7eb512013d2c...aa7bc3c6f9c28a93f26f5f6279bf7eb512013d2c) - 2019-04-11
- [ [`aa7bc`](https://github.com/thheller/shadow-cljs/commit/aa7bc3c6f9c28a93f26f5f6279bf7eb512013d2c) ] fix broken goog.LOCALE issue for :npm-module

## [2.8.29](https://github.com/thheller/shadow-cljs/compare/7bf19e30ae34929d971569de75b000cf281bb81d...7f456ac454585985e5bb6b573745d79090cfcabe) - 2019-04-08
- [ [`7f456`](https://github.com/thheller/shadow-cljs/commit/7f456ac454585985e5bb6b573745d79090cfcabe) ] fix broken hashbang prepend for :node-script target
- [ [`7bf19`](https://github.com/thheller/shadow-cljs/commit/7bf19e30ae34929d971569de75b000cf281bb81d) ] fix classpath js handling for commonjs files

## [2.8.28](https://github.com/thheller/shadow-cljs/compare/0461774d47fee569596288f77296e4a27d186764...0461774d47fee569596288f77296e4a27d186764) - 2019-04-07
- [ [`04617`](https://github.com/thheller/shadow-cljs/commit/0461774d47fee569596288f77296e4a27d186764) ] properly apply :closure-output-charset in all cases

## [2.8.27](https://github.com/thheller/shadow-cljs/compare/be0f1ee817a5b8ce2d67b27506ccae19aad36a31...d2a7233f1522ae2817dd41ece85c1ec6f94e9e72) - 2019-04-07
- [ [`d2a72`](https://github.com/thheller/shadow-cljs/commit/d2a7233f1522ae2817dd41ece85c1ec6f94e9e72) ] fix closure issue with incremental classpath-js compiles
- [ [`555be`](https://github.com/thheller/shadow-cljs/commit/555befb961f204849524011eeafba1ab665c538a) ] add configurable babel rewriting using babel preset-env
- [ [`e3c12`](https://github.com/thheller/shadow-cljs/commit/e3c12ab87ced5431ab7a49a31e40327899742dd6) ] [WIP] restructure internal build-log handling
- [ [`457e8`](https://github.com/thheller/shadow-cljs/commit/457e805ded950db97a71700f4a4f9d914d473742) ] remove useless error log
- [ [`09791`](https://github.com/thheller/shadow-cljs/commit/097919d210a7631cb83312f24ff9e3f73f9932ef) ] babel tweaks
- [ [`78059`](https://github.com/thheller/shadow-cljs/commit/780598fd5f3e958cc6fa4a4517bd3eba632e38d3) ] missed package.json in previous commit
- [ [`05ed0`](https://github.com/thheller/shadow-cljs/commit/05ed061e8aa34464eae674e5b5bb3fca22f8fa7b) ] update babel interop to use latest babel
- [ [`914cb`](https://github.com/thheller/shadow-cljs/commit/914cbf1ffe256a8bff13b96f34cb6879d504518e) ] adjust REPL in-ns behavior to match CLJS default
- [ [`af729`](https://github.com/thheller/shadow-cljs/commit/af729484365dbab70bb8bc741ba9fbfad2aa7669) ] remove unused JS option (undocument, and not working anyways)
- [ [`be0f1`](https://github.com/thheller/shadow-cljs/commit/be0f1ee817a5b8ce2d67b27506ccae19aad36a31) ] reduce the babel interop to only rewriting import -> require

## [2.8.26](https://github.com/thheller/shadow-cljs/compare/bc0f1885afce028ee01d93903c674e4a52bc6fb2...fd3288fc3f36b9130e1af61d302f371667cadfa7) - 2019-04-02
- [ [`fd328`](https://github.com/thheller/shadow-cljs/commit/fd3288fc3f36b9130e1af61d302f371667cadfa7) ] fix REPL issue with (ns foo.bar)
- [ [`66885`](https://github.com/thheller/shadow-cljs/commit/66885e5bba705fc1c591d9c070ae958ab12f42c2) ] remove unused java class causing issues with GCC interface change
- [ [`37299`](https://github.com/thheller/shadow-cljs/commit/3729944e2254b2223a1068fbdabeca49c93ae67b) ] bump closure-compiler
- [ [`bc0f1`](https://github.com/thheller/shadow-cljs/commit/bc0f1885afce028ee01d93903c674e4a52bc6fb2) ] Add shadow-cljs-kitchen-async-puppeteer example (#467)

## [2.8.25](https://github.com/thheller/shadow-cljs/compare/05fe1a19daba81f2fd84fe51899529edf1d9a6df...05fe1a19daba81f2fd84fe51899529edf1d9a6df) - 2019-03-27
- [ [`05fe1`](https://github.com/thheller/shadow-cljs/commit/05fe1a19daba81f2fd84fe51899529edf1d9a6df) ] fix require issue with nested package.json files

## [2.8.24](https://github.com/thheller/shadow-cljs/compare/8c4fd3cd14108f5e08f36c18ec17114cca821368...8c4fd3cd14108f5e08f36c18ec17114cca821368) - 2019-03-26
- [ [`8c4fd`](https://github.com/thheller/shadow-cljs/commit/8c4fd3cd14108f5e08f36c18ec17114cca821368) ] fix broken goog.define for goog.LOCALE

## [2.8.23](https://github.com/thheller/shadow-cljs/compare/10a51192ae1b1ca30011ad54ea29adab01d6ee46...10a51192ae1b1ca30011ad54ea29adab01d6ee46) - 2019-03-25
- [ [`10a51`](https://github.com/thheller/shadow-cljs/commit/10a51192ae1b1ca30011ad54ea29adab01d6ee46) ] fix missing sourcesContent in goog sources

## [2.8.22](https://github.com/thheller/shadow-cljs/compare/11436e09f692cb8593f6aa537f3be1d20da1e3e5...a6a0201c615abc3adbf8d39e359fa90e78bae789) - 2019-03-24
- [ [`a6a02`](https://github.com/thheller/shadow-cljs/commit/a6a0201c615abc3adbf8d39e359fa90e78bae789) ] work around compiler munging default to default$
- [ [`11436`](https://github.com/thheller/shadow-cljs/commit/11436e09f692cb8593f6aa537f3be1d20da1e3e5) ] tweak readme

## [2.8.21](https://github.com/thheller/shadow-cljs/compare/b0cb03d13cb8d96fa137153955eeb37b0142d468...b0cb03d13cb8d96fa137153955eeb37b0142d468) - 2019-03-20
- [ [`b0cb0`](https://github.com/thheller/shadow-cljs/commit/b0cb03d13cb8d96fa137153955eeb37b0142d468) ] handle :middelware properly when pointing to seq of middlewares

## [2.8.20](https://github.com/thheller/shadow-cljs/compare/d47233a0fa9aa66b4a970efd5c4037c358fee776...d47233a0fa9aa66b4a970efd5c4037c358fee776) - 2019-03-20
- [ [`d4723`](https://github.com/thheller/shadow-cljs/commit/d47233a0fa9aa66b4a970efd5c4037c358fee776) ] add support for newer nREPL config files

## [2.8.19](https://github.com/thheller/shadow-cljs/compare/553ccec84e648af65ad146c6a39728ded0ddc2e3...db6d630960bedfaf83d0ac01dde4a934ddfbb4cb) - 2019-03-19
- [ [`db6d6`](https://github.com/thheller/shadow-cljs/commit/db6d630960bedfaf83d0ac01dde4a934ddfbb4cb) ] optimize bootstrap target to avoid unnecessary writes
- [ [`bae97`](https://github.com/thheller/shadow-cljs/commit/bae971396886df7cdd728554b6d1eef401fb1a6e) ] make bootstrap target properly recompile macros on change
- [ [`553cc`](https://github.com/thheller/shadow-cljs/commit/553ccec84e648af65ad146c6a39728ded0ddc2e3) ] Small typo fixed (#465)

## [2.8.18](https://github.com/thheller/shadow-cljs/compare/774b4987ff19c5d8465bc1bf651bca22105756bb...774b4987ff19c5d8465bc1bf651bca22105756bb) - 2019-03-15
- [ [`774b4`](https://github.com/thheller/shadow-cljs/commit/774b4987ff19c5d8465bc1bf651bca22105756bb) ] fix ^:dev/always ns meta not propagating to the client properly

## [2.8.17](https://github.com/thheller/shadow-cljs/compare/3e3563ff93f5dd7b82094f6299ed71e035a31aaa...f9cb67d75739c21f0e263ac30873cd2192a91b62) - 2019-03-15
- [ [`f9cb6`](https://github.com/thheller/shadow-cljs/commit/f9cb67d75739c21f0e263ac30873cd2192a91b62) ] fix broken --config-merge CLI
- [ [`3e356`](https://github.com/thheller/shadow-cljs/commit/3e3563ff93f5dd7b82094f6299ed71e035a31aaa) ] add utf-8 charset to generated browser-test page

## [2.8.16](https://github.com/thheller/shadow-cljs/compare/cf752df3fa6a8ad9c950c0c114d8c6ddfa220987...09ac39346b333d46216d07611938a405ef29bc33) - 2019-03-14
- [ [`09ac3`](https://github.com/thheller/shadow-cljs/commit/09ac39346b333d46216d07611938a405ef29bc33) ] better errors when config isn't formatted properly
- [ [`56b39`](https://github.com/thheller/shadow-cljs/commit/56b39bf17f48f59e80ab6258f6cd7eb0a05f102c) ] ensure that :append-js/:prepend-js are positioned properly
- [ [`00d0f`](https://github.com/thheller/shadow-cljs/commit/00d0fe56f2dbfd9e35639480df279c5a7b3c257a) ] add some typehints to avoid reflection misses in clj 1.10
- [ [`cf752`](https://github.com/thheller/shadow-cljs/commit/cf752df3fa6a8ad9c950c0c114d8c6ddfa220987) ] fix test count for karma (#459)

## [2.8.15](https://github.com/thheller/shadow-cljs/compare/d6f53762397610c00f465c9bbb0e6adc6d5d6287...815ce0dc8caab05fedbec5d182b92a1c994b8308) - 2019-03-07
- [ [`815ce`](https://github.com/thheller/shadow-cljs/commit/815ce0dc8caab05fedbec5d182b92a1c994b8308) ] fix JS import issue in :npm-module based builds
- [ [`e6973`](https://github.com/thheller/shadow-cljs/commit/e6973b7eaaa7331b1ab946c3e5d896f2e90c19fa) ] downgrade closure compiler for now
- [ [`d6f53`](https://github.com/thheller/shadow-cljs/commit/d6f53762397610c00f465c9bbb0e6adc6d5d6287) ] bump deps

## [2.8.14](https://github.com/thheller/shadow-cljs/compare/1a949a94d94c75d086c23f9320fcba1e127abc96...64d2c9842329c8f1e72506b12188f0514740ddcd) - 2019-03-03
- [ [`64d2c`](https://github.com/thheller/shadow-cljs/commit/64d2c9842329c8f1e72506b12188f0514740ddcd) ] ensure dev mode load info is properly set when using shadow.loader
- [ [`1a949`](https://github.com/thheller/shadow-cljs/commit/1a949a94d94c75d086c23f9320fcba1e127abc96) ] set :language-out default once again

## [2.8.13](https://github.com/thheller/shadow-cljs/compare/f5bb2e8428c330dfdab271e0ee5204b47c944dde...f5bb2e8428c330dfdab271e0ee5204b47c944dde) - 2019-03-02
- [ [`f5bb2`](https://github.com/thheller/shadow-cljs/commit/f5bb2e8428c330dfdab271e0ee5204b47c944dde) ] fix windows/linux file watcher problem with new directories

## [2.8.12](https://github.com/thheller/shadow-cljs/compare/3623915d52a9769a5cb40ad8f9ae3d0995fe4ee6...795eb1ed67d053361c1db7438d42344ae9e69b0e) - 2019-03-01
- [ [`795eb`](https://github.com/thheller/shadow-cljs/commit/795eb1ed67d053361c1db7438d42344ae9e69b0e) ] make it possible to disable hawk fs-watch on macOS
- [ [`36239`](https://github.com/thheller/shadow-cljs/commit/3623915d52a9769a5cb40ad8f9ae3d0995fe4ee6) ] shorten error message when dev-http port is taken

## [2.8.11](https://github.com/thheller/shadow-cljs/compare/13e31f2ddba1073cb32da92ee71ae5461c178a84...1cfd21a20556d7b2b6f7d48e0fa84a903858bca8) - 2019-02-27
- [ [`1cfd2`](https://github.com/thheller/shadow-cljs/commit/1cfd21a20556d7b2b6f7d48e0fa84a903858bca8) ] properly assign goog.global in react-native builds
- [ [`13e31`](https://github.com/thheller/shadow-cljs/commit/13e31f2ddba1073cb32da92ee71ae5461c178a84) ] fix goog.module loading errors in react-native dev builds

## [2.8.10](https://github.com/thheller/shadow-cljs/compare/8332a74780b1565a75de560ccb7715eb8e0ed4c3...8332a74780b1565a75de560ccb7715eb8e0ed4c3) - 2019-02-24
- [ [`8332a`](https://github.com/thheller/shadow-cljs/commit/8332a74780b1565a75de560ccb7715eb8e0ed4c3) ] add proper support for :output-feature-set

## [2.8.9](https://github.com/thheller/shadow-cljs/compare/f370b3015aa8346e958b878493aa5e30e961d90c...21bc80f36598de6aaad0e12059991c1f8095ec9e) - 2019-02-24
- [ [`21bc8`](https://github.com/thheller/shadow-cljs/commit/21bc80f36598de6aaad0e12059991c1f8095ec9e) ] Merge branch 'master' of github.com:thheller/shadow-cljs
- [ [`b7d99`](https://github.com/thheller/shadow-cljs/commit/b7d99ff27fe250f701569a0314a6b21fb662ead2) ] fix #455, missing dev time js polyfill for workers (#446)
- [ [`b7724`](https://github.com/thheller/shadow-cljs/commit/b772434d3abe7ac18a95352ed31b5ec223a2844c) ] use shadow.lazy in UI
- [ [`2be4f`](https://github.com/thheller/shadow-cljs/commit/2be4fd1b71bec9e82a2e0cd15f8e5632b34a1f5e) ] tweak shadow.loader to support shadow.lazy properly
- [ [`f370b`](https://github.com/thheller/shadow-cljs/commit/f370b3015aa8346e958b878493aa5e30e961d90c) ] [WIP] add experimental shadow.lazy API

## [2.8.8](https://github.com/thheller/shadow-cljs/compare/3d8d125f4d9f0613e15fee8af0ae13691dac4b00...958e50b14d9a0b184d73e93d54c84247b3c95547) - 2019-02-21
- [ [`958e5`](https://github.com/thheller/shadow-cljs/commit/958e50b14d9a0b184d73e93d54c84247b3c95547) ] adjust :react-native target so release mode works
- [ [`3d8d1`](https://github.com/thheller/shadow-cljs/commit/3d8d125f4d9f0613e15fee8af0ae13691dac4b00) ] completely remove the require removal changes

## [2.8.7](https://github.com/thheller/shadow-cljs/compare/dd54de8c72f0a50b275b28c48bc283938da33395...dd54de8c72f0a50b275b28c48bc283938da33395) - 2019-02-20
- [ [`dd54d`](https://github.com/thheller/shadow-cljs/commit/dd54de8c72f0a50b275b28c48bc283938da33395) ] actually disable the require removal compiler pass

## [2.8.6](https://github.com/thheller/shadow-cljs/compare/6db7a8286aaf7ab6cebe5462c513f0d8320aec35...2230ab777352b2ab7f3906a962ac8dba53f161df) - 2019-02-20
- [ [`2230a`](https://github.com/thheller/shadow-cljs/commit/2230ab777352b2ab7f3906a962ac8dba53f161df) ] disable js require removal
- [ [`6db7a`](https://github.com/thheller/shadow-cljs/commit/6db7a8286aaf7ab6cebe5462c513f0d8320aec35) ] tweak classpath ignore pattern

## [2.8.5](https://github.com/thheller/shadow-cljs/compare/2ea817906d0b0e0fa07073daf783af5c443b5d69...2ea817906d0b0e0fa07073daf783af5c443b5d69) - 2019-02-20
- [ [`2ea81`](https://github.com/thheller/shadow-cljs/commit/2ea817906d0b0e0fa07073daf783af5c443b5d69) ] fix broken java version parse

## [2.8.4](https://github.com/thheller/shadow-cljs/compare/2d4d49d70fc03781763b631f1ecbf84023238588...aa6f25799ebe7c1d5b1bf9e00eb5c6951b7944c3) - 2019-02-19
- [ [`aa6f2`](https://github.com/thheller/shadow-cljs/commit/aa6f25799ebe7c1d5b1bf9e00eb5c6951b7944c3) ] fix nashorn deprecation warning
- [ [`2d4d4`](https://github.com/thheller/shadow-cljs/commit/2d4d49d70fc03781763b631f1ecbf84023238588) ] change default language settings again

## [2.8.3](https://github.com/thheller/shadow-cljs/compare/e8f85964bebbcd830f81514b203dd47038815f4d...f396caecf908b3e135a58f1af5aa433b1ce19326) - 2019-02-19
- [ [`f396c`](https://github.com/thheller/shadow-cljs/commit/f396caecf908b3e135a58f1af5aa433b1ce19326) ] rename shadow$loader variable due to weird renaming bug
- [ [`e8f85`](https://github.com/thheller/shadow-cljs/commit/e8f85964bebbcd830f81514b203dd47038815f4d) ] prevent goog caching for generated virtual sources

## [2.8.2](https://github.com/thheller/shadow-cljs/compare/115df1450f284c4190ac00ecfaa6a867db2b1b09...56d362e81f7ae2ac49d742b727d0dfaba3db82c7) - 2019-02-18
- [ [`56d36`](https://github.com/thheller/shadow-cljs/commit/56d362e81f7ae2ac49d742b727d0dfaba3db82c7) ] fix -A handling when using deps.edn
- [ [`115df`](https://github.com/thheller/shadow-cljs/commit/115df1450f284c4190ac00ecfaa6a867db2b1b09) ] fix require issue using new Closure Library in node env

## [2.8.1](https://github.com/thheller/shadow-cljs/compare/80b00a6bd61537c3a4dbcaece1f7555234cc2d87...80b00a6bd61537c3a4dbcaece1f7555234cc2d87) - 2019-02-18
- [ [`80b00`](https://github.com/thheller/shadow-cljs/commit/80b00a6bd61537c3a4dbcaece1f7555234cc2d87) ] fix babel-worker.js issue

## [2.8.0](https://github.com/thheller/shadow-cljs/compare/e1a818df19fd5c9ec4abc9c607c83dc5d3fc2145...ccddea4b467778eb81acfd368cea563ea4f45677) - 2019-02-17
- [ [`ccdde`](https://github.com/thheller/shadow-cljs/commit/ccddea4b467778eb81acfd368cea563ea4f45677) ] make :minimize-require the default in :release builds
- [ [`3efb7`](https://github.com/thheller/shadow-cljs/commit/3efb71864e7958d0e52d3d49d0ff0ad3f573aadd) ] avoid compiling goog sources twice in :release mode
- [ [`01a0b`](https://github.com/thheller/shadow-cljs/commit/01a0b4810047c719ae528f5ff2bdd26b0dcfe4fd) ] allow nrepl to be disabled globally via user config
- [ [`7f362`](https://github.com/thheller/shadow-cljs/commit/7f362e30476f477c1992761ed848a10beedd5b6a) ] allow adding extra :dependencies via ~/.shadow-cljs/config.edn
- [ [`e8ed3`](https://github.com/thheller/shadow-cljs/commit/e8ed39ad2081b34f219c74c9c893143c3ee794ea) ] remove JS deps when :advanced removed their uses
- [ [`376ca`](https://github.com/thheller/shadow-cljs/commit/376cac280f96f608fe606db382c28282f7a9ed2c) ] add basic cache for goog converted files
- [ [`e1a81`](https://github.com/thheller/shadow-cljs/commit/e1a818df19fd5c9ec4abc9c607c83dc5d3fc2145) ] bump closure library !possibly breaking!

## [2.7.36](https://github.com/thheller/shadow-cljs/compare/237149e284f588b93dfb8616a084be2af95d24db...237149e284f588b93dfb8616a084be2af95d24db) - 2019-02-14
- [ [`23714`](https://github.com/thheller/shadow-cljs/commit/237149e284f588b93dfb8616a084be2af95d24db) ] fix broken push-state handler

## [2.7.35](https://github.com/thheller/shadow-cljs/compare/8124f3ccaa6660f60310995155b776b24b93b619...8ddb18b41a797ed008314d3d89f44d0a54bf303f) - 2019-02-14
- [ [`8ddb1`](https://github.com/thheller/shadow-cljs/commit/8ddb18b41a797ed008314d3d89f44d0a54bf303f) ] create missing :dev-http roots instead of complaining
- [ [`d0d63`](https://github.com/thheller/shadow-cljs/commit/d0d6342c2669b8d7342743ab08c6bc05633f3838) ] make sure all old http related config is supported
- [ [`ffc02`](https://github.com/thheller/shadow-cljs/commit/ffc02f84268c97ff115b50272718dd8c30b54d6a) ] adjust css watch to new :dev-http architecture
- [ [`8124f`](https://github.com/thheller/shadow-cljs/commit/8124f3ccaa6660f60310995155b776b24b93b619) ] restructure dev http config, move out of :builds

## [2.7.34](https://github.com/thheller/shadow-cljs/compare/05c4ae46204192dca4a7ffea815d596bcdcdf046...05c4ae46204192dca4a7ffea815d596bcdcdf046) - 2019-02-14
- [ [`05c4a`](https://github.com/thheller/shadow-cljs/commit/05c4ae46204192dca4a7ffea815d596bcdcdf046) ] bump deps

## [2.7.33](https://github.com/thheller/shadow-cljs/compare/30508df9fd4998d394688b761127dc3a5807e03e...5b01afa38dcccbc5b13bb8ff21d7179f028c5836) - 2019-02-13
- [ [`5b01a`](https://github.com/thheller/shadow-cljs/commit/5b01afa38dcccbc5b13bb8ff21d7179f028c5836) ] add support for bootstrap host on node
- [ [`76bda`](https://github.com/thheller/shadow-cljs/commit/76bda07215ea8543b2e8ba64863de0095f6d822e) ] fix css reload in electron
- [ [`a2be1`](https://github.com/thheller/shadow-cljs/commit/a2be188a4c42327e7d8271d4877bc9206cf53fcc) ] use wss in UI when loaded over ssl
- [ [`dead1`](https://github.com/thheller/shadow-cljs/commit/dead100fe1c50fbb84bed5fe03f48af7968192f1) ] slim down the aot jar a bit
- [ [`30508`](https://github.com/thheller/shadow-cljs/commit/30508df9fd4998d394688b761127dc3a5807e03e) ] fix cache issue when using :minimize-require true

## [2.7.32](https://github.com/thheller/shadow-cljs/compare/68c7d6d77c33f07ccb242b9f0f68762d0634d358...2da00a58505a7676742bb11f39c6d47f317057b2) - 2019-02-11
- [ [`2da00`](https://github.com/thheller/shadow-cljs/commit/2da00a58505a7676742bb11f39c6d47f317057b2) ] changing npm deps did not properly trigger reloads in watch
- [ [`68c7d`](https://github.com/thheller/shadow-cljs/commit/68c7d6d77c33f07ccb242b9f0f68762d0634d358) ] delay loading expound until used (saves couple ms on startup)

## [2.7.31](https://github.com/thheller/shadow-cljs/compare/703eeb1aadad66ffc5b6b1b2aa63eeb650d0774f...4e01ae1af2d0a1f4c2ef395767edab0170db4910) - 2019-02-10
- [ [`4e01a`](https://github.com/thheller/shadow-cljs/commit/4e01ae1af2d0a1f4c2ef395767edab0170db4910) ] allow nrepl to be disabled via :nrepl false in config
- [ [`703ee`](https://github.com/thheller/shadow-cljs/commit/703eeb1aadad66ffc5b6b1b2aa63eeb650d0774f) ] use new :clojure.error/* data in errors

## [2.7.30](https://github.com/thheller/shadow-cljs/compare/f30aa0f6b7bc666a4e5d5be02da29f90d2cf3cc7...f5e00c368a84cdd20b9c833ba64160494e92ce49) - 2019-02-07
- [ [`f5e00`](https://github.com/thheller/shadow-cljs/commit/f5e00c368a84cdd20b9c833ba64160494e92ce49) ] revert 9c45df72c57f6 and handle nils elsewhere
- [ [`2bc02`](https://github.com/thheller/shadow-cljs/commit/2bc02c863fe5a540aa34961097c2095ef2b79e00) ] allow passing additional build config via the CLI
- [ [`f30aa`](https://github.com/thheller/shadow-cljs/commit/f30aa0f6b7bc666a4e5d5be02da29f90d2cf3cc7) ] allow use of keyword module ids in shadow.loader

## [2.7.29](https://github.com/thheller/shadow-cljs/compare/280a1396f3f3292f548ad01f02ff24e6cb7f1fa7...35f8141f6d5496506f5821f969f834636a3c5d2c) - 2019-02-07
- [ [`35f81`](https://github.com/thheller/shadow-cljs/commit/35f8141f6d5496506f5821f969f834636a3c5d2c) ] fix dead require detection (broken since 2.7.26)
- [ [`176e3`](https://github.com/thheller/shadow-cljs/commit/176e37285a3f650b379c6f779d7b9819fa554368) ] tweak shadow.resource errors and a bit of doc
- [ [`280a1`](https://github.com/thheller/shadow-cljs/commit/280a1396f3f3292f548ad01f02ff24e6cb7f1fa7) ] add support for inline static assets

## [2.7.28](https://github.com/thheller/shadow-cljs/compare/8ba8909debd532b40712ef660b4a53db4573ff6b...8ba8909debd532b40712ef660b4a53db4573ff6b) - 2019-02-06
- [ [`8ba89`](https://github.com/thheller/shadow-cljs/commit/8ba8909debd532b40712ef660b4a53db4573ff6b) ] add more source map options

## [2.7.27](https://github.com/thheller/shadow-cljs/compare/ee1edc0a49f5b99dfc2974ce3dc2ee2028dffc1f...ee1edc0a49f5b99dfc2974ce3dc2ee2028dffc1f) - 2019-02-06
- [ [`ee1ed`](https://github.com/thheller/shadow-cljs/commit/ee1edc0a49f5b99dfc2974ce3dc2ee2028dffc1f) ] less strict classpath handling

## [2.7.26](https://github.com/thheller/shadow-cljs/compare/37e35cf10bec01111747beb909323e1a216905ac...4859a5c3d93436e32d52ecb701442517e8d57d43) - 2019-02-06
- [ [`4859a`](https://github.com/thheller/shadow-cljs/commit/4859a5c3d93436e32d52ecb701442517e8d57d43) ] don't default to minimize-require for now
- [ [`eaa23`](https://github.com/thheller/shadow-cljs/commit/eaa23806cc1f4e359d8b03c548d67260d22b4709) ] allow classpath directories ending with .jar
- [ [`c2abe`](https://github.com/thheller/shadow-cljs/commit/c2abeafe40018f2cf219a41807f3e301b35d00ae) ] add option to minimize require calls for imported JS deps
- [ [`34f08`](https://github.com/thheller/shadow-cljs/commit/34f089a2a270ffbe083dadabe1db6aee43d0e83b) ] make :hud configurable again
- [ [`def3c`](https://github.com/thheller/shadow-cljs/commit/def3c62741729cdcbc88b44a82ee607d5c40c449) ] bump clojure/closure deps
- [ [`77d72`](https://github.com/thheller/shadow-cljs/commit/77d72f9803456007ea38e4d110c6530aa97d954c) ] watch package.json when build failed due to missing JS deps
- [ [`37e35`](https://github.com/thheller/shadow-cljs/commit/37e35cf10bec01111747beb909323e1a216905ac) ] add link to learn re-frame course

## [2.7.25](https://github.com/thheller/shadow-cljs/compare/7c4cc6e77ec8fe28d8d67ae25ceb7c71ac514984...f1187b37360ccf66ad3210bd15e53bbeaa1a1e2f) - 2019-02-02
- [ [`f1187`](https://github.com/thheller/shadow-cljs/commit/f1187b37360ccf66ad3210bd15e53bbeaa1a1e2f) ] more node full-bundle tweaks
- [ [`6feeb`](https://github.com/thheller/shadow-cljs/commit/6feeb4db26eb4281f6728d583700933d206b9d21) ] enable one file bundle for node-library
- [ [`b0927`](https://github.com/thheller/shadow-cljs/commit/b0927bb96b30cbdf4ed47a33028c546d4d4c9309) ] experimental support for self-contained node builds
- [ [`e4504`](https://github.com/thheller/shadow-cljs/commit/e45047720585f131848d5f92c82902f324ddf039) ] avoid lazy seqs in analyze
- [ [`7c4cc`](https://github.com/thheller/shadow-cljs/commit/7c4cc6e77ec8fe28d8d67ae25ceb7c71ac514984) ] port CLJ-2473

## [2.7.24](https://github.com/thheller/shadow-cljs/compare/cbd91dfad8b972773b50a8b26dee032af90185da...83b4d5558166be9617f95d769bb0933fc953fd1a) - 2019-01-31
- [ [`83b4d`](https://github.com/thheller/shadow-cljs/commit/83b4d5558166be9617f95d769bb0933fc953fd1a) ] bump cljs 1.10.516
- [ [`cbd91`](https://github.com/thheller/shadow-cljs/commit/cbd91dfad8b972773b50a8b26dee032af90185da) ] work arround warnings in rrb-vector

## [2.7.23](https://github.com/thheller/shadow-cljs/compare/9c45df72c57f6cb64a5a8811852db5b1b770ee1d...9c45df72c57f6cb64a5a8811852db5b1b770ee1d) - 2019-01-30
- [ [`9c45d`](https://github.com/thheller/shadow-cljs/commit/9c45df72c57f6cb64a5a8811852db5b1b770ee1d) ] ensure #shadow/env doesn't return nils

## [2.7.22](https://github.com/thheller/shadow-cljs/compare/4e0e78aa22341260ebcaadb2af0ee17ad1c236db...4e0e78aa22341260ebcaadb2af0ee17ad1c236db) - 2019-01-30
- [ [`4e0e7`](https://github.com/thheller/shadow-cljs/commit/4e0e78aa22341260ebcaadb2af0ee17ad1c236db) ] relax cache-control to allow conditional cache

## [2.7.21](https://github.com/thheller/shadow-cljs/compare/11966a88c2ecac556203cc4db39f491f1848698d...20c75b6b11a64a6910a4f95f4dcae195f76d0562) - 2019-01-30
- [ [`20c75`](https://github.com/thheller/shadow-cljs/commit/20c75b6b11a64a6910a4f95f4dcae195f76d0562) ] make startup less noisy
- [ [`11966`](https://github.com/thheller/shadow-cljs/commit/11966a88c2ecac556203cc4db39f491f1848698d) ] bump some deps to get rid of reflection warnings on startup

## [2.7.20](https://github.com/thheller/shadow-cljs/compare/8b4641ad575e2260be870dd2d3ace4d626e4777c...8b4641ad575e2260be870dd2d3ace4d626e4777c) - 2019-01-28
- [ [`8b464`](https://github.com/thheller/shadow-cljs/commit/8b4641ad575e2260be870dd2d3ace4d626e4777c) ] fix more accidental warnings

## [2.7.19](https://github.com/thheller/shadow-cljs/compare/a21436503ad620c6c095d46c21a4882b079f0218...a21436503ad620c6c095d46c21a4882b079f0218) - 2019-01-28
- [ [`a2143`](https://github.com/thheller/shadow-cljs/commit/a21436503ad620c6c095d46c21a4882b079f0218) ] fix index out of bounds error in new resolve-var call

## [2.7.18](https://github.com/thheller/shadow-cljs/compare/0aaa281853d113829d01a6ba09cc328578b969cc...bf2b8ae126d1e9f1348d0b69a2be5c0111070a61) - 2019-01-28
- [ [`bf2b8`](https://github.com/thheller/shadow-cljs/commit/bf2b8ae126d1e9f1348d0b69a2be5c0111070a61) ] attempt to make resolve-var more accurate
- [ [`ffbae`](https://github.com/thheller/shadow-cljs/commit/ffbaece225cb2fab07a4e5ced72dc5a1cf8593f3) ] improve warnings printed in the terminal/REPL
- [ [`39897`](https://github.com/thheller/shadow-cljs/commit/3989766682cf78850a2d6211d2b486545f1a3b70) ] rough version of ns inspection in the UI
- [ [`8c3dd`](https://github.com/thheller/shadow-cljs/commit/8c3dd960a6fd36fe1d1d79f8e9b0f49ddc334b7e) ] fix weird JS issue
- [ [`718e7`](https://github.com/thheller/shadow-cljs/commit/718e7718eeec43bb4c1033987e454f22c37d6f49) ] improve error message when a foreign-lib namespace is missing
- [ [`861d5`](https://github.com/thheller/shadow-cljs/commit/861d5e32e0272c727ad9503946b76a5cf1b52ddf) ] add warning when trying dynamic require at runtime
- [ [`11229`](https://github.com/thheller/shadow-cljs/commit/112293526e3331e0ac60cc4aefdc71ec3c3e6311) ] remove support for :whitespace optimizations
- [ [`0aaa2`](https://github.com/thheller/shadow-cljs/commit/0aaa281853d113829d01a6ba09cc328578b969cc) ] drop the compiler hacks for cljs.test

## [2.7.17](https://github.com/thheller/shadow-cljs/compare/147f8db14e19897d35948ae3515833050f382ed3...a784a335e0fb5e01936cb5e222d62cf12ac7ff19) - 2019-01-21
- [ [`a784a`](https://github.com/thheller/shadow-cljs/commit/a784a335e0fb5e01936cb5e222d62cf12ac7ff19) ] add experimental api to allow custom ws messages from hooks
- [ [`ba986`](https://github.com/thheller/shadow-cljs/commit/ba9860292df68ccec499232a20cad66359eaa6e9) ] upgrade xterm so github stops complaining
- [ [`147f8`](https://github.com/thheller/shadow-cljs/commit/147f8db14e19897d35948ae3515833050f382ed3) ] Reload assets on both `:new` and `:mod` events (#431)

## [2.7.16](https://github.com/thheller/shadow-cljs/compare/0b7b22855be0f2272e3b8e90fdf4e2f4e2a637f7...7e03bc1e53ce48cc537e699d4d561dd328d6b0c5) - 2019-01-19
- [ [`7e03b`](https://github.com/thheller/shadow-cljs/commit/7e03bc1e53ce48cc537e699d4d561dd328d6b0c5) ] use only first connected runtime for REPL when multiple are connected
- [ [`0b7b2`](https://github.com/thheller/shadow-cljs/commit/0b7b22855be0f2272e3b8e90fdf4e2f4e2a637f7) ] basic test build that can be deployed via "now"

## [2.7.15](https://github.com/thheller/shadow-cljs/compare/2ce647796541eff0b59a69c89d17a37facd8b9ce...2ce647796541eff0b59a69c89d17a37facd8b9ce) - 2019-01-11
- [ [`2ce64`](https://github.com/thheller/shadow-cljs/commit/2ce647796541eff0b59a69c89d17a37facd8b9ce) ] further restrict which properties are collected from JS files

## [2.7.14](https://github.com/thheller/shadow-cljs/compare/ab20ae493ee4ecc72abad4700bd6d44251512a1b...d70c57f8e14a7420b65feadeb5e016527e851583) - 2019-01-10
- [ [`d70c5`](https://github.com/thheller/shadow-cljs/commit/d70c57f8e14a7420b65feadeb5e016527e851583) ] update PropertyCollector to use Closure Compiler implementation
- [ [`aca8b`](https://github.com/thheller/shadow-cljs/commit/aca8b779c9b5c46a51c289ddf177ffee8a6f450b) ] dump 2.7.13
- [ [`ab20a`](https://github.com/thheller/shadow-cljs/commit/ab20ae493ee4ecc72abad4700bd6d44251512a1b) ] properly munge ns-roots for :npm-module

## [2.7.12](https://github.com/thheller/shadow-cljs/compare/5de37a9ccee84b88119a6ff7df0d7f6348949ba7...d0b2d377f6097c7531143fdc6d0b4cedb827dca9) - 2019-01-06
- [ [`d0b2d`](https://github.com/thheller/shadow-cljs/commit/d0b2d377f6097c7531143fdc6d0b4cedb827dca9) ] always expose all ns roots in :npm-module builds
- [ [`5de37`](https://github.com/thheller/shadow-cljs/commit/5de37a9ccee84b88119a6ff7df0d7f6348949ba7) ] fix warning about shadow$umd$export

## [2.7.11](https://github.com/thheller/shadow-cljs/compare/27fee66aa600c1284370e6ae8f7e276cc690558d...27fee66aa600c1284370e6ae8f7e276cc690558d) - 2019-01-06
- [ [`27fee`](https://github.com/thheller/shadow-cljs/commit/27fee66aa600c1284370e6ae8f7e276cc690558d) ] expose closure code stripping options for release builds

## [2.7.10](https://github.com/thheller/shadow-cljs/compare/55ccd94dbbc0c2174921e2a40e92040c1aa0b8f1...23be4ff6b32efc9ba406cc7e4eeae7c75dfce5d1) - 2019-01-04
- [ [`23be4`](https://github.com/thheller/shadow-cljs/commit/23be4ff6b32efc9ba406cc7e4eeae7c75dfce5d1) ] delay connecting devtools in case document.body does not exist
- [ [`778c1`](https://github.com/thheller/shadow-cljs/commit/778c14d4b1d1613b7e711888c1c279b38d9536aa) ] fix *ns* metadata not being updated properly
- [ [`55ccd`](https://github.com/thheller/shadow-cljs/commit/55ccd94dbbc0c2174921e2a40e92040c1aa0b8f1) ] fix failing :bootstrap builds

## [2.7.9](https://github.com/thheller/shadow-cljs/compare/5429179134db20b24732daeafbfa2b34153fb46d...5429179134db20b24732daeafbfa2b34153fb46d) - 2018-12-11
- [ [`54291`](https://github.com/thheller/shadow-cljs/commit/5429179134db20b24732daeafbfa2b34153fb46d) ] fix broken node-library dev builds

## [2.7.8](https://github.com/thheller/shadow-cljs/compare/81ee576240355c26c247aa8c747acb3b517fd313...a44e444f761a0192f616d89c3a08872584057ff6) - 2018-12-04
- [ [`a44e4`](https://github.com/thheller/shadow-cljs/commit/a44e444f761a0192f616d89c3a08872584057ff6) ] change how :node-library generates its export
- [ [`9aa70`](https://github.com/thheller/shadow-cljs/commit/9aa7016cdfe670c836d6ec99275a079deb3310e9) ] support :prepend in :node-library builds
- [ [`81ee5`](https://github.com/thheller/shadow-cljs/commit/81ee576240355c26c247aa8c747acb3b517fd313) ] use cljs-test-display for :browser-test

## [2.7.7](https://github.com/thheller/shadow-cljs/compare/64b2db0b657275c552f045e802529e6cfea78d2f...d319d3b60bc2b36c41a6b6fe0abcb897130d30c8) - 2018-12-04
- [ [`d319d`](https://github.com/thheller/shadow-cljs/commit/d319d3b60bc2b36c41a6b6fe0abcb897130d30c8) ] add warning when unwanted deps are listed in shadow-cljs.edn
- [ [`fb32a`](https://github.com/thheller/shadow-cljs/commit/fb32a1b25fa92913af09eacea1c8a64117bb4a26) ] consider simplified externs before warning about :infer-externs
- [ [`5986e`](https://github.com/thheller/shadow-cljs/commit/5986e7953cbcc39c48ff7a74f01086082c3177b1) ] bump deps
- [ [`13b29`](https://github.com/thheller/shadow-cljs/commit/13b29ab8251946bcfcdf08b37146f6054206be9b) ] add support for :compiler-options :warnings overrides
- [ [`2a4ed`](https://github.com/thheller/shadow-cljs/commit/2a4ed0a363a191f296d8a42ca3b5aed4b9f1bff4) ] Added managed dependencies to remove version ambiguity (#411)
- [ [`64b2d`](https://github.com/thheller/shadow-cljs/commit/64b2db0b657275c552f045e802529e6cfea78d2f) ] fix error message when executable is not found

## [2.7.6](https://github.com/thheller/shadow-cljs/compare/4eb7a9f84147adc967078ca1b9b9a71bcfeeb1bb...4eb7a9f84147adc967078ca1b9b9a71bcfeeb1bb) - 2018-11-23
- [ [`4eb7a`](https://github.com/thheller/shadow-cljs/commit/4eb7a9f84147adc967078ca1b9b9a71bcfeeb1bb) ] add support for symlinks on the classpath

## [2.7.5](https://github.com/thheller/shadow-cljs/compare/93551ebe1a101929edb481cd72292ecdbbd4050c...93551ebe1a101929edb481cd72292ecdbbd4050c) - 2018-11-23
- [ [`93551`](https://github.com/thheller/shadow-cljs/commit/93551ebe1a101929edb481cd72292ecdbbd4050c) ] make sure undertow only receives absolute paths

## [2.7.4](https://github.com/thheller/shadow-cljs/compare/bf49c260dffcff69a098c3c5946c5569a419cde3...331665a90f4c0c53ca98eec59dbbdb3d2b992ceb) - 2018-11-17
- [ [`33166`](https://github.com/thheller/shadow-cljs/commit/331665a90f4c0c53ca98eec59dbbdb3d2b992ceb) ] work arround closure renaming global to window.global
- [ [`bf49c`](https://github.com/thheller/shadow-cljs/commit/bf49c260dffcff69a098c3c5946c5569a419cde3) ] add link to example boodle app

## [2.7.3](https://github.com/thheller/shadow-cljs/compare/cb893d1286ba11b953ae90ce308d5862a0932020...8d175ac469a4acc6c15fe791f1eca1eaaddad0fc) - 2018-11-13
- [ [`8d175`](https://github.com/thheller/shadow-cljs/commit/8d175ac469a4acc6c15fe791f1eca1eaaddad0fc) ] fix compile errors getting lost in nREPL
- [ [`606e1`](https://github.com/thheller/shadow-cljs/commit/606e115a3c17b1c4b51d0069f6bcbb55783a98e6) ] allow custom command for installing npm packages from deps.cljs
- [ [`fa0bb`](https://github.com/thheller/shadow-cljs/commit/fa0bb39d3c80791a0cb4a9b26aaf3df6e3fdb964) ] bump shadow-cljsjs
- [ [`8dad9`](https://github.com/thheller/shadow-cljs/commit/8dad928b80a8f5fecd756fe6b2d696391469e579) ] fix error format for macro-load errors
- [ [`cb893`](https://github.com/thheller/shadow-cljs/commit/cb893d1286ba11b953ae90ce308d5862a0932020) ] better error when package.json "main" entries are missing

## [2.7.2](https://github.com/thheller/shadow-cljs/compare/f872d2d4e99ef585231f329e8137bd7f3815ddac...f872d2d4e99ef585231f329e8137bd7f3815ddac) - 2018-11-08
- [ [`f872d`](https://github.com/thheller/shadow-cljs/commit/f872d2d4e99ef585231f329e8137bd7f3815ddac) ] add missing binding in REPL

## [2.7.1](https://github.com/thheller/shadow-cljs/compare/eaef64b405708748e0512f977ab860b4534e2f70...eaef64b405708748e0512f977ab860b4534e2f70) - 2018-11-08
- [ [`eaef6`](https://github.com/thheller/shadow-cljs/commit/eaef64b405708748e0512f977ab860b4534e2f70) ] slightly safer externs inference for js/... for CLJS namespaces

## [2.7.0](https://github.com/thheller/shadow-cljs/compare/76ed31b51340d182101a323aff343ca4aa99732e...eb87105ee3557b193d362559e4cbbee9c739497a) - 2018-11-06
- [ [`eb871`](https://github.com/thheller/shadow-cljs/commit/eb87105ee3557b193d362559e4cbbee9c739497a) ] upgrade to CLJS 1.10.439
- [ [`068c8`](https://github.com/thheller/shadow-cljs/commit/068c89f20b06a0592da8a42f50dc56d9aefe7d7e) ] attempt to work around resolve issues in CLJS 1.10.439
- [ [`d6300`](https://github.com/thheller/shadow-cljs/commit/d6300ec6940bb093a6ee338fd82cdcafa7ad9389) ] add missing compiler env binding for REPL read resolve-symbol
- [ [`76ed3`](https://github.com/thheller/shadow-cljs/commit/76ed31b51340d182101a323aff343ca4aa99732e) ] Merge branch 'master' of github.com:thheller/shadow-cljs

## [2.6.24](https://github.com/thheller/shadow-cljs/compare/...) - 2018-11-06

## [2.6.24](https://github.com/thheller/shadow-cljs/compare/8c1bca52c03fd71bcaaeb537cb2732fa880d857a...196ccca6e6022ed34ad3776c020f532e1ff2b6ae) - 2018-11-06
- [ [`196cc`](https://github.com/thheller/shadow-cljs/commit/196ccca6e6022ed34ad3776c020f532e1ff2b6ae) ] add missing reader/resolve-symbol binding
- [ [`27670`](https://github.com/thheller/shadow-cljs/commit/27670612c314a3a4ab6beac00bb27cc753ced0b7) ] add quickstart section to readme
- [ [`8c1bc`](https://github.com/thheller/shadow-cljs/commit/8c1bca52c03fd71bcaaeb537cb2732fa880d857a) ] improve error message when trying to access .js outside classpath

## [2.6.23](https://github.com/thheller/shadow-cljs/compare/14424bb3562c35a6679009635c3588496b743940...14424bb3562c35a6679009635c3588496b743940) - 2018-11-01
- [ [`14424`](https://github.com/thheller/shadow-cljs/commit/14424bb3562c35a6679009635c3588496b743940) ] only use hawk on osx

## [2.6.22](https://github.com/thheller/shadow-cljs/compare/118fff4571cae0570c4eeb22a589aeaf3b14dcf7...118fff4571cae0570c4eeb22a589aeaf3b14dcf7) - 2018-10-31
- [ [`118ff`](https://github.com/thheller/shadow-cljs/commit/118fff4571cae0570c4eeb22a589aeaf3b14dcf7) ] catch exceptions when trying to extract source-excerpts

## [2.6.21](https://github.com/thheller/shadow-cljs/compare/673325e22282efb942508b945678160be22b0b16...673325e22282efb942508b945678160be22b0b16) - 2018-10-30
- [ [`67332`](https://github.com/thheller/shadow-cljs/commit/673325e22282efb942508b945678160be22b0b16) ] change dev proxy server setup and add more options

## [2.6.20](https://github.com/thheller/shadow-cljs/compare/f10dc37c7d38138b30808dd06e3f1eca1949f760...f10dc37c7d38138b30808dd06e3f1eca1949f760) - 2018-10-30
- [ [`f10dc`](https://github.com/thheller/shadow-cljs/commit/f10dc37c7d38138b30808dd06e3f1eca1949f760) ] add support for https :proxy-url in dev server

## [2.6.19](https://github.com/thheller/shadow-cljs/compare/db7d19bf68a5f3b9a3f9c9d056980d43a27108d9...f5ea6c22221ce3ae0165aa446e8c8c840a03ff1f) - 2018-10-26
- [ [`f5ea6`](https://github.com/thheller/shadow-cljs/commit/f5ea6c22221ce3ae0165aa446e8c8c840a03ff1f) ] run compile/release/check sequentially when called from the CLI
- [ [`f2022`](https://github.com/thheller/shadow-cljs/commit/f2022eecae929da741def630651fbfe95201ff9f) ] Merge branch 'master' of github.com:thheller/shadow-cljs
- [ [`db427`](https://github.com/thheller/shadow-cljs/commit/db42782f8b38d12653cf14d5873bfb0bb7f03fa5) ] [ Summary ] Handle resources for both junction and symlink on windows. (#400)
- [ [`db7d1`](https://github.com/thheller/shadow-cljs/commit/db7d19bf68a5f3b9a3f9c9d056980d43a27108d9) ] change exception handling in log printer

## [2.6.18](https://github.com/thheller/shadow-cljs/compare/fc4739f83052652f6abe761cf93d57876ed74801...6f39dcb2e2222813f9853e8540a184d3d13ef224) - 2018-10-25
- [ [`6f39d`](https://github.com/thheller/shadow-cljs/commit/6f39dcb2e2222813f9853e8540a184d3d13ef224) ] allow undertow file handler to follow links
- [ [`fc473`](https://github.com/thheller/shadow-cljs/commit/fc4739f83052652f6abe761cf93d57876ed74801) ] add application/wasm mime type

## [2.6.17](https://github.com/thheller/shadow-cljs/compare/e12d59bba4d980e3b05ca9333110dba73c18e6c8...7cef4eff0f540178efaa07660594da44d8279b54) - 2018-10-24
- [ [`7cef4`](https://github.com/thheller/shadow-cljs/commit/7cef4eff0f540178efaa07660594da44d8279b54) ] fix closure js conversion cache bug
- [ [`e12d5`](https://github.com/thheller/shadow-cljs/commit/e12d59bba4d980e3b05ca9333110dba73c18e6c8) ] initialize REPL after build config

## [2.6.16](https://github.com/thheller/shadow-cljs/compare/9c87128b6d8a430f45892b9e9edb0b991b44bc42...9c87128b6d8a430f45892b9e9edb0b991b44bc42) - 2018-10-24
- [ [`9c871`](https://github.com/thheller/shadow-cljs/commit/9c87128b6d8a430f45892b9e9edb0b991b44bc42) ] fix broken live reload due to bad OPTIONS request handling

## [2.6.15](https://github.com/thheller/shadow-cljs/compare/899bbcc5bdb8d16cfa06e1371244433fbab73772...427defadcf49cf57e39b63c8ac20d062b67fb585) - 2018-10-23
- [ [`427de`](https://github.com/thheller/shadow-cljs/commit/427defadcf49cf57e39b63c8ac20d062b67fb585) ] ensure that all test namespaces are compiled before runner-ns
- [ [`1361d`](https://github.com/thheller/shadow-cljs/commit/1361d7af7cc22241b86990392aa260b45ea61711) ] fix build log update for UI
- [ [`a0bc3`](https://github.com/thheller/shadow-cljs/commit/a0bc3f2b757c112de8a8407c0685dd38c43f9249) ] start using undertow handlers instead of ring
- [ [`c43ed`](https://github.com/thheller/shadow-cljs/commit/c43ed254c868408447d3cc7f942b06d8027443e3) ] bring raw log back into UI for now
- [ [`899bb`](https://github.com/thheller/shadow-cljs/commit/899bbcc5bdb8d16cfa06e1371244433fbab73772) ] check if specified :entries get moved out of their module

## [2.6.14](https://github.com/thheller/shadow-cljs/compare/2e08f24e7d168622a50b0127bf6199a1d0ad31b0...14a86212987b3ef2588fa7d60882355aff3b4272) - 2018-10-18
- [ [`14a86`](https://github.com/thheller/shadow-cljs/commit/14a86212987b3ef2588fa7d60882355aff3b4272) ] no need to recompile files with warnings with every cycle
- [ [`c1a71`](https://github.com/thheller/shadow-cljs/commit/c1a71d2178f51fdb4b8bb457f33ff988c75cd149) ] tweak parallel compilation
- [ [`2e08f`](https://github.com/thheller/shadow-cljs/commit/2e08f24e7d168622a50b0127bf6199a1d0ad31b0) ] optimize source map handling

## [2.6.13](https://github.com/thheller/shadow-cljs/compare/8f817abbbb6785b819e2ed9ffd02d9059974f2fd...f32e40f4d987d1f8d4bc246c32d3fae44b040bea) - 2018-10-17
- [ [`f32e4`](https://github.com/thheller/shadow-cljs/commit/f32e40f4d987d1f8d4bc246c32d3fae44b040bea) ] add back nashorn flag with version check
- [ [`fca9d`](https://github.com/thheller/shadow-cljs/commit/fca9d3500cd75afbbf7703bf396a8ecac98c7742) ] fix build-failure print for the console
- [ [`d5dbd`](https://github.com/thheller/shadow-cljs/commit/d5dbd0dc1acad2c3c50e49ed0bbce3b71c0473d3) ] use a more robust way to launch external processes (hopefully)
- [ [`8f817`](https://github.com/thheller/shadow-cljs/commit/8f817abbbb6785b819e2ed9ffd02d9059974f2fd) ] add sentry/electron for exception reports

## [2.6.12](https://github.com/thheller/shadow-cljs/compare/97b4a2bf344c0d80767307334a969700d600bebc...97b4a2bf344c0d80767307334a969700d600bebc) - 2018-10-16
- [ [`97b4a`](https://github.com/thheller/shadow-cljs/commit/97b4a2bf344c0d80767307334a969700d600bebc) ] remove nashorn deprecation warning flag

## [2.6.11](https://github.com/thheller/shadow-cljs/compare/ad4a3c4a979f0b2a2d1fb73ef2b471f0574209f8...11b97bbb03d91b5bd314891889864bf15656b0b5) - 2018-10-15
- [ [`11b97`](https://github.com/thheller/shadow-cljs/commit/11b97bbb03d91b5bd314891889864bf15656b0b5) ] make sure only cljs+goog names affect munging
- [ [`86dd1`](https://github.com/thheller/shadow-cljs/commit/86dd1ff8971347b1c285c5355468b7a0acb62d01) ] make sure the CLI check thread doesn't end up keeping the JVM alive
- [ [`33fd6`](https://github.com/thheller/shadow-cljs/commit/33fd629d53d2fa43b2f080afdd3009c4df661a54) ] ensure java process exits when shadow-cljs node process is killed
- [ [`ed559`](https://github.com/thheller/shadow-cljs/commit/ed5590e705ebf65e4350f2abbb5beb1986caeefd) ] fix race condition in cljs.compiler/munge
- [ [`3fc6c`](https://github.com/thheller/shadow-cljs/commit/3fc6c191be56492915debba311bb216204eb5bab) ] change REPL impl to allow option :ns attribute (via nREPL)
- [ [`d77c8`](https://github.com/thheller/shadow-cljs/commit/d77c885dfb1c4b3d4753c5f5ce1fc643150ec70e) ] get rid of nashorn deprecation warning on jdk11
- [ [`4f20d`](https://github.com/thheller/shadow-cljs/commit/4f20d599280c68b55ce3d5577fad1eeeade3fd0a) ] remove unused nrepl refers
- [ [`781e4`](https://github.com/thheller/shadow-cljs/commit/781e49e4f4da84e24c4145fab85e2552de83db25) ] fix emacs temp file filtering on windows
- [ [`f0b36`](https://github.com/thheller/shadow-cljs/commit/f0b369576c81f1f617af1d9c75a15afc0f13a330) ] add missing launcher css
- [ [`e53cb`](https://github.com/thheller/shadow-cljs/commit/e53cb1123418ef1be870bb90fc8a77d54751affb) ] separate build script for win/mac
- [ [`be3e8`](https://github.com/thheller/shadow-cljs/commit/be3e86cfc89c6b6827b04a635a1e896702707971) ] fix file perms, resize icon for osx
- [ [`8aca2`](https://github.com/thheller/shadow-cljs/commit/8aca2e7b77a22c9b0a0f6a4dd98c11767701d194) ] build scripts
- [ [`8e024`](https://github.com/thheller/shadow-cljs/commit/8e0246ca40be206825b1fb3226be812544955131) ] [WIP] launcher packaging, ui tweaks
- [ [`70af5`](https://github.com/thheller/shadow-cljs/commit/70af5be48edfead2d65af72a857ce2c08e69a8d9) ] [WIP] launcher stuff
- [ [`de065`](https://github.com/thheller/shadow-cljs/commit/de065f237031f2e8233642c96b0030477ce2be30) ] update Closure Java API changes
- [ [`1b88a`](https://github.com/thheller/shadow-cljs/commit/1b88a93aefd0a6ec2a5186f4c585719095e3d803) ] bump deps
- [ [`7ec84`](https://github.com/thheller/shadow-cljs/commit/7ec8453965f8d61f95a17ac6bc638d0a73fd1648) ] fix npm-deps install failing on windows
- [ [`741a8`](https://github.com/thheller/shadow-cljs/commit/741a8fa908d4b35a9a559b8c1709bf7321bcd327) ] fix proc.kill on windows
- [ [`cb04d`](https://github.com/thheller/shadow-cljs/commit/cb04d78ed9cf4a4a109d802150b66950fabbe689) ] [WIP] launcher basic process management, kill doesn't work
- [ [`52292`](https://github.com/thheller/shadow-cljs/commit/522929d5e1ae5f062958c8b219c7d9be45d32c4e) ] make module :preloads dev-only too
- [ [`48c55`](https://github.com/thheller/shadow-cljs/commit/48c55dce0d1086b79d189230795668d6c848126b) ] prevent name collision on PrintWriter-on with clj 1.10 alphas
- [ [`d9f74`](https://github.com/thheller/shadow-cljs/commit/d9f746841ecc11a1ad21aa2468cfa960d3a38b04) ] allow specifying :preloads per :modules entry
- [ [`ad4a3`](https://github.com/thheller/shadow-cljs/commit/ad4a3c4a979f0b2a2d1fb73ef2b471f0574209f8) ] [WIP] first few bits of the electron launcher UI

## [2.6.10](https://github.com/thheller/shadow-cljs/compare/a56205f63aff5f871c98ec007a12417e5550a4e8...f321b390d52b69bf89e4568cf096a8d51e04575c) - 2018-09-30
- [ [`f321b`](https://github.com/thheller/shadow-cljs/commit/f321b390d52b69bf89e4568cf096a8d51e04575c) ] fix loading race condition
- [ [`1f44c`](https://github.com/thheller/shadow-cljs/commit/1f44c603a7da9ca13e2189ab5e9e7822c6f64562) ] bump fulcro
- [ [`a5620`](https://github.com/thheller/shadow-cljs/commit/a56205f63aff5f871c98ec007a12417e5550a4e8) ] prep for upcoming analyzer :var changes

## [2.6.9](https://github.com/thheller/shadow-cljs/compare/105d3771a63a47d6a585883d28eb899e93752de8...2ce6fcc6a2781269d8c9fcdef28e9046273d1aea) - 2018-09-20
- [ [`2ce6f`](https://github.com/thheller/shadow-cljs/commit/2ce6fcc6a2781269d8c9fcdef28e9046273d1aea) ] [WIP] UI REPL
- [ [`a61ee`](https://github.com/thheller/shadow-cljs/commit/a61eea6d7f74fb7d0d806bc030442cf554ab5a24) ] fix :js-options :resolve package to false
- [ [`f6694`](https://github.com/thheller/shadow-cljs/commit/f6694aaa5459591556a5e83f939885b70924d3b0) ] REPL should make no assumptions about the namespaces loaded by the runtime
- [ [`fbac4`](https://github.com/thheller/shadow-cljs/commit/fbac46abfcae0c0d1aea83c0c5f149e036abc52a) ] [WIP] minimal chrome extension for UI
- [ [`105d3`](https://github.com/thheller/shadow-cljs/commit/105d3771a63a47d6a585883d28eb899e93752de8) ] ensure that babel-worker.js is updated properly

## [2.6.8](https://github.com/thheller/shadow-cljs/compare/0e7ac521c6d7bf3eeb4b06a5502e00a1c971fb5b...f09237f7146e100dccf385f73a7b902b325641c0) - 2018-09-15
- [ [`f0923`](https://github.com/thheller/shadow-cljs/commit/f09237f7146e100dccf385f73a7b902b325641c0) ] update resolve-var hacks in prep for next cljs release
- [ [`0e7ac`](https://github.com/thheller/shadow-cljs/commit/0e7ac521c6d7bf3eeb4b06a5502e00a1c971fb5b) ] format warnings a little bit more, add proper favicon

## [2.6.7](https://github.com/thheller/shadow-cljs/compare/f0dda3e927cd2790a63bfd70d96d2c7843e4a6fe...2234802edb9208ff663e9b2253f5afd53901f171) - 2018-09-11
- [ [`22348`](https://github.com/thheller/shadow-cljs/commit/2234802edb9208ff663e9b2253f5afd53901f171) ] remove redundant wrapping component
- [ [`99741`](https://github.com/thheller/shadow-cljs/commit/99741e3edd07ef8ba8a20e5fc3e2e0cad14051ad) ] add support for :parallel-build false (no threads)
- [ [`532fb`](https://github.com/thheller/shadow-cljs/commit/532fb53be526b9dcdb4cb105b3f8d69100a29db8) ] [WIP] UI hooking up more features
- [ [`30e18`](https://github.com/thheller/shadow-cljs/commit/30e1844ca61a8716c803a16e7deb1e59273904d2) ] minor style tweaks
- [ [`8c9b3`](https://github.com/thheller/shadow-cljs/commit/8c9b391a3a90c67b6e34b5fac3fe9a5a4ce2ba3f) ] add local roboto font
- [ [`88d53`](https://github.com/thheller/shadow-cljs/commit/88d533b36b2d020dcf167ed11451955c3991a445) ] [WIP] UI starting to get somewhat usable
- [ [`0416e`](https://github.com/thheller/shadow-cljs/commit/0416ea27e9a031c4a39c49df820855aa4b72575c) ] port latest core.specs.alpha
- [ [`f0dda`](https://github.com/thheller/shadow-cljs/commit/f0dda3e927cd2790a63bfd70d96d2c7843e4a6fe) ] [WIP] big UI restructure

## [2.6.6](https://github.com/thheller/shadow-cljs/compare/...) - 2018-08-31

## [2.6.5](https://github.com/thheller/shadow-cljs/compare/daf9e3bd2ff4d8ffee36354cbc84012333482fd9...3d9d81af25bdce85d122930a3fea0a823521defd) - 2018-08-31
- [ [`3d9d8`](https://github.com/thheller/shadow-cljs/commit/3d9d81af25bdce85d122930a3fea0a823521defd) ] [WIP] UI work
- [ [`8a893`](https://github.com/thheller/shadow-cljs/commit/8a89341cb384d5dc49033a0f1668a6e41dca3546) ] use a volatile to track par-compile progress
- [ [`41292`](https://github.com/thheller/shadow-cljs/commit/4129249da938a7069b2564dc9868ee67af066aef) ] fix watch with autobuild disabled issue
- [ [`87392`](https://github.com/thheller/shadow-cljs/commit/873924ff10996ad2a05b2a08ad6f7edb30d9c18f) ] don't filter clojure-future-spec because of the clojure.future ns
- [ [`fb9e0`](https://github.com/thheller/shadow-cljs/commit/fb9e0c3adb22c6352da10fb4bacc3e49226ac24f) ] can't figure this out
- [ [`69805`](https://github.com/thheller/shadow-cljs/commit/6980541aae0656dc154180f2b4e46735adbfa6d5) ] try cleaning up the keyword alias madness
- [ [`d59b9`](https://github.com/thheller/shadow-cljs/commit/d59b91d5dbc83a1b2b9147a3f55d3fd38077f099) ] only use absolute paths in npm, not replacing links
- [ [`5bcb8`](https://github.com/thheller/shadow-cljs/commit/5bcb83f70a26a9ccb7d797e66a1c9052a5b27dbb) ] 2.6.4
- [ [`daf9e`](https://github.com/thheller/shadow-cljs/commit/daf9e3bd2ff4d8ffee36354cbc84012333482fd9) ] first basic UI setup

## [2.6.3](https://github.com/thheller/shadow-cljs/compare/26cbb11c1f9848bda7a81546b18065b9dd34bc84...17309326ab440b86618294b31f468f6c40438800) - 2018-08-24
- [ [`17309`](https://github.com/thheller/shadow-cljs/commit/17309326ab440b86618294b31f468f6c40438800) ] fix electron css reloading
- [ [`bcf1f`](https://github.com/thheller/shadow-cljs/commit/bcf1f3acad88e94f7eaeaffe7ccec42c2320485b) ] missing files
- [ [`179f5`](https://github.com/thheller/shadow-cljs/commit/179f5910d3658bc23f85a689dce820453a0bb521) ] add built-in browser-test/workspaces build pages
- [ [`c1f8b`](https://github.com/thheller/shadow-cljs/commit/c1f8b0d84318832a0c1c5c2d761d133b48975209) ] removed unused dep
- [ [`bb850`](https://github.com/thheller/shadow-cljs/commit/bb8507d22cb53d7fe0ac79b6aa792702a337ee81) ] fix last minute var rename
- [ [`82081`](https://github.com/thheller/shadow-cljs/commit/82081091d3f5613ec5c0de3e67080feb8ab579da) ] first draft of pathom driven graph API endpoint
- [ [`51f5c`](https://github.com/thheller/shadow-cljs/commit/51f5cda73db5dae78f83a58f3052d1328bc157ef) ] temp fix for exists? code gen issue
- [ [`f64af`](https://github.com/thheller/shadow-cljs/commit/f64aff50e76e82732b838117d9ff17416db829cc) ] stricter ns parsing for :require-macros
- [ [`1e9c3`](https://github.com/thheller/shadow-cljs/commit/1e9c3ef91a11da6f2ff88d227bf525094cc03f7e) ] new command-aware CLI parsing
- [ [`b56e2`](https://github.com/thheller/shadow-cljs/commit/b56e293faaf1d6233d75c86fda68b8b803c217bb) ] more tweaks
- [ [`98e5c`](https://github.com/thheller/shadow-cljs/commit/98e5cf3354c98a39d7ad7d90da888af5cd6101aa) ] tweak deps tree output
- [ [`ff898`](https://github.com/thheller/shadow-cljs/commit/ff8987c5a91c5d4f73dbd0f5f534292ab72a0e87) ] add dependency graph printer
- [ [`bfac7`](https://github.com/thheller/shadow-cljs/commit/bfac7e11ac824437d04b651a52fca6aa9624b268) ] ignore more deps that are known to cause problems
- [ [`dc00d`](https://github.com/thheller/shadow-cljs/commit/dc00dfdaaf96a95bd0f24e93c66f138db12bcbbc) ] add additional s3p fix, print stacktrace on errors
- [ [`26cbb`](https://github.com/thheller/shadow-cljs/commit/26cbb11c1f9848bda7a81546b18065b9dd34bc84) ] try adding support for s3-wagon-private

## [2.6.2](https://github.com/thheller/shadow-cljs/compare/8cca2cb30aabace50d59edcbaac13ccb50050abb...016c0c87241ba8bb3a5d753774c052c8661ac8ba) - 2018-08-20
- [ [`016c0`](https://github.com/thheller/shadow-cljs/commit/016c0c87241ba8bb3a5d753774c052c8661ac8ba) ] best effort nrepl cleanup on worker exit
- [ [`da966`](https://github.com/thheller/shadow-cljs/commit/da96615ef785c8fbee3dfb4e4699987610ebc585) ] also enable :preloads in :karma and :browser-test
- [ [`3c521`](https://github.com/thheller/shadow-cljs/commit/3c52152827b6f15135933f91f8b420a1c76b5bda) ] fix :preloads injection in :node-test
- [ [`fc3e7`](https://github.com/thheller/shadow-cljs/commit/fc3e7458442d87bc6d4ab917658efa7b0c6c72f2) ] fix js-invalid-requires log missing resource-name
- [ [`8cca2`](https://github.com/thheller/shadow-cljs/commit/8cca2cb30aabace50d59edcbaac13ccb50050abb) ] fix create-cljs-project windows issue

## [2.6.1](https://github.com/thheller/shadow-cljs/compare/bfb29ad9b85a5a6393b9834fa36def821d1365d1...6a6a95d82376912c456496ebbbfbfc8fdb6becdc) - 2018-08-19
- [ [`6a6a9`](https://github.com/thheller/shadow-cljs/commit/6a6a95d82376912c456496ebbbfbfc8fdb6becdc) ] delay installing babel until its actually required
- [ [`97d25`](https://github.com/thheller/shadow-cljs/commit/97d25b8a6c904c98bb81cec1db490da92b69acd7) ] add initial project template script
- [ [`46011`](https://github.com/thheller/shadow-cljs/commit/460115397dc881c2a0c5c4097fcfcfe051b82358) ] fix worker using wrong fs-watch namespace
- [ [`bfb29`](https://github.com/thheller/shadow-cljs/commit/bfb29ad9b85a5a6393b9834fa36def821d1365d1) ] support :preloads in :node-test target

## [2.6.0](https://github.com/thheller/shadow-cljs/compare/19cc6402adda5342931ca292e7a7e097719e9798...ed0fae78be27091eaa56b1c707b9b5ee3f40dac3) - 2018-08-17
- [ [`ed0fa`](https://github.com/thheller/shadow-cljs/commit/ed0fae78be27091eaa56b1c707b9b5ee3f40dac3) ] fix CI build
- [ [`c0ede`](https://github.com/thheller/shadow-cljs/commit/c0edec82cbbf03f3eaa43608e94fa5ce6b4a9e6d) ] revert launcher change to 2.4.33
- [ [`53bfd`](https://github.com/thheller/shadow-cljs/commit/53bfd3f36b8f6623fbbc4dcfa0ef7966e688d393) ] fix closure variable naming issue
- [ [`c257f`](https://github.com/thheller/shadow-cljs/commit/c257ff6d2ec29942b9350e5bdb51e67cc1f9cdcc) ] reworks cljs-hacks to be more AOT friendly
- [ [`586e0`](https://github.com/thheller/shadow-cljs/commit/586e08ac66c087d7ddd3f6e8be8f243f860da2e7) ] fix node-repl getting unusable after node process exit
- [ [`779cb`](https://github.com/thheller/shadow-cljs/commit/779cb62e40ecc9074284b035fcbfe96582463760) ] fix node-repl issue related to recent relative path change
- [ [`2f4a0`](https://github.com/thheller/shadow-cljs/commit/2f4a0eaf2dbfcd5c073176f13917d89b30c5140d) ] drop CLJS slim classifier due to issues in lein
- [ [`19cc6`](https://github.com/thheller/shadow-cljs/commit/19cc6402adda5342931ca292e7a7e097719e9798) ] fix bad merge related to reload-flags in require and ns

## [2.5.1, launcher 2.1.0](https://github.com/thheller/shadow-cljs/compare/7c0ef1042c9d392b28064fd30f00ba665a35c4dc...7c0ef1042c9d392b28064fd30f00ba665a35c4dc) - 2018-08-14
- [ [`7c0ef`](https://github.com/thheller/shadow-cljs/commit/7c0ef1042c9d392b28064fd30f00ba665a35c4dc) ] basic support for reloading deps, adding deps without restart.

## [2.5.0](https://github.com/thheller/shadow-cljs/compare/71403a6578e502f9b3811036692cf79b8c271aa3...a55c653931eaea375346a901d7f2131a8063f7ae) - 2018-08-12
- [ [`a55c6`](https://github.com/thheller/shadow-cljs/commit/a55c653931eaea375346a901d7f2131a8063f7ae) ] fix launcher location, file encoding, typo.
- [ [`f310f`](https://github.com/thheller/shadow-cljs/commit/f310f5f7126c806fc97e993fa5e84351e4458163) ] launcher rework
- [ [`86dfd`](https://github.com/thheller/shadow-cljs/commit/86dfd91e6cad77488381706f3742ffe42de079a0) ] fix CI config?
- [ [`c01f4`](https://github.com/thheller/shadow-cljs/commit/c01f4287de7a4c25e0b3b6faab8cc8d3264aff86) ] remove shadow-cljs-jar package dependency
- [ [`71403`](https://github.com/thheller/shadow-cljs/commit/71403a6578e502f9b3811036692cf79b8c271aa3) ] use relative paths in :node-script dev builds

## [2.4.33](https://github.com/thheller/shadow-cljs/compare/ba67074ffb8e300ce9bd66eeedb8557fb9b74313...e9bd399ab82f9ac826f880ab51a50f67bac5783f) - 2018-08-06
- [ [`e9bd3`](https://github.com/thheller/shadow-cljs/commit/e9bd399ab82f9ac826f880ab51a50f67bac5783f) ] revert java.classpath removal, required for boot to work
- [ [`ba670`](https://github.com/thheller/shadow-cljs/commit/ba67074ffb8e300ce9bd66eeedb8557fb9b74313) ] cleanup some warnings getting printed to the wrong places

## [2.4.32](https://github.com/thheller/shadow-cljs/compare/1fb0b087a2051645ed6e7e9cfe179c51a67c9052...6282b0694a90992679c34ba0c2433c4a95b84c49) - 2018-08-06
- [ [`6282b`](https://github.com/thheller/shadow-cljs/commit/6282b0694a90992679c34ba0c2433c4a95b84c49) ] oops didn't mean to disable this
- [ [`1fb0b`](https://github.com/thheller/shadow-cljs/commit/1fb0b087a2051645ed6e7e9cfe179c51a67c9052) ] replace clojure.tools.logging with simplified logger

## [2.4.31](https://github.com/thheller/shadow-cljs/compare/95221a9fd4c45a7a179ff80e139aa6c33a0658e8...95221a9fd4c45a7a179ff80e139aa6c33a0658e8) - 2018-08-06
- [ [`95221`](https://github.com/thheller/shadow-cljs/commit/95221a9fd4c45a7a179ff80e139aa6c33a0658e8) ] fix nrepl port getting lost

## [2.4.30](https://github.com/thheller/shadow-cljs/compare/cb5eda785f4c86111b7fc6418f4dfa72a11c346a...cb5eda785f4c86111b7fc6418f4dfa72a11c346a) - 2018-08-05
- [ [`cb5ed`](https://github.com/thheller/shadow-cljs/commit/cb5eda785f4c86111b7fc6418f4dfa72a11c346a) ] fix bad ns reference and file encoding

## [2.4.29](https://github.com/thheller/shadow-cljs/compare/6435fc72935c9145bacabe1ec7138c7027829105...6435fc72935c9145bacabe1ec7138c7027829105) - 2018-08-05
- [ [`6435f`](https://github.com/thheller/shadow-cljs/commit/6435fc72935c9145bacabe1ec7138c7027829105) ] revert 2.4.25 nrepl changes and try different appraoch

## [2.4.28](https://github.com/thheller/shadow-cljs/compare/...) - 2018-08-04

## [2.4.27](https://github.com/thheller/shadow-cljs/compare/62313315188a8c9b65a99049e8b5f640f6bdb82d...190c80037970bbf289ff8d6da65370129d2346ba) - 2018-08-04
- [ [`190c8`](https://github.com/thheller/shadow-cljs/commit/190c80037970bbf289ff8d6da65370129d2346ba) ] create an AOT compiled artifact for the standalone version
- [ [`b49a8`](https://github.com/thheller/shadow-cljs/commit/b49a80365acb95fdf10d57535036bf434897144c) ] Use newer Buffer API instead of deprecated one (#359)
- [ [`0a3f6`](https://github.com/thheller/shadow-cljs/commit/0a3f6f7d71a19f81d2a5d7fb677c2fc791cb2620) ] bump closure, remove deprecated AMD transform option
- [ [`5b91d`](https://github.com/thheller/shadow-cljs/commit/5b91df93885f52d6dcc3ed8aad429b86149e12d8) ] fix load-file issue on windows, can't just (str ... "/") there
- [ [`62313`](https://github.com/thheller/shadow-cljs/commit/62313315188a8c9b65a99049e8b5f640f6bdb82d) ] fix outdated cursive-repl helper

## [2.4.26](https://github.com/thheller/shadow-cljs/compare/98fb3d2fb9475f4f1da5aa42f96074a9fb722868...98fb3d2fb9475f4f1da5aa42f96074a9fb722868) - 2018-07-31
- [ [`98fb3`](https://github.com/thheller/shadow-cljs/commit/98fb3d2fb9475f4f1da5aa42f96074a9fb722868) ] fix for cider-nrepl not using nrepl 0.4+ yet

## [2.4.25](https://github.com/thheller/shadow-cljs/compare/fa546ee477e73ed7e04694d85649a5206ca64917...f4a78924ca9097fbb7b2227b017a652a48669008) - 2018-07-30
- [ [`f4a78`](https://github.com/thheller/shadow-cljs/commit/f4a78924ca9097fbb7b2227b017a652a48669008) ] change all shadow$loader accesses just in case
- [ [`c718e`](https://github.com/thheller/shadow-cljs/commit/c718e4a756e5a645dea4677b2e6a8708185f5756) ] safer access to shadow$loader in case it doesn't exist
- [ [`fa546`](https://github.com/thheller/shadow-cljs/commit/fa546ee477e73ed7e04694d85649a5206ca64917) ] nrepl 0.4+ support (hopefully keeping nrepl 0.2.x support working)

## [2.4.24](https://github.com/thheller/shadow-cljs/compare/a6a7040d2d47e298d0438dc729685126531e339b...98e396101c7d256a6025828d285b7ce74feefa0e) - 2018-07-23
- [ [`98e39`](https://github.com/thheller/shadow-cljs/commit/98e396101c7d256a6025828d285b7ce74feefa0e) ] catch errors during evalLoad so it behaves like script tags
- [ [`a6a70`](https://github.com/thheller/shadow-cljs/commit/a6a7040d2d47e298d0438dc729685126531e339b) ] make devtools ws connect async and catch errors during setup

## [2.4.23](https://github.com/thheller/shadow-cljs/compare/f7d907f696aed984b16c9d45f1b97004e5bcf47f...9afd1c754871418d60f269ecc2d3111889331a50) - 2018-07-23
- [ [`9afd1`](https://github.com/thheller/shadow-cljs/commit/9afd1c754871418d60f269ecc2d3111889331a50) ] add experimental :loader-mode :eval
- [ [`f7d90`](https://github.com/thheller/shadow-cljs/commit/f7d907f696aed984b16c9d45f1b97004e5bcf47f) ] better error when browse-url fails in browser-repl

## [2.4.22](https://github.com/thheller/shadow-cljs/compare/a7015cf64bf1619228917b90a132dc05a38d0129...a7015cf64bf1619228917b90a132dc05a38d0129) - 2018-07-17
- [ [`a7015`](https://github.com/thheller/shadow-cljs/commit/a7015cf64bf1619228917b90a132dc05a38d0129) ] remove defrecord hack

## [2.4.21](https://github.com/thheller/shadow-cljs/compare/c75f739a1515f2ab3c91d3ea36f3a378750b3e6a...02d9ff76995962f4730eee82194a3c50d9116fd9) - 2018-07-17
- [ [`02d9f`](https://github.com/thheller/shadow-cljs/commit/02d9ff76995962f4730eee82194a3c50d9116fd9) ] fix live-reload issue when using :npm-module + browser runtime
- [ [`24c64`](https://github.com/thheller/shadow-cljs/commit/24c6416a1329184553185f13f0c3bc23262af1df) ] support reloading css which was loaded via a relative path
- [ [`c75f7`](https://github.com/thheller/shadow-cljs/commit/c75f739a1515f2ab3c91d3ea36f3a378750b3e6a) ] bump a few deps

## [2.4.20](https://github.com/thheller/shadow-cljs/compare/2d59e4fefb762ffa629ad29d99695ec84206b33e...968edf61e0bef372321ed87c62ea8c3015b94680) - 2018-07-11
- [ [`968ed`](https://github.com/thheller/shadow-cljs/commit/968edf61e0bef372321ed87c62ea8c3015b94680) ] fix cache issue where it would grow over time
- [ [`2d59e`](https://github.com/thheller/shadow-cljs/commit/2d59e4fefb762ffa629ad29d99695ec84206b33e) ] minor tweaks to repl web ui + updated deps

## [2.4.19](https://github.com/thheller/shadow-cljs/compare/d835229efe9ca1c2070bf643721873ef432613f1...d835229efe9ca1c2070bf643721873ef432613f1) - 2018-07-10
- [ [`d8352`](https://github.com/thheller/shadow-cljs/commit/d835229efe9ca1c2070bf643721873ef432613f1) ] add additional nrepl middleware to emulate

## [2.4.18](https://github.com/thheller/shadow-cljs/compare/53caa2ae6721f8353393416b267f0574bcb9f229...01bbbdd91d27e6d1f2a72acc7707918d32f42e74) - 2018-07-10
- [ [`01bbb`](https://github.com/thheller/shadow-cljs/commit/01bbbdd91d27e6d1f2a72acc7707918d32f42e74) ] emulate the new cider.piggieback ns
- [ [`6be0a`](https://github.com/thheller/shadow-cljs/commit/6be0ac17a16dc7ca790dd19655cb84d495865193) ] remove hack for CLJS-2385 since that was merged
- [ [`53caa`](https://github.com/thheller/shadow-cljs/commit/53caa2ae6721f8353393416b267f0574bcb9f229) ] fix process.browser not being correctly set for :browser in dev

## [2.4.17](https://github.com/thheller/shadow-cljs/compare/fad09ee1332210b58095dbc87262ecccc04b62f3...fad09ee1332210b58095dbc87262ecccc04b62f3) - 2018-07-08
- [ [`fad09`](https://github.com/thheller/shadow-cljs/commit/fad09ee1332210b58095dbc87262ecccc04b62f3) ] fix fedora linux classpath issue for jars that don't exist

## [2.4.16](https://github.com/thheller/shadow-cljs/compare/262de603267d53e4170389c8e7a6fb4411002e8e...262de603267d53e4170389c8e7a6fb4411002e8e) - 2018-07-05
- [ [`262de`](https://github.com/thheller/shadow-cljs/commit/262de603267d53e4170389c8e7a6fb4411002e8e) ] make closure polyfill handling more flexible and configurable

## [2.4.15](https://github.com/thheller/shadow-cljs/compare/24fe139dd3a7857f9c90076115138e655898e77c...5a8a9d1c1c8e9b2c35cb39497c031ae45331ac31) - 2018-07-04
- [ [`5a8a9`](https://github.com/thheller/shadow-cljs/commit/5a8a9d1c1c8e9b2c35cb39497c031ae45331ac31) ] split new node stuff into separate pass
- [ [`5d8c7`](https://github.com/thheller/shadow-cljs/commit/5d8c7afcc51d3a2d052d82734d684f252368174b) ] add gitignore exception for shadow.js package
- [ [`24fe1`](https://github.com/thheller/shadow-cljs/commit/24fe139dd3a7857f9c90076115138e655898e77c) ] rewrite Buffer/__filename/__dirname uses to match webpack/browserify

## [2.4.14](https://github.com/thheller/shadow-cljs/compare/4f333b545f7fb1ee9ee2c4080a0288f9ddaaa162...4f333b545f7fb1ee9ee2c4080a0288f9ddaaa162) - 2018-07-02
- [ [`4f333`](https://github.com/thheller/shadow-cljs/commit/4f333b545f7fb1ee9ee2c4080a0288f9ddaaa162) ] make :node-test :main configurable

## [2.4.13](https://github.com/thheller/shadow-cljs/compare/1c51bb88f40fa1d81abb23dba5595df59afe054d...37e29afece3ee5076ed71e0ca3aa7767bae68316) - 2018-07-01
- [ [`37e29`](https://github.com/thheller/shadow-cljs/commit/37e29afece3ee5076ed71e0ca3aa7767bae68316) ] fix :fn-deprecated warning causing OOM while printing
- [ [`1c51b`](https://github.com/thheller/shadow-cljs/commit/1c51bb88f40fa1d81abb23dba5595df59afe054d) ] fix generated sources creating unusable source maps

## [2.4.12](https://github.com/thheller/shadow-cljs/compare/f9342577888dc13f1c7112a176d04b5cf1b78af8...f9342577888dc13f1c7112a176d04b5cf1b78af8) - 2018-06-30
- [ [`f9342`](https://github.com/thheller/shadow-cljs/commit/f9342577888dc13f1c7112a176d04b5cf1b78af8) ] fix another resolve issue introduced in 2.4.8

## [2.4.11](https://github.com/thheller/shadow-cljs/compare/0e28c615cd75d4cbce27ede7a8f7f70785d42c86...dd1e389819bacbb7d2a5f2d93b7436dc347367e8) - 2018-06-28
- [ [`dd1e3`](https://github.com/thheller/shadow-cljs/commit/dd1e389819bacbb7d2a5f2d93b7436dc347367e8) ] fix another node resolve issue introduced in 2.4.9
- [ [`5bdad`](https://github.com/thheller/shadow-cljs/commit/5bdad85b19cdc642147f3eec8f22a3805578b5f6) ] fix another emacs print-length case
- [ [`e5a75`](https://github.com/thheller/shadow-cljs/commit/e5a7586183402ba350b0bc91b2e5e2e2fa99f7ba) ] fix race condition in :node-test with :autorun
- [ [`3ae57`](https://github.com/thheller/shadow-cljs/commit/3ae575ee9040617ab69a7d5d087c5ed4bc6d245c) ] add links to Learn Reagent course
- [ [`a5465`](https://github.com/thheller/shadow-cljs/commit/a54651428dd043d199b96ce739ce0f5577c00b59) ] fix broken watch* fn
- [ [`4820d`](https://github.com/thheller/shadow-cljs/commit/4820d06434167b6b3b3e8ba1bf73f2dd41738b93) ] oops, didn't mean to commit that
- [ [`0e28c`](https://github.com/thheller/shadow-cljs/commit/0e28c615cd75d4cbce27ede7a8f7f70785d42c86) ] make :source-paths in build config a hard error

## [2.4.10](https://github.com/thheller/shadow-cljs/compare/7e8af0fed3c493d6358b76074679994433ee18fc...7e8af0fed3c493d6358b76074679994433ee18fc) - 2018-06-25
- [ [`7e8af`](https://github.com/thheller/shadow-cljs/commit/7e8af0fed3c493d6358b76074679994433ee18fc) ] fix resolve issue where package.json main points to directory

## [2.4.9](https://github.com/thheller/shadow-cljs/compare/97f5f8a7e37615f499cd36ebcb195bb2ba7913a6...6b3bede25ddb874abc954c574095ef66966c76b1) - 2018-06-25
- [ [`6b3be`](https://github.com/thheller/shadow-cljs/commit/6b3bede25ddb874abc954c574095ef66966c76b1) ] adjust node resolve rules to properly support nested package.json
- [ [`bb1bc`](https://github.com/thheller/shadow-cljs/commit/bb1bc74917c6531e240f723013acb4ce96bf3f5d) ] adjust CLJS hacks for analyzer changes
- [ [`4b833`](https://github.com/thheller/shadow-cljs/commit/4b8335cc38ca1dc9b1e0e6ce04a7335f0a301cbc) ] bump CLJS + some deps
- [ [`97f5f`](https://github.com/thheller/shadow-cljs/commit/97f5f8a7e37615f499cd36ebcb195bb2ba7913a6) ] avoid race condition when writing externs.shadow.js

## [2.4.8](https://github.com/thheller/shadow-cljs/compare/e608b6af8453a23e768f3a196405e7316b96afde...b78c30d145665291c28204fb3fc28ea9d57679f0) - 2018-06-21
- [ [`b78c3`](https://github.com/thheller/shadow-cljs/commit/b78c30d145665291c28204fb3fc28ea9d57679f0) ] fix externs inference issue related to reserved names
- [ [`54331`](https://github.com/thheller/shadow-cljs/commit/54331d258005fa4045219344df79e9ba84dc5326) ] disable :variable-renaming in dev mode
- [ [`e608b`](https://github.com/thheller/shadow-cljs/commit/e608b6af8453a23e768f3a196405e7316b96afde) ] bump shadow-cljsjs

## [2.4.7](https://github.com/thheller/shadow-cljs/compare/55123ce376ef80f2b5f5a43f113dccb6c9a13569...55123ce376ef80f2b5f5a43f113dccb6c9a13569) - 2018-06-20
- [ [`55123`](https://github.com/thheller/shadow-cljs/commit/55123ce376ef80f2b5f5a43f113dccb6c9a13569) ] fix :refer referring too much

## [2.4.6](https://github.com/thheller/shadow-cljs/compare/a8ddf5215eb2833f86c343c550c79fac633ab322...632b4788076bfcfd8179b1cc7bd08a5c1d96870f) - 2018-06-20
- [ [`632b4`](https://github.com/thheller/shadow-cljs/commit/632b4788076bfcfd8179b1cc7bd08a5c1d96870f) ] fix :include-macros + :refer not recognizing macros
- [ [`3e54c`](https://github.com/thheller/shadow-cljs/commit/3e54c8d7138fde6dbbe0dc0765535f66f4bb0df3) ] fix externs inference for exists?
- [ [`5fad8`](https://github.com/thheller/shadow-cljs/commit/5fad8e88d3ff4056766c3370914ed3f78841e48a) ] temp fix for new exists? macro prepending js/
- [ [`5a7c6`](https://github.com/thheller/shadow-cljs/commit/5a7c6947278c926e225a6044c5d17e0a5d590544) ] revert startup message for nREPL since cider looks for it
- [ [`a8ddf`](https://github.com/thheller/shadow-cljs/commit/a8ddf5215eb2833f86c343c550c79fac633ab322) ] bump cljs to 1.10.312

## [2.4.5](https://github.com/thheller/shadow-cljs/compare/e07102bb50446f01332e7210b46f048526af48aa...8900d1b9add2a913262ac20501318d2fa6d8aba1) - 2018-06-15
- [ [`8900d`](https://github.com/thheller/shadow-cljs/commit/8900d1b9add2a913262ac20501318d2fa6d8aba1) ] remove HUD when reload fails
- [ [`36e9b`](https://github.com/thheller/shadow-cljs/commit/36e9bd62e66d1c748e09e5d30a33dd9f1fdba134) ] cleanup some old release-snapshot references
- [ [`f3302`](https://github.com/thheller/shadow-cljs/commit/f3302f46488089fa61362e888ccb6ec8e3974c69) ] fix JS require in REPL when using symbol
- [ [`0aee7`](https://github.com/thheller/shadow-cljs/commit/0aee73bc2caeec56f05ce591b8b5182188122fb1) ] rename release-snapshot, generate standalone html file, easier CLI
- [ [`cc386`](https://github.com/thheller/shadow-cljs/commit/cc386a72372ee72469a6a1f7270d4e54cab6279f) ] bump closure compiler
- [ [`55bb2`](https://github.com/thheller/shadow-cljs/commit/55bb27acf5b952adaa848c4bb8196f850e2fccc9) ] Avoid munging the :azure-app dir names (#316)
- [ [`e0710`](https://github.com/thheller/shadow-cljs/commit/e07102bb50446f01332e7210b46f048526af48aa) ] properly munge :node-library exports

## [2.4.4](https://github.com/thheller/shadow-cljs/compare/d3a28258d5bcd46b448202abc693888c1324aaf9...ee8a88ad5735e2d8b8e9a05db3f3f35951407c0b) - 2018-06-14
- [ [`ee8a8`](https://github.com/thheller/shadow-cljs/commit/ee8a88ad5735e2d8b8e9a05db3f3f35951407c0b) ] fix config reload that was accidentally broken recently
- [ [`af9f7`](https://github.com/thheller/shadow-cljs/commit/af9f7424fcf8d3765d0b3b2d8331594bdabbe4c6) ] Make use :azure/scriptFile metadata in :azure-app (#310)
- [ [`00db8`](https://github.com/thheller/shadow-cljs/commit/00db8b6d186e44f0b21aa7d9443676a737c642f0) ] do not hardcode :advanced for :azure-app
- [ [`71200`](https://github.com/thheller/shadow-cljs/commit/71200320061430bece6d40f2faa5b7d9cfb139cb) ] enable REPL for azure-fn
- [ [`d3a28`](https://github.com/thheller/shadow-cljs/commit/d3a28258d5bcd46b448202abc693888c1324aaf9) ] properly munge azure fn names

## [2.4.3](https://github.com/thheller/shadow-cljs/compare/e57847a6679af84c866bff1767e637a3a690967a...6f0df0510455d4c6fce0b4c59f4ace60734af160) - 2018-06-13
- [ [`6f0df`](https://github.com/thheller/shadow-cljs/commit/6f0df0510455d4c6fce0b4c59f4ace60734af160) ] add missing io/make-parents call
- [ [`b9e9a`](https://github.com/thheller/shadow-cljs/commit/b9e9a1bcf54d09c897eda3ebf5b5a02cbf54c75c) ] fix azure-app target file encoding, fn-data cache, add metadata check
- [ [`e5784`](https://github.com/thheller/shadow-cljs/commit/e57847a6679af84c866bff1767e637a3a690967a) ] change :azure-fn to :azure-app, support :fn-map instead

## [2.4.2](https://github.com/thheller/shadow-cljs/compare/e3b01fb2b2eceedd41b6f9a12586eba6bff42eba...e3b01fb2b2eceedd41b6f9a12586eba6bff42eba) - 2018-06-13
- [ [`e3b01`](https://github.com/thheller/shadow-cljs/commit/e3b01fb2b2eceedd41b6f9a12586eba6bff42eba) ] fix css watch issue on windows and ../ paths in general

## [2.4.1](https://github.com/thheller/shadow-cljs/compare/7f3c7cadd9c78d9dd5e923c4d2fa795bd53d84b6...7f3c7cadd9c78d9dd5e923c4d2fa795bd53d84b6) - 2018-06-10
- [ [`7f3c7`](https://github.com/thheller/shadow-cljs/commit/7f3c7cadd9c78d9dd5e923c4d2fa795bd53d84b6) ] add :preloads support for :node-script and :node-library

## [2.4.0](https://github.com/thheller/shadow-cljs/compare/751b37c3958ab8e6b45a2ff886d09b5bcd359860...f1a74850b14f7ec8504361d6a33c5cc659c5becf) - 2018-06-09
- [ [`f1a74`](https://github.com/thheller/shadow-cljs/commit/f1a74850b14f7ec8504361d6a33c5cc659c5becf) ] remove test require
- [ [`c5dbc`](https://github.com/thheller/shadow-cljs/commit/c5dbc7bbd04bd2e9412fbfe620ca0ed13b144464) ] let node resolve the babel transform helper
- [ [`926a4`](https://github.com/thheller/shadow-cljs/commit/926a4daac7efb51f078d4d238b5c166551bb1eef) ] properly wait for executor shutdown
- [ [`75ad4`](https://github.com/thheller/shadow-cljs/commit/75ad427935fb2ae3d2c856c447c319a796f1972a) ] fix crash when trying to extract warnings excerpt for EOF error
- [ [`7dc79`](https://github.com/thheller/shadow-cljs/commit/7dc79c289b29ef6baf78e9045a58742de78fdf62) ] fix node-library config specs
- [ [`1f19a`](https://github.com/thheller/shadow-cljs/commit/1f19ae27a0531b4de937821d16a5227f4a22ddbe) ] fix node-library :exports-fn
- [ [`9158e`](https://github.com/thheller/shadow-cljs/commit/9158e8ea4b2c0b84bb80d2588d41fedbcdafc473) ] remove generated files
- [ [`b66a4`](https://github.com/thheller/shadow-cljs/commit/b66a45aaddb54286aaa1e77ba7c0b3842947e6e7) ] experimental :azure-fn target
- [ [`751b3`](https://github.com/thheller/shadow-cljs/commit/751b37c3958ab8e6b45a2ff886d09b5bcd359860) ] clean up :node-library config, :exports now only accepts a map

## [2.3.36](https://github.com/thheller/shadow-cljs/compare/4be1c66183a95dfd9205865ab97e46581bd1c3fc...c76e25cc27cd9144e99d6c43d5d69ce72d9b5c60) - 2018-06-07
- [ [`c76e2`](https://github.com/thheller/shadow-cljs/commit/c76e25cc27cd9144e99d6c43d5d69ce72d9b5c60) ] cleanup some config specs, validate :npm-module
- [ [`4be1c`](https://github.com/thheller/shadow-cljs/commit/4be1c66183a95dfd9205865ab97e46581bd1c3fc) ] move back to localhost default

## [2.3.35](https://github.com/thheller/shadow-cljs/compare/cd64f98e6378a99c8903ee6e49ab35735f05bbce...8757462a4681a5a060257e7eadcc5672a2aa457e) - 2018-06-05
- [ [`87574`](https://github.com/thheller/shadow-cljs/commit/8757462a4681a5a060257e7eadcc5672a2aa457e) ] make some compile tasks async
- [ [`cd64f`](https://github.com/thheller/shadow-cljs/commit/cd64f98e6378a99c8903ee6e49ab35735f05bbce) ] skip cache read in watch when files were compiled previously

## [2.3.34](https://github.com/thheller/shadow-cljs/compare/bdab9f7c120c1286629c8c2c91b5df3942a52b61...bdab9f7c120c1286629c8c2c91b5df3942a52b61) - 2018-06-05
- [ [`bdab9`](https://github.com/thheller/shadow-cljs/commit/bdab9f7c120c1286629c8c2c91b5df3942a52b61) ] better warnings for filenames with - instead of _

## [2.3.33](https://github.com/thheller/shadow-cljs/compare/c0db51af694a9de897efa57714aca1d1088e52d3...61235972d39f1d887c19a565f066413907a8d4fa) - 2018-06-04
- [ [`61235`](https://github.com/thheller/shadow-cljs/commit/61235972d39f1d887c19a565f066413907a8d4fa) ] do not display warnings from jar files in the hud
- [ [`c0db5`](https://github.com/thheller/shadow-cljs/commit/c0db51af694a9de897efa57714aca1d1088e52d3) ] Compact warning so it doesn't fill the user screen (#289)

## [2.3.32](https://github.com/thheller/shadow-cljs/compare/2d1d9ef926381ab3ef298d97d66bb08d84f1ffcd...2d1d9ef926381ab3ef298d97d66bb08d84f1ffcd) - 2018-05-31
- [ [`2d1d9`](https://github.com/thheller/shadow-cljs/commit/2d1d9ef926381ab3ef298d97d66bb08d84f1ffcd) ] ensure JS errors show up correctly (inside the build process)

## [2.3.31](https://github.com/thheller/shadow-cljs/compare/8c3c025b3f55147b0972f405a38c058ebdc59154...c370eb4352f68cbcbf628a5705e3560166012c5a) - 2018-05-31
- [ [`c370e`](https://github.com/thheller/shadow-cljs/commit/c370eb4352f68cbcbf628a5705e3560166012c5a) ] support [:nrepl :init-ns] as well as [:repl :init-ns]
- [ [`dda8a`](https://github.com/thheller/shadow-cljs/commit/dda8ac71ee9335df2f83a26175c992c93012d7ce) ] builds that have external conf should recompile when changed
- [ [`8c3c0`](https://github.com/thheller/shadow-cljs/commit/8c3c025b3f55147b0972f405a38c058ebdc59154) ] add L externs just in case someone uses leaflet

## [2.3.30](https://github.com/thheller/shadow-cljs/compare/7e2bf369f0ccc144a29f8dd0ce2481c5dcfbf1fa...6e2a79198f9a80439e4d485cc0860d77a5ea8917) - 2018-05-28
- [ [`6e2a7`](https://github.com/thheller/shadow-cljs/commit/6e2a79198f9a80439e4d485cc0860d77a5ea8917) ] support -d when using :lein or :deps runners
- [ [`1164e`](https://github.com/thheller/shadow-cljs/commit/1164e5c3f9bcf0f2ad56dd1512b927324cab245b) ] add api/get-builds-ids accessor
- [ [`7e2bf`](https://github.com/thheller/shadow-cljs/commit/7e2bf369f0ccc144a29f8dd0ce2481c5dcfbf1fa) ] filter more network adapters

## [2.3.29](https://github.com/thheller/shadow-cljs/compare/a5b266efd8c042d3438ec2ba7c32cb4c17d73edb...a5b266efd8c042d3438ec2ba7c32cb4c17d73edb) - 2018-05-28
- [ [`a5b26`](https://github.com/thheller/shadow-cljs/commit/a5b266efd8c042d3438ec2ba7c32cb4c17d73edb) ] remove nrepl :greeting-fn, doesn't do anything useful anyways

## [2.3.28](https://github.com/thheller/shadow-cljs/compare/093d682bc21862c0b70d21373ae6310be8a88b07...093d682bc21862c0b70d21373ae6310be8a88b07) - 2018-05-27
- [ [`093d6`](https://github.com/thheller/shadow-cljs/commit/093d682bc21862c0b70d21373ae6310be8a88b07) ] fix broken module-hash-names

## [2.3.27](https://github.com/thheller/shadow-cljs/compare/f27f51e61899cf46ac8de5af7a56247f0b364e70...f27f51e61899cf46ac8de5af7a56247f0b364e70) - 2018-05-27
- [ [`f27f5`](https://github.com/thheller/shadow-cljs/commit/f27f51e61899cf46ac8de5af7a56247f0b364e70) ] plugin and build-hooks support

## [2.3.26](https://github.com/thheller/shadow-cljs/compare/1296453fac85d4d1991936601a7942efdd045ca8...da6e9b22c4ade84071e02b556c37ae7ed1024c3f) - 2018-05-26
- [ [`da6e9`](https://github.com/thheller/shadow-cljs/commit/da6e9b22c4ade84071e02b556c37ae7ed1024c3f) ] also check peerDependencies
- [ [`8ef08`](https://github.com/thheller/shadow-cljs/commit/8ef081c34ded70cf2f6f176b7fa0509e391a595b) ] actually mention which dependency didn't match. doh.
- [ [`ea525`](https://github.com/thheller/shadow-cljs/commit/ea52533191899dd60226beb3d3cc4f37541cefea) ] not my day today, writing more bugs by the minute
- [ [`b1dcf`](https://github.com/thheller/shadow-cljs/commit/b1dcf351f7cf62b2031e11b51c7083489380e7e5) ] add basic version checking for npm
- [ [`fbed2`](https://github.com/thheller/shadow-cljs/commit/fbed28030bf24d822fb9a9a9cbaf2953fe0892d1) ] third time's the charm
- [ [`bcb19`](https://github.com/thheller/shadow-cljs/commit/bcb198460bbeac39b2848a4c5ca4959ad7a07885) ] properly fix stream loop
- [ [`82807`](https://github.com/thheller/shadow-cljs/commit/8280788a1e46bdcb59f3c3f64d2b066473cd380b) ] fix thread leak in new REPL code
- [ [`8bb0c`](https://github.com/thheller/shadow-cljs/commit/8bb0cf436a928a804e42ec094e77b7e774a5aac3) ] [WIP] add a very basic REPL ui
- [ [`6103f`](https://github.com/thheller/shadow-cljs/commit/6103fef5eedf4a14e2a9042e669e7b9cc2964b09) ] RN is still a mystery to me
- [ [`12964`](https://github.com/thheller/shadow-cljs/commit/1296453fac85d4d1991936601a7942efdd045ca8) ] allow chrome-ext outputs config in either config or manifest

## [2.3.25](https://github.com/thheller/shadow-cljs/compare/8b78485e02dfc8db3e5852e558421d02298d1371...8b78485e02dfc8db3e5852e558421d02298d1371) - 2018-05-25
- [ [`8b784`](https://github.com/thheller/shadow-cljs/commit/8b78485e02dfc8db3e5852e558421d02298d1371) ] change how shadow.loader is initialized so its always available

## [2.3.24](https://github.com/thheller/shadow-cljs/compare/9f23773e30c0d35314e434ab227f5e439c0ecbe2...7b229a875624279a3a200270e42d1e1084d2823f) - 2018-05-25
- [ [`7b229`](https://github.com/thheller/shadow-cljs/commit/7b229a875624279a3a200270e42d1e1084d2823f) ] fix reload callbacks for chrome ext content scripts
- [ [`a087d`](https://github.com/thheller/shadow-cljs/commit/a087d6846357e0870f2737044be015fef680e801) ] use an actual Thread in server-thread, without bindings
- [ [`c5663`](https://github.com/thheller/shadow-cljs/commit/c5663ae9e05725e917abc75796140ce457b7e2e8) ] fix cider pprint issue due to print-length
- [ [`b6bb7`](https://github.com/thheller/shadow-cljs/commit/b6bb74c6ff01be6b76a0d26db322acf817b67ab1) ] [WIP] :chrome-extension refactor, now supports more use cases
- [ [`b50e4`](https://github.com/thheller/shadow-cljs/commit/b50e477e5bc963107fbfa4abf6733f985e4d4c02) ] add links to shadow-proto-starter repo+blog post
- [ [`d8d2c`](https://github.com/thheller/shadow-cljs/commit/d8d2c9871395ca56681e1cc76b6d07680eb55984) ] add some unicode to test builds
- [ [`b3a57`](https://github.com/thheller/shadow-cljs/commit/b3a579388fd7635ca43d6fa4a0383eb3a3764d3b) ] separate package.json for UI build
- [ [`c78d5`](https://github.com/thheller/shadow-cljs/commit/c78d534809bc6e1a6e58ebb7ca080bfca7e4e91a) ] make :node-modules-dir configurable per build
- [ [`af90f`](https://github.com/thheller/shadow-cljs/commit/af90f9f45b1929018f6bf5cb4814122660055082) ] fix bad error for invalid ns forms
- [ [`73a50`](https://github.com/thheller/shadow-cljs/commit/73a504875448ab7952ce74c94c9ef7ac8928e1d3) ] fix invalid repl reference
- [ [`1784e`](https://github.com/thheller/shadow-cljs/commit/1784e6f26a3867d01733eecd57c58cec3c7c091a) ] add release support for chrome-exts, rename :entry to :shadow/entry
- [ [`bd462`](https://github.com/thheller/shadow-cljs/commit/bd46202ccbce66e36b249a1c0ada0a760e188586) ] more worker cleanup
- [ [`bb431`](https://github.com/thheller/shadow-cljs/commit/bb431f21c0f879252116677879509792dc52b78f) ] more worker naming cleanup
- [ [`91b01`](https://github.com/thheller/shadow-cljs/commit/91b013fae8f6583a32b36854f2eaf428dd710259) ] further worker cleanup
- [ [`de754`](https://github.com/thheller/shadow-cljs/commit/de754e12f081269e596042d380d7aa911cbd2712) ] better to remove the perf concern entirely
- [ [`f261b`](https://github.com/thheller/shadow-cljs/commit/f261bfe7838add2004a55b0514adc82e9d9b4359) ] minor perf tweak in worker recompile (about 2ms)
- [ [`02ec9`](https://github.com/thheller/shadow-cljs/commit/02ec9896a06f832c4c95574fb85b3fe4d1dabec8) ] cleanup worker impl, more accurate names for stuff
- [ [`614f1`](https://github.com/thheller/shadow-cljs/commit/614f1b36133ada941f180975e28c59286522a0e3) ] don't use document.hostname in chrome exts
- [ [`b20e6`](https://github.com/thheller/shadow-cljs/commit/b20e6273fbc1ff47b22b078ae875cfe2ce58688b) ] fix multiple watch starting sequentially instead of parallel
- [ [`6e368`](https://github.com/thheller/shadow-cljs/commit/6e3680500a8313ee03279ac15dfd5a6649e66dbc) ] fix repl host selection in browser
- [ [`f1df2`](https://github.com/thheller/shadow-cljs/commit/f1df2fc4a1ba8079f2789c06cc230e21a00345c2) ] better error message for invalid entry
- [ [`20891`](https://github.com/thheller/shadow-cljs/commit/208917cdff8aab1352dfa2840584de70b2bbf027) ] [WIP] better :chrome-extension concept
- [ [`a7596`](https://github.com/thheller/shadow-cljs/commit/a759672a95e48d603e7bd6577e40902baf9ffd02) ] fix worker boot file
- [ [`15bdd`](https://github.com/thheller/shadow-cljs/commit/15bddc4b23864ba62d36700894939c3932b2ef0f) ] [WIP] :chrome-extension now with source maps
- [ [`f1b2a`](https://github.com/thheller/shadow-cljs/commit/f1b2a00af1f6b72befa6fc48cb56491406542ed0) ] [WIP] :target :chrome-extension
- [ [`9f237`](https://github.com/thheller/shadow-cljs/commit/9f23773e30c0d35314e434ab227f5e439c0ecbe2) ] secret cookie header to secure websockets for UI later

## [2.3.23](https://github.com/thheller/shadow-cljs/compare/9b457c91da5c5bd4212d35c15387c929ddd4aad9...9fe0c322b1cfdc40158d952908dabbbeb032c63e) - 2018-05-17
- [ [`9fe0c`](https://github.com/thheller/shadow-cljs/commit/9fe0c322b1cfdc40158d952908dabbbeb032c63e) ] switch to goog.globalEval instead of scripts
- [ [`9b457`](https://github.com/thheller/shadow-cljs/commit/9b457c91da5c5bd4212d35c15387c929ddd4aad9) ] fix check, broken due to closure update

## [2.3.22](https://github.com/thheller/shadow-cljs/compare/ca880a11034f2e401050303e808ad02af15768e6...c20c9e5f3681ff3e41ad3c933994b78f6964728d) - 2018-05-16
- [ [`c20c9`](https://github.com/thheller/shadow-cljs/commit/c20c9e5f3681ff3e41ad3c933994b78f6964728d) ] forgot there were actual tests for resolve
- [ [`4b21d`](https://github.com/thheller/shadow-cljs/commit/4b21d0b7f056e99bc563973c150ef95d5e467297) ] make :resolve for for :require
- [ [`b4491`](https://github.com/thheller/shadow-cljs/commit/b4491df8f1ce7e21af35b50bdbf3496468898bcc) ] add :devtools {:repl-pprint true} option
- [ [`eb879`](https://github.com/thheller/shadow-cljs/commit/eb87961a7a38c3549cca39290c546b77994181c4) ] minor require cleanup
- [ [`5deb4`](https://github.com/thheller/shadow-cljs/commit/5deb4de8b27e4fe261665dbd96ef339f83d6c7bc) ] check if document.hostname is empty before using it
- [ [`434aa`](https://github.com/thheller/shadow-cljs/commit/434aad1e7fc07a0df19d01e0038e44e8d7d5d7d6) ] cleanup repeated node-repl output
- [ [`5ce12`](https://github.com/thheller/shadow-cljs/commit/5ce12f4b3f9e11c0b8c3dd7e597aaf4cd5696074) ] more generic run! error handling
- [ [`acea4`](https://github.com/thheller/shadow-cljs/commit/acea49a13306489a51ec88c088e9703e70569b69) ] bump closure
- [ [`927a4`](https://github.com/thheller/shadow-cljs/commit/927a4600bf345dbb4a049f6846ba8ffecdaf821b) ] proper error message when clojure,lein or java are not found
- [ [`ca880`](https://github.com/thheller/shadow-cljs/commit/ca880a11034f2e401050303e808ad02af15768e6) ] print server and node versions on startup

## [2.3.21](https://github.com/thheller/shadow-cljs/compare/5555444f5b4ef7c0aa596f52f68b3bf2fc5c55aa...b95386161133620f669e7b57d4c1106d14549f61) - 2018-05-09
- [ [`b9538`](https://github.com/thheller/shadow-cljs/commit/b95386161133620f669e7b57d4c1106d14549f61) ] allow custom cljs.user, make cljs repl init-ns configurable
- [ [`55554`](https://github.com/thheller/shadow-cljs/commit/5555444f5b4ef7c0aa596f52f68b3bf2fc5c55aa) ] make shadow.test.env/register-test return the test var

## [2.3.20](https://github.com/thheller/shadow-cljs/compare/a093c587f67d0e0c5a8d94d8faa99202084e5cb9...96edfaa14e487a98ff04896ed7fb77736c77c4fd) - 2018-05-07
- [ [`96edf`](https://github.com/thheller/shadow-cljs/commit/96edfaa14e487a98ff04896ed7fb77736c77c4fd) ] add basic support for :proxy-url in devtools
- [ [`ea82d`](https://github.com/thheller/shadow-cljs/commit/ea82d490cf5e9739dd8f96bae7f7dacbcb45b3bd) ] filter more assets types so they are not parsed as JS
- [ [`8862c`](https://github.com/thheller/shadow-cljs/commit/8862cca9d2aaa0130ac22ad4632ec5183b6d730a) ] support merging sets in the config merge
- [ [`a093c`](https://github.com/thheller/shadow-cljs/commit/a093c587f67d0e0c5a8d94d8faa99202084e5cb9) ] fix warning which should just be debug info

## [2.3.19](https://github.com/thheller/shadow-cljs/compare/67e0c75aaf608c15c5236ff3156f7716b7bff74f...88f0d027a69733bf450f9f800e5274b8b9b3c42b) - 2018-05-04
- [ [`88f0d`](https://github.com/thheller/shadow-cljs/commit/88f0d027a69733bf450f9f800e5274b8b9b3c42b) ] fix source map parsing issue for release-snapshot
- [ [`67e0c`](https://github.com/thheller/shadow-cljs/commit/67e0c75aaf608c15c5236ff3156f7716b7bff74f) ] don't use with-build-options to set :output-dir

## [2.3.18](https://github.com/thheller/shadow-cljs/compare/5d970d618a37e5b2ea2668d999f17e54fea37ccf...4e1bda0fe1ab832cb83f44ed1b6e62b7ff1b3a2e) - 2018-05-03
- [ [`4e1bd`](https://github.com/thheller/shadow-cljs/commit/4e1bda0fe1ab832cb83f44ed1b6e62b7ff1b3a2e) ] fix :module-hash-names computation not accounting for shadow-js
- [ [`3016b`](https://github.com/thheller/shadow-cljs/commit/3016b514a0aa82d02408288d87d2d313457bcd59) ] properly invalidate closure conversion caches on config change
- [ [`b5fa5`](https://github.com/thheller/shadow-cljs/commit/b5fa5dcd350848a7a4321179123c0afbc745a109) ] add :source-map-use-fs-paths true compiler option
- [ [`5d970`](https://github.com/thheller/shadow-cljs/commit/5d970d618a37e5b2ea2668d999f17e54fea37ccf) ] fix accidentally always enabling :rename-prefix-namespace

## [2.3.17](https://github.com/thheller/shadow-cljs/compare/f7cf6ff123fce4a5844824368b2c798f9f6cd6d2...f7cf6ff123fce4a5844824368b2c798f9f6cd6d2) - 2018-05-03
- [ [`f7cf6`](https://github.com/thheller/shadow-cljs/commit/f7cf6ff123fce4a5844824368b2c798f9f6cd6d2) ] fix shadow$provide again ...

## [2.3.16](https://github.com/thheller/shadow-cljs/compare/ae0b5dd6fb54c391370774f38d29fe0b137c6142...ae0b5dd6fb54c391370774f38d29fe0b137c6142) - 2018-05-01
- [ [`ae0b5`](https://github.com/thheller/shadow-cljs/commit/ae0b5dd6fb54c391370774f38d29fe0b137c6142) ] fix :expo test target

## [2.3.15](https://github.com/thheller/shadow-cljs/compare/10553565f796281ce5fdf1b569c0c7ea2f4dabe9...10553565f796281ce5fdf1b569c0c7ea2f4dabe9) - 2018-05-01
- [ [`10553`](https://github.com/thheller/shadow-cljs/commit/10553565f796281ce5fdf1b569c0c7ea2f4dabe9) ] fix cache error when cache data includes a regexp

## [2.3.14](https://github.com/thheller/shadow-cljs/compare/d7b2b26488df235c518038a986698f03f6207539...a5c7bd04c1a103a79178589d2044f428d4f924b3) - 2018-05-01
- [ [`a5c7b`](https://github.com/thheller/shadow-cljs/commit/a5c7bd04c1a103a79178589d2044f428d4f924b3) ] inject polyfills in node-library/node-script dev builds
- [ [`025ff`](https://github.com/thheller/shadow-cljs/commit/025ff9d4e8891e0c64621442c9a0828f34089e74) ] fix another shadow$provide issue
- [ [`d7b2b`](https://github.com/thheller/shadow-cljs/commit/d7b2b26488df235c518038a986698f03f6207539) ] didn't mean to commit this. breaks on CI.

## [2.3.13](https://github.com/thheller/shadow-cljs/compare/4582133f0171a1bff944f48edbf95be1be755d2d...b3bb3b6b618ae8498fbed3feed94731dd23d3555) - 2018-04-30
- [ [`b3bb3`](https://github.com/thheller/shadow-cljs/commit/b3bb3b6b618ae8498fbed3feed94731dd23d3555) ] fix incorrect or missing shadow$provide placement
- [ [`45821`](https://github.com/thheller/shadow-cljs/commit/4582133f0171a1bff944f48edbf95be1be755d2d) ] fix hud not going away when nothing was recompiled

## [2.3.12](https://github.com/thheller/shadow-cljs/compare/5b93cfee6d168e69ca7a8e083a9de65bdb9bb818...e09441fb20de646cc67aa587e7ebbd9dd55c7c16) - 2018-04-29
- [ [`e0944`](https://github.com/thheller/shadow-cljs/commit/e09441fb20de646cc67aa587e7ebbd9dd55c7c16) ] also add api fn to toggle autobuild on/off
- [ [`bf50a`](https://github.com/thheller/shadow-cljs/commit/bf50a4a7856d8b41dcb9e4859973fa0792ea9171) ] add api methods to trigger recompile of watch workers
- [ [`af5e3`](https://github.com/thheller/shadow-cljs/commit/af5e382466c872bb532be9b1ef98d006e4d8b2fe) ] use last-index-of to get file exts so css catches .min.css
- [ [`7dc90`](https://github.com/thheller/shadow-cljs/commit/7dc90cc2acdc188dca752cedc15df0c6e9eb80a4) ] fix :npm-module missing SHADOW_ENV
- [ [`ebc3a`](https://github.com/thheller/shadow-cljs/commit/ebc3ad8d2cd8ac018795258fc3676063cd205d7c) ] bump deps and add few type hints
- [ [`5b93c`](https://github.com/thheller/shadow-cljs/commit/5b93cfee6d168e69ca7a8e083a9de65bdb9bb818) ] bump shadow-cljsjs

## [2.3.11](https://github.com/thheller/shadow-cljs/compare/8ee74c5cf2d88f0236bd6d8be2ba1ceb98d04f3b...8ee74c5cf2d88f0236bd6d8be2ba1ceb98d04f3b) - 2018-04-27
- [ [`8ee74`](https://github.com/thheller/shadow-cljs/commit/8ee74c5cf2d88f0236bd6d8be2ba1ceb98d04f3b) ] remove the last remaining use of the goog debug loader

## [2.3.10](https://github.com/thheller/shadow-cljs/compare/db01b91b5b9d24c77debd4f08336cb788b97243c...db01b91b5b9d24c77debd4f08336cb788b97243c) - 2018-04-27
- [ [`db01b`](https://github.com/thheller/shadow-cljs/commit/db01b91b5b9d24c77debd4f08336cb788b97243c) ] remove nrepl-select checking if a client is connected

## [2.3.9](https://github.com/thheller/shadow-cljs/compare/cd15a5f7165d7dc55ee06e761dd165d14efce30d...71e36678989ef3844a314f9e836eb34168bd57a2) - 2018-04-27
- [ [`71e36`](https://github.com/thheller/shadow-cljs/commit/71e36678989ef3844a314f9e836eb34168bd57a2) ] make it possible to disable :output-wrapper
- [ [`0ad1c`](https://github.com/thheller/shadow-cljs/commit/0ad1ca39700206894b6dc43c8e1f89acce7c56d1) ] add support for :output-wrapper and :rename-prefix-namespace
- [ [`cd15a`](https://github.com/thheller/shadow-cljs/commit/cd15a5f7165d7dc55ee06e761dd165d14efce30d) ] fix code-split bug

## [2.3.8](https://github.com/thheller/shadow-cljs/compare/1e64cb5bd523fa27b43625b5600fb44c5111c45d...1e64cb5bd523fa27b43625b5600fb44c5111c45d) - 2018-04-26
- [ [`1e64c`](https://github.com/thheller/shadow-cljs/commit/1e64cb5bd523fa27b43625b5600fb44c5111c45d) ] fix reload-macros check, broken in 2.3.7

## [2.3.7](https://github.com/thheller/shadow-cljs/compare/d7945ea4b8339ee78e02eb6c850b6e413c0ec5ee...5f64cc1c001918c14ff4c5f88cfcd58d4afb6bfe) - 2018-04-26
- [ [`5f64c`](https://github.com/thheller/shadow-cljs/commit/5f64cc1c001918c14ff4c5f88cfcd58d4afb6bfe) ] fix file leak caused by URLConnection
- [ [`d7945`](https://github.com/thheller/shadow-cljs/commit/d7945ea4b8339ee78e02eb6c850b6e413c0ec5ee) ] add :devtools :ignore-warnings, defaults to false

## [2.3.6](https://github.com/thheller/shadow-cljs/compare/d7e9b605b6bd5ba1457a4dd739bd7119ae6d44c4...ab71155d2a43d6587c68f6adfc9ce4e51f3cd919) - 2018-04-24
- [ [`ab711`](https://github.com/thheller/shadow-cljs/commit/ab71155d2a43d6587c68f6adfc9ce4e51f3cd919) ] revamp release-snapshot UI, much better now.
- [ [`d7e9b`](https://github.com/thheller/shadow-cljs/commit/d7e9b605b6bd5ba1457a4dd739bd7119ae6d44c4) ] fix browser devtools reloading too many shadow-js files

## [2.3.5](https://github.com/thheller/shadow-cljs/compare/73eb8633a31be10a98c6ca136f23bca4d23ac12c...08045e836cd440c480a3cdb2f0faf634875d4973) - 2018-04-24
- [ [`08045`](https://github.com/thheller/shadow-cljs/commit/08045e836cd440c480a3cdb2f0faf634875d4973) ] reset analyzer data when recompiling files
- [ [`9d95c`](https://github.com/thheller/shadow-cljs/commit/9d95c7f37b97307f09ff9cab6f5cd5063ba3bbac) ] change default to not group jars
- [ [`73eb8`](https://github.com/thheller/shadow-cljs/commit/73eb8633a31be10a98c6ca136f23bca4d23ac12c) ] extract pom infos so they are available for release-snapshot

## [2.3.4](https://github.com/thheller/shadow-cljs/compare/cb47b6cf1a4cc67f4b5237e72e63e694315e5166...cb47b6cf1a4cc67f4b5237e72e63e694315e5166) - 2018-04-24
- [ [`cb47b`](https://github.com/thheller/shadow-cljs/commit/cb47b6cf1a4cc67f4b5237e72e63e694315e5166) ] make sure :reload-namespaces is never nil

## [2.3.3](https://github.com/thheller/shadow-cljs/compare/dc7b9c516f2367578ba0c521747d7f49c20b4c71...9f631f81b2800d0e9647c611179854d5ca43e6e0) - 2018-04-24
- [ [`9f631`](https://github.com/thheller/shadow-cljs/commit/9f631f81b2800d0e9647c611179854d5ca43e6e0) ] remove recently added dom-specific event stuff
- [ [`dc7b9`](https://github.com/thheller/shadow-cljs/commit/dc7b9c516f2367578ba0c521747d7f49c20b4c71) ] make IP detection optional

## [2.3.2](https://github.com/thheller/shadow-cljs/compare/9633a9127b259ffde2cee44d03f133041c87a69f...a0c762534b6b122484ac67f29e9972b366aeb1cc) - 2018-04-24
- [ [`a0c76`](https://github.com/thheller/shadow-cljs/commit/a0c762534b6b122484ac67f29e9972b366aeb1cc) ] add unload ns event so code can do something before a ns is reloaded
- [ [`ba795`](https://github.com/thheller/shadow-cljs/commit/ba7955813997a7a890ac61bb9a504a77f654db47) ] filter :repl/out :repl/err
- [ [`fb670`](https://github.com/thheller/shadow-cljs/commit/fb67013b7af770d29c2e851c88f0e3010240d40b) ] tweak release-snapshot table
- [ [`c7a74`](https://github.com/thheller/shadow-cljs/commit/c7a743ff565980c5af6afb6056b9ee840afc954e) ] Print bundle info table from release snapshot (#252)
- [ [`35b16`](https://github.com/thheller/shadow-cljs/commit/35b16dc76e042f68b59ef95d1a3f084bfdadf2c7) ] fix :classpath-excludes filtering
- [ [`9633a`](https://github.com/thheller/shadow-cljs/commit/9633a9127b259ffde2cee44d03f133041c87a69f) ] expose :package-name in release-snapshot data

## [2.3.1](https://github.com/thheller/shadow-cljs/compare/51ef32237297ce0a56517ec418c88dae70d69890...bb8111728e406976a943c81592350d3ee3ec52b9) - 2018-04-22
- [ [`bb811`](https://github.com/thheller/shadow-cljs/commit/bb8111728e406976a943c81592350d3ee3ec52b9) ] make it possible to toggle :variable-renaming and :property-renaming
- [ [`dab56`](https://github.com/thheller/shadow-cljs/commit/dab56fb0cdc74dee4254ff1b5e253b000fcea7ca) ] export provides/requires in release snapshot data
- [ [`7dbec`](https://github.com/thheller/shadow-cljs/commit/7dbec072fcb1f09091f9ef90430ab3a16fc72bfc) ] fix :js-properties getting replaced
- [ [`b8f8d`](https://github.com/thheller/shadow-cljs/commit/b8f8d31ba9ac931ab6ac69a1000d68c9a119752d) ] cleanup extern properties collector code a bit
- [ [`e56ec`](https://github.com/thheller/shadow-cljs/commit/e56ec0bcfaddc234bd82e767daf343fddd5377a0) ] hook up PropertyCollector to classpath JS
- [ [`37206`](https://github.com/thheller/shadow-cljs/commit/3720670906799888b9bd4af437fddae3f508da35) ] add CryptoTwittos link
- [ [`b1532`](https://github.com/thheller/shadow-cljs/commit/b15320685fe83896e809c76be3b5096b1358876b) ] small doc tweak
- [ [`4845d`](https://github.com/thheller/shadow-cljs/commit/4845d523b559905ef0d19e39193ceb3e248ee952) ] Initial draft of CONTRIBUTING.md (#253)
- [ [`f5add`](https://github.com/thheller/shadow-cljs/commit/f5add53fded9c641b6b1602e9353642718935f53) ] more detailed cache logging
- [ [`6a242`](https://github.com/thheller/shadow-cljs/commit/6a242befc3de24b79a5e805f951b3935cdf35cc7) ] remove leftover debug log message
- [ [`b8582`](https://github.com/thheller/shadow-cljs/commit/b85827b6c340e59cd435a3b414e1977ef8761f1c) ] allow disabling source map generating for shadow-js
- [ [`8df39`](https://github.com/thheller/shadow-cljs/commit/8df39533acd809b852eb6d37b80708f5072298f2) ] use :js-options directly as compiler opts for js conversion
- [ [`51ef3`](https://github.com/thheller/shadow-cljs/commit/51ef32237297ce0a56517ec418c88dae70d69890) ] add externs for Java.type call in cljs.core (for nashorn)

## [2.3.0](https://github.com/thheller/shadow-cljs/compare/537eb1067983a56f84fa1b7d8a70b6effa236c08...24408ed5e7bfe81a921fa53488f5a41a070484c4) - 2018-04-16
- [ [`24408`](https://github.com/thheller/shadow-cljs/commit/24408ed5e7bfe81a921fa53488f5a41a070484c4) ] no longer include cljsjs externs by default
- [ [`537eb`](https://github.com/thheller/shadow-cljs/commit/537eb1067983a56f84fa1b7d8a70b6effa236c08) ] fix invalid exports reference

## [2.2.32](https://github.com/thheller/shadow-cljs/compare/137df16d7efcb4637869957ea1d8ca3b00d65bcb...f266ca1d67647426ef9a645bfe859b351fdca8de) - 2018-04-16
- [ [`f266c`](https://github.com/thheller/shadow-cljs/commit/f266ca1d67647426ef9a645bfe859b351fdca8de) ] fix :browser-test target devtools
- [ [`137df`](https://github.com/thheller/shadow-cljs/commit/137df16d7efcb4637869957ea1d8ca3b00d65bcb) ] basic source map work, release support

## [2.2.31](https://github.com/thheller/shadow-cljs/compare/7991c693d4d789612023bf4bc20357912435bb90...74ff7520432425e9f107ec83218bd46424bc1e2e) - 2018-04-15
- [ [`74ff7`](https://github.com/thheller/shadow-cljs/commit/74ff7520432425e9f107ec83218bd46424bc1e2e) ] add logo in readme and emphasis javascript related features (#246)
- [ [`3bbaa`](https://github.com/thheller/shadow-cljs/commit/3bbaab0838cd0dec321f6f693218a6ba955c232a) ] [WIP] :expo target
- [ [`7991c`](https://github.com/thheller/shadow-cljs/commit/7991c693d4d789612023bf4bc20357912435bb90) ] add link to shadow-re-frame-simple-example (#245)

## [2.2.30](https://github.com/thheller/shadow-cljs/compare/5e6dae3ec9143560c76ac6ddd565dc08e63879c5...b6d4594bf50e6efa2a0d8ba3a21d31a04ef0238a) - 2018-04-13
- [ [`b6d45`](https://github.com/thheller/shadow-cljs/commit/b6d4594bf50e6efa2a0d8ba3a21d31a04ef0238a) ] add basic react-native target, doesn't quite work yet
- [ [`b08ec`](https://github.com/thheller/shadow-cljs/commit/b08ecd1f6a66f0afeee42704eb45d5b44ba8aa75) ] add warning when :http-handler could not be resolved
- [ [`7c6f2`](https://github.com/thheller/shadow-cljs/commit/7c6f20340df0e2ab60448052873f5dfb7c5efba0) ] remove manual hud injection from browser target as it is now always used
- [ [`5b176`](https://github.com/thheller/shadow-cljs/commit/5b176d9c9df98268f342f7c567a11538944017f0) ] better error when the npm shadow-cljs package is not installed
- [ [`db042`](https://github.com/thheller/shadow-cljs/commit/db04274d96c4886b3abe8aab6f9bdddb838b8048) ] forwards prints for the REPL
- [ [`5e6da`](https://github.com/thheller/shadow-cljs/commit/5e6dae3ec9143560c76ac6ddd565dc08e63879c5) ] rework watch behavior

## [2.2.29](https://github.com/thheller/shadow-cljs/compare/ea1032263612ff6e948e3598ba5e6a86bded8044...5cbe1889486260fe83b12b4561e0702ddeca65be) - 2018-04-11
- [ [`5cbe1`](https://github.com/thheller/shadow-cljs/commit/5cbe1889486260fe83b12b4561e0702ddeca65be) ] fix watch issue where paths could get registered twice
- [ [`4c9fc`](https://github.com/thheller/shadow-cljs/commit/4c9fcd99a83840907c84ae834d241b722a23970a) ] fix JS circular dependency issue happening in aws-sdk
- [ [`ea103`](https://github.com/thheller/shadow-cljs/commit/ea1032263612ff6e948e3598ba5e6a86bded8044) ] add support for meta hint to always force compile a ns

## [2.2.28](https://github.com/thheller/shadow-cljs/compare/36cbb4fc883a40aa7a894a2b38efedd8f2d18f23...36cbb4fc883a40aa7a894a2b38efedd8f2d18f23) - 2018-04-10
- [ [`36cbb`](https://github.com/thheller/shadow-cljs/commit/36cbb4fc883a40aa7a894a2b38efedd8f2d18f23) ] fix classpath issue when files in ~/.m2 get deleted

## [2.2.27](https://github.com/thheller/shadow-cljs/compare/503445a2eb3a47b073ff606bd191ad6441a19ab3...503445a2eb3a47b073ff606bd191ad6441a19ab3) - 2018-04-10
- [ [`50344`](https://github.com/thheller/shadow-cljs/commit/503445a2eb3a47b073ff606bd191ad6441a19ab3) ] node repl client was using the wrong read-string

## [2.2.26](https://github.com/thheller/shadow-cljs/compare/4e94646cde5ec9acab1c6ac223edb6590b8ddb5c...6674f5afce106062001cb504f81bb083acd83689) - 2018-04-08
- [ [`6674f`](https://github.com/thheller/shadow-cljs/commit/6674f5afce106062001cb504f81bb083acd83689) ] fix JS reloading, accidentally broken in 0a3a66042b82ce6a1c98d6377792f749d81c9a7d
- [ [`4e946`](https://github.com/thheller/shadow-cljs/commit/4e94646cde5ec9acab1c6ac223edb6590b8ddb5c) ] consistently set z-index for the hud

## [2.2.25](https://github.com/thheller/shadow-cljs/compare/b49fd6659d25f5f9d08450a3d8610cae8d9eb8d7...d0d79398ddd5f3ae11afc864ec46212870439d4b) - 2018-04-06
- [ [`d0d79`](https://github.com/thheller/shadow-cljs/commit/d0d79398ddd5f3ae11afc864ec46212870439d4b) ] clojure.* -> cljs.* aliasing didn't rename :refer-macros properly
- [ [`9b8f2`](https://github.com/thheller/shadow-cljs/commit/9b8f289cdc34f468a242e05494da08a6aea3bb36) ] dev-http should not deref the handler var
- [ [`b49fd`](https://github.com/thheller/shadow-cljs/commit/b49fd6659d25f5f9d08450a3d8610cae8d9eb8d7) ] bump shadow-cljsjs

## [2.2.24](https://github.com/thheller/shadow-cljs/compare/395b5546109c280e0abe666051be59f8d0223662...395b5546109c280e0abe666051be59f8d0223662) - 2018-04-04
- [ [`395b5`](https://github.com/thheller/shadow-cljs/commit/395b5546109c280e0abe666051be59f8d0223662) ] fix fs-watch accidentally filtering all file deletes

## [2.2.23](https://github.com/thheller/shadow-cljs/compare/0a3a66042b82ce6a1c98d6377792f749d81c9a7d...ec8edecd116b6ebf2d487f6358a2e07fa0dae7cc) - 2018-04-03
- [ [`ec8ed`](https://github.com/thheller/shadow-cljs/commit/ec8edecd116b6ebf2d487f6358a2e07fa0dae7cc) ] more logging, remove pprint for browser manifest
- [ [`76528`](https://github.com/thheller/shadow-cljs/commit/76528f9dd83c2c0bc688633437ed65805eafd64b) ] remove one assert to improve perf
- [ [`abd1a`](https://github.com/thheller/shadow-cljs/commit/abd1a415c16d6c6107b1da55f7ce5f6918cd8010) ] remove some reflective calls
- [ [`48844`](https://github.com/thheller/shadow-cljs/commit/48844b2fc9cfe0a915274e7f80e31c56ce838b51) ] add some additional logging to make tracing resolve easier
- [ [`0a3a6`](https://github.com/thheller/shadow-cljs/commit/0a3a66042b82ce6a1c98d6377792f749d81c9a7d) ] improve resolve perf when recompiling in watch

## [2.2.22](https://github.com/thheller/shadow-cljs/compare/ade8321fc991ecc192c3d8e975a139458c4a519e...6458eb4d4c42a026603865f6ae4667bb5fe2cb48) - 2018-04-03
- [ [`6458e`](https://github.com/thheller/shadow-cljs/commit/6458eb4d4c42a026603865f6ae4667bb5fe2cb48) ] less noisy warning when inspect resource fails
- [ [`2afe5`](https://github.com/thheller/shadow-cljs/commit/2afe5c82a42eb9d8c9925d6d22de6faaedb392e7) ] fix REPL crash on invalid input
- [ [`ade83`](https://github.com/thheller/shadow-cljs/commit/ade8321fc991ecc192c3d8e975a139458c4a519e) ] bump shadow-cljsjs

## [2.2.21, actually released yesterday](https://github.com/thheller/shadow-cljs/compare/0939bb8805c699f4f1e2f80126931a1a92a95b19...51774b65f571865a0d96850174b4bef3e9e894be) - 2018-03-30
- [ [`51774`](https://github.com/thheller/shadow-cljs/commit/51774b65f571865a0d96850174b4bef3e9e894be) ] bump closure-compiler
- [ [`60c8a`](https://github.com/thheller/shadow-cljs/commit/60c8ac82f6657a8d0f219a1acc961f7eec348fa6) ] print :repl/quit for clj-repl instead of :cljs/quit
- [ [`8a0e0`](https://github.com/thheller/shadow-cljs/commit/8a0e04bb8ebfe917396486d35fad0c001c4b3bc8) ] fix REPL require :reload
- [ [`b5f65`](https://github.com/thheller/shadow-cljs/commit/b5f65320ecae2b5f35c6f228b6f64b5f5d47e62f) ] fix typo/encoding
- [ [`0939b`](https://github.com/thheller/shadow-cljs/commit/0939bb8805c699f4f1e2f80126931a1a92a95b19) ] protect against *print-length* messing with data files

## [2.2.20](https://github.com/thheller/shadow-cljs/compare/c0b501da3d1a35c88336b02d063b2ce0473c205d...c0b501da3d1a35c88336b02d063b2ce0473c205d) - 2018-03-27
- [ [`c0b50`](https://github.com/thheller/shadow-cljs/commit/c0b501da3d1a35c88336b02d063b2ce0473c205d) ] quickfix for closure compatibility issue until their next release

## [2.2.19](https://github.com/thheller/shadow-cljs/compare/04a6947f279a84324aea24f138a5c534c824ba90...17905b4f9648b91c414c4d359c7a5c3ecb6be60f) - 2018-03-26
- [ [`17905`](https://github.com/thheller/shadow-cljs/commit/17905b4f9648b91c414c4d359c7a5c3ecb6be60f) ] bump to latest clojurescript 1.10.238
- [ [`c5eff`](https://github.com/thheller/shadow-cljs/commit/c5eff5888b2e1dcaac0c8b21bdc00373c618a3bc) ] fix broken cond
- [ [`6a0a2`](https://github.com/thheller/shadow-cljs/commit/6a0a2f9508f75b37ece44253100b779deb170469) ] enable clj-run fns to use server-only things like watch via metadata
- [ [`e2406`](https://github.com/thheller/shadow-cljs/commit/e2406650ba1bc5bc2daa7eb07c2a5f498eff284e) ] display errors in HUD if websocket disconnects/fails
- [ [`2df45`](https://github.com/thheller/shadow-cljs/commit/2df45046f1ab31f38c41a03b28dbf8a37292429e) ] improve REPL error message for missing eval client
- [ [`04a69`](https://github.com/thheller/shadow-cljs/commit/04a6947f279a84324aea24f138a5c534c824ba90) ] Merge branch 'master' of github.com:thheller/shadow-cljs

## [2.2.18](https://github.com/thheller/shadow-cljs/compare/db4f6853ca212aa5422139ce0f49a5d12c1c9abf...cd36aea989a404fb342a072fd6b8cb9fce2a815e) - 2018-03-25
- [ [`cd36a`](https://github.com/thheller/shadow-cljs/commit/cd36aea989a404fb342a072fd6b8cb9fce2a815e) ] change nrepl-select to only allow select when a JS runtime is connected
- [ [`52e6d`](https://github.com/thheller/shadow-cljs/commit/52e6d9921ac8a05035dedff857de6fdecab1d328) ] properly export shadow-js requires at the REPL
- [ [`e825c`](https://github.com/thheller/shadow-cljs/commit/e825cce767983d0dbfe8c724586dad093da8f716) ] Add Conduit to examples (#227)
- [ [`5b376`](https://github.com/thheller/shadow-cljs/commit/5b376d45eb346463c3c1846e690b45c27e249865) ] add fallback when the shadow-cljs CLI fails to connect
- [ [`db4f6`](https://github.com/thheller/shadow-cljs/commit/db4f6853ca212aa5422139ce0f49a5d12c1c9abf) ] fix failing assert on windows

## [2.2.17](https://github.com/thheller/shadow-cljs/compare/8310a245e586537e3fbe849145a1e83c154f9782...d403fa190cf72dab30962b2435e7452b7fa764d7) - 2018-03-23
- [ [`d403f`](https://github.com/thheller/shadow-cljs/commit/d403fa190cf72dab30962b2435e7452b7fa764d7) ] ensure proper cache invalidation for JS sources
- [ [`68c41`](https://github.com/thheller/shadow-cljs/commit/68c4124a8199cfe61a773cd245ec6d0e0683638d) ] fix accidental code corruption
- [ [`00e06`](https://github.com/thheller/shadow-cljs/commit/00e06f7ce2ea2c87f2bd30618d0c31d5bf51f523) ] avoid directly referencing static fields of closure classes
- [ [`727cf`](https://github.com/thheller/shadow-cljs/commit/727cfd023277b5e52e14e33328dbdc01124bb27a) ] change startup messages so cider can properly find nrepl port
- [ [`f104d`](https://github.com/thheller/shadow-cljs/commit/f104d68fb67bc8a106b2bf6e3382faea38280770) ] start collecting links to examples/templates in README
- [ [`db419`](https://github.com/thheller/shadow-cljs/commit/db4190bef453984fdde3d30b89070512a61a7505) ] enhance missing-ns error to warn if there is a .clj file
- [ [`8310a`](https://github.com/thheller/shadow-cljs/commit/8310a245e586537e3fbe849145a1e83c154f9782) ] ensure output-dir exists when writeing module-loader.{json,edn}

## [2.2.16](https://github.com/thheller/shadow-cljs/compare/7bb507b33dbfd0dd8fffcde53b4d5d067eb85171...aff3f442b264bec7ce6f10b911ac9ba768cd2f73) - 2018-03-20
- [ [`aff3f`](https://github.com/thheller/shadow-cljs/commit/aff3f442b264bec7ce6f10b911ac9ba768cd2f73) ] fix IllegalStateException on shutdown
- [ [`7bb50`](https://github.com/thheller/shadow-cljs/commit/7bb507b33dbfd0dd8fffcde53b4d5d067eb85171) ] ensure cache invalidation when closure version is manually changed

## [2.2.15](https://github.com/thheller/shadow-cljs/compare/b321acb62858e0f200f68019322d586989ccc113...b321acb62858e0f200f68019322d586989ccc113) - 2018-03-19
- [ [`b321a`](https://github.com/thheller/shadow-cljs/commit/b321acb62858e0f200f68019322d586989ccc113) ] nrepl fixes to behave more like piggieback

## [2.2.14](https://github.com/thheller/shadow-cljs/compare/fec0e6cf54107051dbd20ba94268134737448ffe...fec0e6cf54107051dbd20ba94268134737448ffe) - 2018-03-18
- [ [`fec0e`](https://github.com/thheller/shadow-cljs/commit/fec0e6cf54107051dbd20ba94268134737448ffe) ] rework live-reload so it can be configured via metadata in code

## [2.2.13](https://github.com/thheller/shadow-cljs/compare/6fecb5b2f47a6b204394f8e387c38d9fbdde7b58...6fecb5b2f47a6b204394f8e387c38d9fbdde7b58) - 2018-03-18
- [ [`6fecb`](https://github.com/thheller/shadow-cljs/commit/6fecb5b2f47a6b204394f8e387c38d9fbdde7b58) ] change nrepl piggieback binding so its immediately available after set

## [2.2.12](https://github.com/thheller/shadow-cljs/compare/ffe0ee7ec2962a59dc2cc19a6f1f675b2cbf0753...ffe0ee7ec2962a59dc2cc19a6f1f675b2cbf0753) - 2018-03-16
- [ [`ffe0e`](https://github.com/thheller/shadow-cljs/commit/ffe0ee7ec2962a59dc2cc19a6f1f675b2cbf0753) ] add :tooling-config and :external-config to cache affecting options

## [2.2.11](https://github.com/thheller/shadow-cljs/compare/bfdf402a60624699d78bf6240b0b4f5f588f991c...5d4362e30a16d248291bee5c65b76970650d40ad) - 2018-03-16
- [ [`5d436`](https://github.com/thheller/shadow-cljs/commit/5d4362e30a16d248291bee5c65b76970650d40ad) ] :provides must be set of symbols
- [ [`29557`](https://github.com/thheller/shadow-cljs/commit/2955705cde2a6b50433cb72dff9fc7bd46ee0d8e) ] work around google always replacing require in commonjs files
- [ [`bfdf4`](https://github.com/thheller/shadow-cljs/commit/bfdf402a60624699d78bf6240b0b4f5f588f991c) ] create .nrepl-port if it doesn't exist

## [2.2.10](https://github.com/thheller/shadow-cljs/compare/ece785a5c1d56f86861ff36ed14f5c31460f88ca...d1a51aeaf43eb10c3ec5976db76d068e468fde3a) - 2018-03-15
- [ [`d1a51`](https://github.com/thheller/shadow-cljs/commit/d1a51aeaf43eb10c3ec5976db76d068e468fde3a) ] pass :jvm-opts along to tools.deps when set in config
- [ [`ece78`](https://github.com/thheller/shadow-cljs/commit/ece785a5c1d56f86861ff36ed14f5c31460f88ca) ] fix InvalidPathException on windows when compiling with source maps

## [2.2.9](https://github.com/thheller/shadow-cljs/compare/21521590a01b710d6ca825e473b75759a1c9d85e...21521590a01b710d6ca825e473b75759a1c9d85e) - 2018-03-14
- [ [`21521`](https://github.com/thheller/shadow-cljs/commit/21521590a01b710d6ca825e473b75759a1c9d85e) ] ignore defmacro in normal cljs compilation

## [2.2.8](https://github.com/thheller/shadow-cljs/compare/0749e08322f5059e255fb3eb41a0f3c63c29c1d3...6e433a5085f706a03e6ac59f8c8de0390a4dcc34) - 2018-03-12
- [ [`6e433`](https://github.com/thheller/shadow-cljs/commit/6e433a5085f706a03e6ac59f8c8de0390a4dcc34) ] fix ns :rename not properly using ns aliases
- [ [`0749e`](https://github.com/thheller/shadow-cljs/commit/0749e08322f5059e255fb3eb41a0f3c63c29c1d3) ] upgrade to latest CLJS pre-release, fix java9 issues

## [2.2.7](https://github.com/thheller/shadow-cljs/compare/5b04e283cc98eac5cd4aba2f67d4b05fc9c8649a...7ef884caca59fe9b23b1ad3a2ec7febbf101cece) - 2018-03-10
- [ [`7ef88`](https://github.com/thheller/shadow-cljs/commit/7ef884caca59fe9b23b1ad3a2ec7febbf101cece) ] ensure the :file and :url attrs from a rc don't get sent to the client
- [ [`947e5`](https://github.com/thheller/shadow-cljs/commit/947e58384b89ef57d416711e792094d34667d928) ] enable autoload by default for :browser target
- [ [`5b04e`](https://github.com/thheller/shadow-cljs/commit/5b04e283cc98eac5cd4aba2f67d4b05fc9c8649a) ] fix logging.properties issue when running shadow-cljs in nested folder

## [2.2.6](https://github.com/thheller/shadow-cljs/compare/f7a398d03a7e3fc06be2c7473498325bd932a680...f7a398d03a7e3fc06be2c7473498325bd932a680) - 2018-03-08
- [ [`f7a39`](https://github.com/thheller/shadow-cljs/commit/f7a398d03a7e3fc06be2c7473498325bd932a680) ] add :autorun option for :node-test target

