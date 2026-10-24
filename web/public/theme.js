// Applies the saved theme before first paint (no flash). Loaded as a classic,
// blocking same-origin script from <head> so a CSP of default-src 'self'
// allows it. Keep in sync with src/ui/theme.ts (key "smsgate-theme").
;(function () {
  try {
    var t = localStorage.getItem('smsgate-theme')
    var d = t === 'dark' || (t !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches)
    var r = document.documentElement
    if (d) r.classList.add('dark')
    r.style.colorScheme = d ? 'dark' : 'light'
  } catch (e) {}
})()
