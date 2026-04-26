/**
 * main.js — App entry point.
 * The UI is served directly by the backend, so the base URL is always
 * window.location.origin (same host + port).
 * The bearer token is read from the ?token= URL query param, which the
 * launch scripts append automatically.
 */

(async () => {
  const params = new URLSearchParams(window.location.search);
  const token  = params.get('token') || 'dev-token';

  Api.init(window.location.origin, token);

  Sessions.onSelect(session => Chat.setSession(session));

  await Sessions.load();
})();
