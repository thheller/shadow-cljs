module.exports = {
  plugins: {
    '@tailwindcss/jit': {},
    autoprefixer: {},
    cssnano: process.env.NODE_ENV == 'production' ? {} : false
  }
}