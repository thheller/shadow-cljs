
let filesToPurge;

if (process.env.NODE_ENV == "production") {
  filesToPurge = ["src/ui-release/shadow/cljs/ui/dist/js/*.js"];
} else {
  filesToPurge = [".shadow-cljs/ui/js/cljs-runtime/*.js"];
}

module.exports = {
  content: filesToPurge,
  plugins: [
      require('@tailwindcss/forms')
  ]
}