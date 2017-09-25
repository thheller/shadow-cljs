if (process.env.NODE_ENV == 'production') {
  "EQ:prod"
} else {
  "EQ:dev"
}

if (process.env.NODE_ENV) {
  "check";
}

if (something == "foo") "something";

if (process.env.NODE_ENV == 'development') {
  "EQ:dev"
} else {
  "EQ:prod"
}

if (process.env.NODE_ENV == "production") {
   "EQ;prod;no-else"
}

if (process.env.NODE_ENV === "production") {
   "SHEQ;prod;no-else"
}

if (process.env.NODE_ENV == "development") {
  "removed";
}

if (process.env.NODE_ENV != "development") {
   "NE;prod";
}

if (process.env.NODE_ENV !== "development") {
   "SHNE;prod";
}



var test2 = (process.env.NODE_ENV !== "production") ? "dev" : "prod";

if (/[?&]react_perf\b/.test(url)) {
  ReactDebugTool.beginProfiling();
}