# Visual references — 2026-10-28

This second refinement responds to the request to remove the promotional, AI-generated-looking presentation while preserving the already-approved control positions and flows. References inform general interaction and information hierarchy, not app identity or reproduced artwork.

## Reference evidence and applied patterns

| Official source                                                                                                                                                             | What was observed                                                                                                                   | Applied here                                                                                                                                                                                                                            |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [KakaoNavi service](https://www.kakaomobility.com/service-navi), CarPlay example                                                                                            | Map-first navigation, compact grouped journey information, functional labels without a marketing hero                               | Kept the right-side map and compact arrival metrics. Replaced slogan headings with 목적지 / 이동 계획 / 이동 중.                                                                                                                        |
| [TMAP 2026 home and place-screen announcement](https://www.tmapmobility.com/story/pr/detail/223)                                                                            | Top destination search, concise Korean labels, gray/white grouping, one clearly emphasized blue destination action                  | Preserved the top destination selector and all primary-button positions; adopted short Korean headings, quiet gray surfaces and blue primary actions. The selector still opens only the existing example destinations, not live search. |
| [NAVER Map service](https://www.navercorp.com/service/map), official product screenshot                                                                                     | Pale gray map ground and buildings, white roads, restrained green parks/blue water; short place labels and monochrome utility icons | Used a neutral schematic-map palette and quieter icons; retained the existing locally authored schematic geometry. This is not geographic map data.                                                                                     |
| [Google for Cars Navigation template](https://developers.google.com/cars/design/create-apps/apps-for-drivers/templates/navigation-template), official template illustration | Charcoal dark surfaces, subtle separating edges, strong route accent, large distance and compact ETA grouping                       | Used #121418 dark canvas with restrained borders, flat summary panels and blue route/action emphasis. This is a template reference, not a claim that the image is a production Google Maps screen.                                      |

The official screenshots and template illustration were visually reviewed. No reference logos, screenshots, photos, illustrations, fonts or third-party map assets are included in this repository or published app.

## Changes

- Removed English marketing eyebrows, decorative leaf/quote artwork, greeting/date filler and INTERACTIVE DEMO/Pleos concept chrome.
- Replaced the home slogan slab, in its existing position, with two practical empty-state rows: arrival estimate and parking/walking comparison.
- Flattened gradient/shadow-heavy panels; reduced corner radii and nested visual emphasis without relocating navigation or actions.
- Kept both light and dark themes. Blue denotes actions, selection and route; warning colors remain semantic.
- Kept app touch targets at least 56px and removed automatic map movement. The existing explicit demo-update button remains.
- Kept concise mock-data disclosure and the vehicle-control, sharing-consent, location and notification boundaries. Details remain in the documentation drawer.
- No domain/state changes, external dependencies or network services.

## Verification

- `npm test`: all existing behavior tests pass, including consent, cancellation, route review and driving restrictions.
- Every JavaScript module passes `node --check`.
- Added a render regression test for functional Korean screen headings and absent English marketing eyebrows.
- Light and dark home screens and the driving summary were checked in Chromium; navigation and the start-trip flow remained functional.
