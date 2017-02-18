# node.js platform internals

So I don't forget.

`require` works by reading the file and wrapping it in

```
NativeModule.wrapper = [
  '(function (exports, require, module, __filename, __dirname) { ',
  '\n});'
];
```

so every require'd file has its own scope.

It is all executed via the `vm` package and `runInThisContext`.

`require.cache` is used to prevent loading a file multiple times. It uses the `require(id)` so if you attempt to load the same file via different `id` it will load it.