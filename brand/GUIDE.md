# rostdav-mfa brand guide

rostdav-mfa is the phone half of Rostdav's sign-in: a self-hosted authenticator that approves a browser login with a tap and your fingerprint. Its look is Rostdav's look, signed in safely: the same folder, the same palette, the same type, with a lock on the front plate.

This guide is the plain-language version of `tokens.json`. The token tables below are generated from that file (`python3 brand/generate.py`) and checked in CI, so they cannot drift from the app.

## Logo

- The **mark** is the Rostdav folder (Oxid back plate, Rost front plate, Kupfer highlight) with a lock on the front plate and three rust flakes leaving the folder. Files: `brand/logo/mark.svg`, `mark-negative.svg` (on Rost), `mark-mono.svg` (print, stamp, engraving).
- The **app icon** is the mark on a dark Eisen squircle, like Rostdav's own icon: `brand/logo/icon.svg`. On Android it is an adaptive icon with a monochrome layer for themed icons.
- The **wordmark** is `rost` + `dav` + `-mfa`, lowercase, Space Grotesk Bold, tight tracking. `rost` and `-mfa` take the text colour, `dav` takes the accent: Rost on light, Kupfer on dark. Files: `wordmark.svg`, `lockup-light.svg`, `lockup-dark.svg`.
- Keep clear space of half the mark's height on every side. Do not go below 24 dp for the mark alone or 96 dp wide for a lockup.
- Never recolour the mark, stretch it, drop the lock, or set the name as `RostDAV MFA`, `Rostdav-MFA` or `rostdavMFA`. The name is `rostdav-mfa`.
- The files in `brand/logo/` are generated: `python3 brand/logo/make_mark.py`, then `brand/logo/make_wordmark.py` (needs fontTools).

## Colour

Warm, dark-first, with one accent family. The Rostdav names are Eisen (iron, near black), Oxid (oxide, deep red-brown), Rost (rust), Kupfer (copper), Glut (ember) and Elfenbein (ivory).

- **Dark** is the default look: paper `#0B0C10`, glass cards on soft orange glow orbs, Kupfer as the accent, ivory text.
- **Light** uses Elfenbein paper, white cards, Rost as the accent and Eisen text.
- **Action** (filled buttons) is white on rust in both themes. **Accent text** (links, selected tab labels) uses `rost600`, which is chosen to read at 4.5:1 on the paper of its theme.
- **Ok** is a teal, **danger** a red that stays clearly apart from the rust brand colour.
- Every text and background pair is checked against WCAG AA by `brand/check_contrast.py`. A token that fails is changed, not excused.

<!-- tokens:start -->
### Colour

| Token | Light | Dark |
| --- | --- | --- |
| `rost500` | `#C2491D` | `#E8783A` |
| `rost600` | `#B8441A` | `#F2A65A` |
| `rost700` | `#7A2A12` | `#F7C589` |
| `action` | `#B8441A` | `#C2491D` |
| `onAction` | `#FFFFFF` | `#FFFFFF` |
| `ink` | `#15171C` | `#F4EFE8` |
| `slate` | `#4F4842` | `#C2BAB1` |
| `paper` | `#F4EFE8` | `#0B0C10` |
| `surface` | `#FFFFFF` | `#1B1D24` |
| `ok` | `#1F5F5A` | `#6CC7BA` |
| `danger` | `#B3261E` | `#F2B8B5` |

### Gradients

| Token | Value |
| --- | --- |
| `markStart` | `#E8783A` |
| `markEnd` | `#C2491D` |
| `iconStart` | `#2A2D35` |
| `iconEnd` | `#15171C` |
| `tabStart` | `#CF5524` |
| `tabEnd` | `#9A3412` |

### Type scale

| Name | Size (sp) |
| --- | --- |
| `headline` | 28 |
| `title` | 20 |
| `body` | 16 |
| `label` | 14 |
| `caption` | 12 |

### Radii

| Name | Size (dp) |
| --- | --- |
| `chip` | 12 |
| `card` | 16 |
| `sheet` | 24 |
| `button` | 12 |
<!-- tokens:end -->

## Type

Space Grotesk for text, headings and the wordmark (400, 500, 600, 700). JetBrains Mono for one-time codes, the number-match digits and the small uppercase **eyebrow** labels above sections (11 sp, spaced). Codes use tabular figures so digits do not jump. The scale is the type table above; do not add sizes.

## Shape, glass and motion

- Radii: 12 for buttons and chips, 16 for cards, 24 for sheets, 28 for the icon. Elevation is tonal; only the floating bar and sheets cast a soft shadow.
- **Glass** is used on the Approve sheet and the floating bar, and as the glow-orb background of the main screens. It is a soft imitation of the Rostdav web UI: a translucent gradient with a light edge, without blur. Below Android 11, and when the user turned animations off, it is a solid surface. It will not look pixel-identical to the web UI, and does not need to.
- The **floating bar** is a 76 dp pill, at most 334 dp wide, 26 dp above the bottom edge. The selected tab is a rust gradient pill; the Requests tab carries a count badge (`99+` above 99). It is not shown on Approve, onboarding or reconnect.
- Countdown rings animate smoothly and turn red in the last five seconds. Everything respects "remove animations".

## Accessibility

- Touch targets are at least 48 dp; buttons are 56 dp, number choices 72 dp.
- Number choices are announced as "Number N". Countdown rings announce the time left, not the digits. Icons that carry no information are hidden from TalkBack.
- Nothing is clipped at 200% font size: Approve scrolls, titles end in an ellipsis, and the trailing value of a row always stays visible.
- The Approve screen blocks screenshots and the recents preview (`FLAG_SECURE`).

## Voice

Short and plain. Say what happened and what to do next. "This phone was removed on the server." Not "Authentication failure (code 17)." No exclamation marks, no emoji, no scare words. The app does not promise more than it does: the authenticator vault is called **planned** until it exists.

## Planned screens (the vault)

The Accounts list and Add account screens are designed but not built (sub-projects 2 and 3). The mock-ups in `brand/store/vault-mockups/` use sample data and are labelled as mock-ups.

## Store listing

Texts for F-Droid and Play are in `brand/store/en/` and `brand/store/de/`: a short description of at most 80 characters and a full description of at most 4000. The feature graphic and phone frames are in `brand/store/`. They show only what exists.
