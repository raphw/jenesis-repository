# DESIGN.md — the console design system

The shared visual base for the jenesis consoles, layered over the vendored **Pico.css**. It is authored here in the
free repository console (`source/ui`) and is the base the enterprise console extends, so the two products speak one
visual language rather than drifting apart. This document is the catalogue; the implementation is
[`source/ui/META-INF/resources/css/app.css`](source/ui/META-INF/resources/css/app.css) (the tokens and component styles) and
[`source/ui/META-INF/templates/base.html`](source/ui/META-INF/templates/base.html) (the component set as Thymeleaf fragments). Keep the
three in sync — a change to a token or component updates all three.

It is deliberately **thin and additive**: no bespoke widgets, no framework beyond Pico + htmx (both already vendored),
and *no business logic in the shell* — every fragment is pure presentation. The design system is mostly not Java; it
is assets (CSS + fragments) plus, later, a couple of SPI contracts (the icon SPI, W4.2).

## 1. Design tenets

- **Minimal / calm.** One column of attention, progressive disclosure, generous whitespace on the spacing scale.
- **Built on Pico.** `app.css` only defines `--app-*` tokens and `.app-*` component classes over Pico's own
  `--pico-*` variables. It never forks a Pico rule, so Pico upgrades and its light/dark theming keep working.
- **Theme-safe.** Every colour is a token with a light and a dark value; no screen hard-codes a colour. Dark mode is
  Pico's `data-theme` (and the OS `prefers-color-scheme` when a deployment has not pinned a theme).
- **Accessible by construction.** Semantic landmarks, a visible focus ring on every control, colour never the sole
  signal, WCAG-AA contrast in both themes.

## 2. Design tokens (`app.css`)

| Group | Tokens | Note |
|-------|--------|------|
| Type scale | `--app-font-size-{xs,sm,base,lg,xl,2xl}` | A modular scale; a screen never invents a font size. |
| Spacing | `--app-space-{1..6}` (0.25rem → 2rem) | Every gap/margin/padding is a step on this scale. |
| Radii | `--app-radius-{sm,md,lg}` | |
| Icon box | `--app-icon-size` (1.25rem) | The uniform square the icon SPI (W4.2) renders into. |
| Status palette | `--app-status-{pass,warn,danger,info,muted}-{bg,fg}` | Light values under `:root`, dark under `[data-theme=dark]` and `prefers-color-scheme: dark`; AA-contrast in each theme. |

## 3. Component set (`base.html`)

The minimum set every screen composes from. The reusable ones are Thymeleaf fragments in `base.html`; the two that
are page-specific markup (list/table, card body) are CSS-class patterns documented here.

### Page header — `base :: pageHeader(title)` / `base :: pageHeaderCrumbs(title, crumbs)`
Title, an optional breadcrumb above it, and a right-aligned primary-action slot (`.app-page-header__actions`). Use
`pageHeaderCrumbs` when the screen is nested (browse); `crumbs` is a `List<Map{href,label}>` whose last entry is the
current page. A header that needs an action button renders the `.app-page-header` markup directly:

```html
<header class="app-page-header">
  <hgroup class="app-page-header__heading"><h1 class="app-page-header__title">Repositories</h1></hgroup>
  <div class="app-page-header__actions"><a role="button" href="/new">New repository</a></div>
</header>
```

### Generic list / table — `.app-list` (CSS pattern)
The one component behind browse, search results and version lists. A semantic `<table class="app-list">`; `.app-list__name`
aligns a name with its (optional) SPI icon, `.app-list__num` right-aligns a tabular-numeric column (size/count). On a
browse folder row, `.app-tree-toggle` is the small inline disclosure control that lazy-loads the folder's children on
expand (the generic browse at `/browse` — a breadcrumbed lazy tree via `base :: pageHeaderCrumbs` — is built on this
table).

```html
<table class="app-list">
  <thead><tr><th>Name</th><th>Type</th><th class="app-list__num">Size</th></tr></thead>
  <tbody><tr>
    <td><span class="app-list__name"><svg class="app-icon">…</svg> serde</span></td>
    <td>folder</td><td class="app-list__num">1.2 MiB</td>
  </tr></tbody>
</table>
```

### Card — `.app-card` (CSS pattern over Pico `<article>`)
The artifact-detail and single-record layout. `.app-card__meta` is a `<dl>` grid for coordinate/checksum/size rows.

### Form field with inline help — `base :: field(label, name, value, help)`
Label, control, help text (`aria-describedby`-linked). The atom the config page (W4.4) composes from; the config page
adds validation state (`.app-field--invalid`) and a default-vs-effective note (`.app-field__note`).

### Empty / loading / error state — `base :: empty(message)`
One consistent treatment (`role="status"`), never a blank screen. A list/table that can be empty renders this
*instead of* the bare table, with a message that says what fills it. **Caution:** `th:if` must sit on a *wrapping*
`th:block`, never on the `th:replace` element itself — fragment inclusion (precedence 100) runs before the
conditional (300), so a same-element `th:if` is silently ignored and the empty state renders always.
The loading state rides htmx: the element that triggered an in-flight request carries `.htmx-request`
(`.app-tree-toggle.htmx-request` shows a busy cursor and dims) — a state, not a decorative spinner.

### Badge / pill — `base :: badge(label, kind)`
Status (`passed` / `quarantined` / `signed`) and config metadata (`live` / `restart`, `changed`). `kind ∈
{pass,warn,danger,info,muted}`. The **label text always carries the meaning**, so colour is never the only signal.

### Alert / flash message — `base :: alert(message, kind)`
The one outcome-banner treatment (saved / failed / signed out): tinted from the status palette (`kind ∈
{pass,warn,danger}`, `null` = info), `role="alert"` when danger so it is announced, `role="status"` otherwise.
`.app-danger-text` is the inline sibling for a per-row error detail inside a table cell.

### Theme switch — `base :: themeSelect`
A compact labelled `<select>` (`Auto / Light / Dark`) the shell nav carries. `/js/theme.js` — loaded un-deferred by
`base :: head` so a stored choice applies before first paint — wires every `[data-theme-select]`, persists the
choice per browser (`localStorage`, key `jenesis-theme`) and sets `data-theme` on `<html>`; `Auto` removes the
override so Pico's `prefers-color-scheme` rules decide. No server round-trip: the theme is presentation, not a
deployment setting.

## 4. Accessibility baseline

- **Landmarks** — `<header>/<nav>/<main id="main">`, and a `.app-skip-link` "skip to content" as the first focusable
  element on every page.
- **Keyboard** — every control reachable and operable; a visible `:focus-visible` ring that is styled, never removed.
- **Colour is never the sole signal** — every status carries an icon and/or text alongside its colour.
- **Contrast** — WCAG-AA in both light and dark; the status palette is tuned per theme.
- **Icons** — `currentColor`-friendly (`.app-icon { fill: currentColor }`) so they invert with the theme, at one
  uniform size.
- **Hidden-but-announced** — `.app-sr-only` for text that exists only for assistive technology (the header of an
  icon-only actions column, an input's label where the design shows a placeholder).
- **Responsive** — the shell nav wraps on narrow viewports (`.app-nav`); wide tables live in a `<figure>` so Pico
  scrolls them horizontally instead of breaking the page layout.

## 5. How the enterprise console extends this base

The intended model is that the enterprise console reuses this base rather than re-vendoring it — serving the same
`app.css` and `th:replace`-ing the `base.html` fragments — and applying the shell across every enterprise screen is
the cross-console cohesion pass (worklist W4.5a). This document and `base.html`/`app.css` are the base that pass
draws on.

**The sharing mechanism: this module's resources live under `META-INF`, which JPMS cannot split.** A plain
`templates/` (or `static/`) directory is derived as a module *package*, and the module layer refuses to
instantiate two modules that split a package (`LayerInstantiationException: Package templates in both module …`) —
which blocked the obvious `requires build.jenesis.repository.ui` edge while both consoles shipped those
directories. `META-INF` is not a legal package name (the hyphen), so nothing under it is ever a package: this
module therefore ships its templates as `META-INF/templates/` (named by `spring.thymeleaf.prefix`) and its static
assets as `META-INF/resources/` (the Servlet web-fragment convention Spring Boot serves first by default, so the
`/css/…`/`/js/…` URLs are unchanged). A downstream console `requires` this module, keeps its own `templates/`,
adds one secondary Thymeleaf resolver over `classpath:/META-INF/templates/` **pattern-scoped to the shared
vocabulary** (`setResolvablePatterns(Set.of("base", "base/*"))`) and `th:replace`s the `base.html` fragments —
one visual language, no re-vendored copy, no split package. Scope by pattern, not resolver order: this module
also carries its own console pages under names a downstream console likely uses too (`browse`, `login`), and
Thymeleaf consults explicitly ordered resolvers ahead of the unordered default, so an order-based fallback lets
the wrong module answer a shared name.
