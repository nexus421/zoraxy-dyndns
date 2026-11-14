package bayern.kickner.zoraxy.dyndns

/**
 * Stylesheet of the status page, served as `/ui/app.css`. Colors and components follow Dirl. Light and dark through
 * `data-theme` on `<html>`, which [APP_JS] sets from Zoraxy's own theme setting. Kept in code instead of a resource,
 * so the native image needs no resource config.
 */
// language=CSS
const val APP_CSS = """
:root, [data-theme=light] {
  --bg: #f4f5f7; --surface: #ffffff; --surface-2: #f8f9fb; --fg: #16181d; --muted: #5f6672;
  --border: #e2e5ea; --border-strong: #cfd4dc; --accent: #2f6fed; --accent-fg: #ffffff; --accent-soft: #e8effd;
  --ok: #138a52; --ok-soft: #e3f5ec; --warn: #b35c00; --warn-soft: #fdf0e1; --error: #c8322a; --error-soft: #fbe9e8;
  --code: #f1f3f6; --shadow: 0 1px 2px rgba(16,24,40,.05), 0 1px 3px rgba(16,24,40,.06); --glow: .05;
  --mono: ui-monospace, "JetBrains Mono", "DejaVu Sans Mono", Menlo, Consolas, monospace;
  color-scheme: light;
}
[data-theme=dark] {
  --bg: #0f1115; --surface: #171a20; --surface-2: #1c2027; --fg: #e7e9ee; --muted: #9aa2b1;
  --border: #272c35; --border-strong: #353b47; --accent: #5b8dff; --accent-fg: #0b1020; --accent-soft: #1b2640;
  --ok: #3ccf8e; --ok-soft: #12291f; --warn: #f2a54a; --warn-soft: #2d2113; --error: #ff6b61; --error-soft: #331716;
  --code: #11141a; --shadow: none; --glow: .16;
  color-scheme: dark;
}
* { box-sizing: border-box; }
[hidden] { display: none !important; }
body { margin: 0; background: var(--bg); color: var(--fg); font: 15px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; -webkit-font-smoothing: antialiased; }
body::before { content: ""; position: fixed; inset: 0; z-index: -1; pointer-events: none;
  background:
    radial-gradient(42rem 32rem at 0% 0%, rgb(91 141 255 / var(--glow)), transparent 70%),
    radial-gradient(42rem 32rem at 100% 0%, rgb(167 139 250 / calc(var(--glow) * .85)), transparent 70%),
    radial-gradient(42rem 32rem at 0% 100%, rgb(34 211 238 / calc(var(--glow) * .7)), transparent 70%),
    radial-gradient(42rem 32rem at 100% 100%, rgb(91 141 255 / calc(var(--glow) * .7)), transparent 70%); }
h1, h2, h3 { margin: 0; line-height: 1.25; }
h1 { font-size: 22px; font-weight: 650; } h2 { font-size: 16px; font-weight: 650; }
code, .mono { font-family: var(--mono); font-size: 13px; }
.muted { color: var(--muted); } .small { font-size: 13px; } .break { overflow-wrap: anywhere; }

.container { max-width: 1040px; margin: 0 auto; padding: 22px 20px 40px; display: grid; gap: 16px; }
.head { display: flex; flex-wrap: wrap; align-items: center; gap: 14px; }
.logo { width: 44px; height: 44px; border-radius: 11px; box-shadow: 0 0 22px rgb(91 141 255 / .35); }
.head .sub { margin-top: 2px; }
.head .spacer { flex: 1; }
.host-form { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.host-form label { font-weight: 550; font-size: 14px; }
.host-form input { flex: 1 1 240px; width: auto; }

.card { background: var(--surface); border: 1px solid var(--border); border-radius: 12px; box-shadow: var(--shadow); padding: 18px 20px; min-width: 0; }
.card.inset { background: var(--surface-2); margin-bottom: 14px; }
.card-header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.card.attention { border-color: color-mix(in srgb, var(--accent) 55%, var(--border)); box-shadow: 0 0 0 3px var(--accent-soft); }

.btn { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 34px; padding: 6px 13px;
  border: 1px solid var(--border-strong); border-radius: 8px; background: var(--surface); color: var(--fg); font: inherit; font-weight: 550; cursor: pointer; white-space: nowrap; }
.btn:hover { background: var(--surface-2); }
.btn.primary { background: var(--accent); border-color: var(--accent); color: var(--accent-fg); }
.btn.primary:hover { filter: brightness(1.07); }
.btn.danger { color: var(--error); border-color: color-mix(in srgb, var(--error) 40%, var(--border)); }
.btn.danger:hover, .btn.armed { background: var(--error-soft); }
.btn.armed { color: var(--error); border-color: var(--error); }
.btn.ghost { border-color: transparent; background: transparent; color: var(--muted); }
.btn.sm { min-height: 28px; padding: 3px 10px; font-size: 13px; }
.row-actions { display: flex; flex-wrap: wrap; gap: 6px; justify-content: flex-end; align-items: center; }
.row-actions form { display: inline; }

.pill { display: inline-flex; align-items: center; gap: 6px; padding: 1px 9px; border-radius: 999px; font-size: 12.5px; font-weight: 550; white-space: nowrap;
  background: var(--surface-2); color: var(--muted); border: 1px solid var(--border); }
.pill.dot::before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.pill.ok { color: var(--ok); background: var(--ok-soft); border-color: transparent; }
.pill.warn { color: var(--warn); background: var(--warn-soft); border-color: transparent; }
.pill.error { color: var(--error); background: var(--error-soft); border-color: transparent; }
.pill.accent { color: var(--accent); background: var(--accent-soft); border-color: transparent; }
.pills { display: flex; flex-wrap: wrap; gap: 6px; }

.notice { display: flex; gap: 10px; align-items: flex-start; padding: 10px 14px; border-radius: 10px; overflow-wrap: anywhere; }
/* Room for Zoraxy's fixed top bar when app.js scrolls a message into view */
.notice, .secret { scroll-margin-top: 72px; }
.notice.success { background: var(--ok-soft); color: var(--ok); }
.notice.error { background: var(--error-soft); color: var(--error); }
.notice.warn { background: var(--warn-soft); color: var(--warn); }

.clients { display: grid; gap: 10px; }
.client { display: grid; grid-template-columns: minmax(150px, 1.1fr) minmax(170px, 1.4fr) minmax(150px, 1fr) auto; gap: 8px 16px; align-items: start;
  padding: 12px 14px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); }
.client .name { font-weight: 650; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.client .pills { margin-top: 6px; }
.label { font-size: 11.5px; text-transform: uppercase; letter-spacing: .04em; color: var(--muted); margin-bottom: 2px; }
.client .edit { grid-column: 1 / -1; }
@media (max-width: 760px) { .client { grid-template-columns: 1fr; } .row-actions { justify-content: flex-start; } }

.form { display: grid; gap: 12px; margin-top: 12px; }
.field { display: grid; gap: 6px; }
.field > span { font-weight: 550; font-size: 14px; }
.hint { color: var(--muted); font-size: 12.5px; }
input[type=text], select, textarea { width: 100%; padding: 7px 10px; font: inherit; color: var(--fg); background: var(--surface); border: 1px solid var(--border-strong); border-radius: 8px; }
textarea { font-family: var(--mono); font-size: 13px; resize: vertical; min-height: 64px; }
input:focus, select:focus, textarea:focus, .btn:focus-visible { outline: 2px solid color-mix(in srgb, var(--accent) 55%, transparent); outline-offset: 1px; }
.checks { display: flex; flex-wrap: wrap; gap: 8px 14px; }
.checks label { display: flex; gap: 6px; align-items: center; padding: 4px 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); cursor: pointer; }
.checks label:has(input:checked) { border-color: var(--accent); background: var(--accent-soft); }

.secret { display: grid; gap: 12px; }
.secret .card-header { margin: 0; }
.copy-line { display: flex; gap: 8px; align-items: center; }
.copy-line code { flex: 1; min-width: 0; padding: 8px 10px; background: var(--code); border: 1px solid var(--border); border-radius: 8px; overflow-wrap: anywhere; }
.copy-label { margin-bottom: 4px; }

.rules { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); }
.rule { border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); padding: 14px; display: grid; gap: 10px; align-content: start; }
.rule .top { display: flex; justify-content: space-between; gap: 8px; align-items: baseline; flex-wrap: wrap; }
.rule .row-actions { justify-content: flex-start; }
.mode-hint.danger { color: var(--warn); }
.add-rule { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
.add-rule select { flex: 1 1 220px; width: auto; }
.add-rule-hint { margin-top: 6px; }

details.card > summary { cursor: pointer; list-style: none; }
details.card > summary::-webkit-details-marker { display: none; }
details.card > summary h2::before { content: "▸ "; color: var(--muted); }
details.card[open] > summary h2::before { content: "▾ "; }
.activity { list-style: none; margin: 12px 0 0; padding: 0; display: grid; gap: 4px; }
.activity li { display: flex; gap: 12px; font-size: 13px; }
.activity time { color: var(--muted); white-space: nowrap; }
.activity .mono { overflow-wrap: anywhere; }

footer { text-align: center; color: var(--muted); font-size: 12px; padding: 4px 0 20px; }
footer a { color: var(--accent); text-decoration: none; }
"""

/**
 * The only script of the status page, served as `/ui/app.js`. Zoraxy's iframe allows scripts but no forms and no
 * dialogs, so this file sends the forms with `fetch` (with the CSRF token Zoraxy checks), swaps in the page that
 * comes back, asks for a second click instead of `confirm()`, copies text and follows Zoraxy's light/dark setting.
 * It only reads `data-` attributes that [renderPage] sets.
 */
// language=JS
const val APP_JS = """
(() => {
  const theme = () => {
    try { document.documentElement.dataset.theme = localStorage.getItem("theme") === "light" ? "light" : "dark"; } catch (e) {}
  };
  theme();
  window.addEventListener("storage", e => { if (e.key === "theme") theme(); });

  const csrf = () => document.querySelector('meta[name="csrf-token"]')?.content ?? "";

  const showError = text => {
    const notice = document.createElement("div");
    notice.className = "notice error";
    notice.textContent = text;
    document.querySelector("main")?.prepend(notice);
    notice.scrollIntoView({ block: "start" });
  };

  // Asks again by changing the button: the second click confirms, but only after a short pause, so a double-click
  // never deletes in one go
  const arm = button => {
    const text = button.textContent;
    button.textContent = button.dataset.confirm;
    button.classList.add("armed");
    button.dataset.armedAt = Date.now();
    setTimeout(() => { button.textContent = text; button.classList.remove("armed"); }, 4000);
  };
  const confirmed = button => button.classList.contains("armed") && Date.now() - button.dataset.armedAt > 400;

  // Zoraxy's iframe lacks allow-forms: the browser drops a form submission before any submit event fires, so a
  // click on a submit button and Enter in a text field are caught here and sent with fetch instead
  // One request at a time: a double click must not send an action twice, the second answer would replace the page
  // that shows a new password once
  let busy = false;
  const send = async (form, button) => {
    if (busy) return;
    if (button?.dataset.confirm && !button.classList.contains("armed")) return arm(button);
    if (button?.dataset.confirm && !confirmed(button)) return;
    busy = true;
    try {
      const action = button?.hasAttribute("formaction") ? button.formAction : form.action;
      const response = await fetch(action, {
        method: "POST",
        headers: { "X-CSRF-Token": csrf(), "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams(new FormData(form)),
      });
      const page = new DOMParser().parseFromString(await response.text(), "text/html");
      if (!response.ok || !page.querySelector("main")) throw new Error("Request rejected (HTTP " + response.status + "). Reload the page and try again.");
      // Keep open what the admin opened, the new page comes with every <details> closed
      const open = [...document.querySelectorAll("details[id][open]")].map(d => d.id);
      document.body.replaceWith(page.body);
      open.forEach(id => document.getElementById(id)?.setAttribute("open", ""));
      const token = page.querySelector('meta[name="csrf-token"]')?.content;
      if (token) document.querySelector('meta[name="csrf-token"]').content = token;
      // Zoraxy scrolls its own page, not the iframe: scrollIntoView also moves the parent to the message
      (document.querySelector(".secret") ?? document.getElementById("notice") ?? document.body).scrollIntoView({ block: "start" });
    } catch (e) {
      showError(e.message);
    } finally {
      busy = false;
    }
  };

  document.addEventListener("keydown", event => {
    const field = event.target;
    // Only text fields submit on Enter, like a browser does it, never a checkbox
    if (event.key !== "Enter" || event.repeat || field.tagName !== "INPUT" || field.type !== "text" || !field.form) return;
    event.preventDefault();
    send(field.form, field.form.querySelector('button[type="submit"]'));
  });

  const copy = button => {
    const target = document.getElementById(button.dataset.copy);
    const done = text => { const old = button.textContent; button.textContent = text; setTimeout(() => button.textContent = old, 2000); };
    const select = () => {
      const range = document.createRange();
      range.selectNodeContents(target);
      getSelection().removeAllRanges();
      getSelection().addRange(range);
      done("Press Ctrl+C");
    };
    if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(target.textContent).then(() => done("Copied"), select);
    else select();
  };

  document.addEventListener("click", event => {
    const button = event.target.closest("button");
    if (!button) return;
    if (button.type === "submit" && button.form) {
      event.preventDefault();
      return send(button.form, button);
    }
    if (button.dataset.toggle) document.getElementById(button.dataset.toggle).toggleAttribute("hidden");
    if (button.dataset.copy) copy(button);
    if (button.hasAttribute("data-dismiss")) button.closest("section")?.remove();
  });
})();
"""
