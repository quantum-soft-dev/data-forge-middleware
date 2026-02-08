(function () {
  // Dev fallback.
  //
  // In containerized "production-like" deployments we generate /env-config.js at runtime
  // to inject environment variables into the browser (window._env_).
  //
  // During local Vite development this file must still exist, otherwise the request to
  // /env-config.js falls back to index.html and the browser throws a syntax error,
  // preventing the app from rendering (blank page).
  //
  // We intentionally keep this empty so code falls back to import.meta.env values
  // sourced from frontend/.env.local.
  window._env_ = window._env_ || {};
})();

