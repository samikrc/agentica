/**
 * main.js — App entry point.
 * The UI is served directly by the backend, so the base URL is always
 * window.location.origin (same host + port).
 * The bearer token is read from the #token= URL fragment, which the
 * launch scripts append automatically. Fragments are never sent to the
 * server; the token is stripped from the URL bar/history after reading and
 * kept in sessionStorage so reloads stay authenticated.
 * The legacy ?token= query param is accepted as a fallback.
 */

(async () => {
  const hashParams = new URLSearchParams(window.location.hash.slice(1));
  const params     = new URLSearchParams(window.location.search);
  let token        = hashParams.get('token') || params.get('token');

  // Persist before stripping so a page reload stays authenticated; the
  // sessionStorage copy is per-tab and dies with it.
  if (token) {
    sessionStorage.setItem('agentica_token', token);
    history.replaceState(null, '', window.location.pathname);
  } else {
    token = sessionStorage.getItem('agentica_token') || '';
  }

  Api.init(window.location.origin, token);

  Sessions.onSelect(session => Chat.setSession(session));

  await Settings.load();
  await Sessions.load();

  // Sidebar toggle
  const btnToggleSidebar = document.getElementById('btn-toggle-sidebar');
  const content = document.getElementById('content');
  btnToggleSidebar.addEventListener('click', () => {
    content.classList.toggle('sidebar-hidden');
  });
})();
