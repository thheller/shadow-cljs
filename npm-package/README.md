# ClojureScript for JS devs

You can use `npm` or `yarn`.

```
yarn add shadow-cljs
./node_modules/shadow-cljs/bin/shadow-cljs
```

You can also `yarn global add shadow-cljs` and just use `shadow-cljs` globally.

The package should be added to your `package.json` though since `yarn` will remove the directory otherwise. We need to keep that.

## Usage

`shadow-cljs`
 
See `shadow-cljs -h` for options. 

By default it will look for CLJS sources in `src-cljs`.

You may add some configuration to your `package.json` under the `shadow-cljs` key
```javascript
  "shadow-cljs": {
    "version":"1.0.20170516",
    "dependencies": [
      ["some-cljs-package", "1.0.0"]
    ],
    "source-paths": [
      "src"
    ]
  }
```

- `version` is the version of `shadow-cljs` to use (optional)
- `source-paths` defaults to `["src-cljs"]`
- `dependencies` extra CLJS dependencies you want
