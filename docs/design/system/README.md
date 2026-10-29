# Locke design system

Locke is Skycloak's Keycloak distribution (Keycloak on Redis). Its screens are the
login theme (keycloak.v2, server-rendered FreeMarker), the admin console and the
account console (React, PatternFly v5). This system collects what the repo already
has so new screens can be designed and built from the same tokens, markup and states.

Everything here comes from the repo. Nothing is invented: a token, a value or a
component appears only if sky-cloak/locke defines or uses it.

## Principles

- **Ink on paper.** The brand is one indigo (`--locke-ink`) on a warm cream canvas
  (`--locke-paper`). The mark and wordmark use ink only. In dark scheme the roles
  flip to light indigo on deep indigo.
- **Accents are rare.** Brass, coral and sage are reserved for premium or stamped
  surfaces. Use at most one accent on a surface, never two at once.
- **PatternFly underneath.** Components are PatternFly v5 classes, mapped through
  the login theme's `theme.properties` (kcButtonPrimaryClass, kcInputClass, and so on)
  and used directly in React. Spacing, type scale and semantic colors are PatternFly
  globals referenced by name (`--pf-v5-global--spacer--md`), with Locke overrides
  layered on top in small stylesheets.
- **One column on login.** The login container is a single 34rem column with the
  header above the card. The card header carries a 4px top border in
  `--keycloak-card-top-color`.
- **Dark mode follows the system.** The login theme adds `pf-v5-theme-dark` when
  `prefers-color-scheme: dark` matches. Styles that change in dark mode (QR code,
  dropdown panel) do so through that class or the media query.

## Tokens

`tokens.css` declares every custom property the repo defines, under `:root`, with
dark values under `@media (prefers-color-scheme: dark)` and `.dark` /
`.pf-v5-theme-dark`.

| Group | Tokens | Source |
| --- | --- | --- |
| Brand colors | `--locke-ink`, `--locke-paper`, `--locke-brass`, `--locke-coral`, `--locke-sage`, `--locke-ink-dark`, `--locke-paper-dark` | docs/brand/palette.css |
| Login theme | `--keycloak-logo-url`, `--keycloak-bg-logo-url`, `--keycloak-logo-height`, `--keycloak-logo-width`, `--keycloak-card-top-color` | keycloak.v2 login styles.css |

The repo defines no named type, spacing, radius or shadow tokens of its own. It
references PatternFly's (`--pf-v5-global--FontSize--sm`, `--pf-v5-global--spacer--lg`,
`--pf-v5-global--BoxShadow--lg`, ...) and uses a handful of literal values (tooltip
radius 2px, QR radius 10px, tooltip shadow `0 1px 8px rgba(0,0,0,0.6)`). The
foundations pages list the PatternFly names the repo uses and every literal value,
so screens can match them. In the demo pages each PatternFly name is given a
fallback drawn from the repo's own literals, so the pages render without PatternFly;
in product code PatternFly supplies the value.

Pages: `foundations/colors.html`, `foundations/type.html`, `foundations/spacing.html`.

## Components

One self-contained page per component in `components/`, showing variants and
states with the markup and class names the repo uses.

| Page | What it covers | Built from |
| --- | --- | --- |
| button | primary, secondary, tertiary, link, control, plain; block; social provider buttons; disabled and aria-disabled | buttons.ftl, theme.properties, styles.css |
| input | text, password with visibility toggle, attempted username, clipboard copy, textarea, number, native select; label, required, helper, error, readonly, disabled | field.ftl, template.ftl, FormErrorText, HelpItem |
| checkbox | login check, admin 20px checkbox, scaled radio | field.ftl, index.css, realm-settings-section.css |
| select | SingleSelect, TypeaheadSelect, admin dropdown panel, native language select, fixed widths | ui-shared select, dropdown-panel.css |
| alert | inline login messages (success, warning, danger, info), toast AlertPanel, recovery codes warning, field messages | template.ftl, AlertPanel.tsx |
| card | keycloak.v2 login card, legacy card-pf, raised panel, KeycloakCard, flat settings panel | styles.css, login.css, KeycloakCard.tsx |
| dialog | ContinueCancelModal, sizes, scrolling body | ContinueCancelModal.tsx |
| tooltip | CSS login tooltip, PatternFly tooltip and help popover | styles.css, HelpItem.tsx |
| divider | labelled login divider, hr, PatternFly divider | divider.ftl, styles.css |
| qr-code | QR block with dark variant, manual TOTP key, recovery codes list | qr-code.ftl, styles.css, login.css |
| label | Label colors, Badge, member count, colored status text | KeycloakCard.tsx, used-by.module.css, user-section.css |
| chip | filter chip group, consents chip group | RolesList.css, user-section.css |
| switch | SwitchControl states, help header switch | SwitchControl.tsx, help-header.css |
| data-list | select authenticator rows, admin flow header | select-authenticator.ftl, flow-header.css |
| tile | OTP credential tiles with selected state | login-otp.ftl |
| spinner | KeycloakSpinner | KeycloakSpinner.tsx |
| masthead | app header with brand, user menu, nav toggle; sidebar nav | Masthead.tsx, page-nav.css |
| flow-diagram | authentication flow nodes and edge button | flow-diagram.css |
| form-actions | fixed save bar, scroll form panels | index.css, fixed-buttons.module.css, scroll-form |

## States

States are expressed the way the repo expresses them:

- **Hover / focus / active** come from PatternFly modifiers; Locke overrides the
  bottom border of form controls and the dropdown panel to `primary-color--100`
  on hover and `active-color--100` (2px) on focus. The demo pages pin these with
  `is-hover` and `is-focus` classes so every state is visible at once.
- **Disabled** uses the native `disabled` attribute on buttons and inputs. Links
  that act once (social providers) get `pf-m-aria-disabled` plus `aria-disabled`
  after the first click.
- **Error** adds `pf-m-error` to the form control, `aria-invalid`, an
  exclamation icon in the control utilities, and a helper text item with
  `pf-m-error`.
- **Read only** adds `pf-m-readonly` to the form control.
- **Selected** uses `pf-m-selected` (OTP tiles, menu items, cards) or the
  `selected` class with a 0.5px ring on flow nodes.
- **Dark** switches `--locke-ink` / `--locke-paper` and removes the QR border and
  shadow; the dropdown panel content moves to `BackgroundColor--300`. Each page
  has a toggle that adds `.dark` to the document.

## Using the system

1. Load `tokens.css` before PatternFly overrides. Reference Locke tokens by name;
   never paste the hex.
2. Build screens from the component pages: copy the markup and class names, keep
   the state attributes.
3. Add a new token only when the repo gains one; add a new component page when
   the repo gains a component. Keep values exactly as the source files have them.
