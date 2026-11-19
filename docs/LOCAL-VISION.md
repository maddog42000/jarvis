# Local screen vision (development branch)

This branch adds a first local-perception layer to the native Android phone operator.

## What it does

- Reuses the screenshot already captured by the existing AccessibilityService path.
- Runs bundled Google ML Kit Latin text recognition on-device.
- Adds recognized text and screenshot-relative bounds (0..1000) to the operator's next model prompt.
- Runs OCR even when the selected model cannot accept images; only image-capable models receive the screenshot bytes.
- Skips OCR when the accessibility snapshot contains a password field or a focused editable field.
- Heuristically redacts credential-labelled OCR lines and email addresses; this is not a guarantee that all sensitive text is detected. Bounds are approximate and are for visual orientation, not a trusted action receipt.
- Decodes pixels in memory and does not write screenshots or OCR output to the task journal or persistent memory. **OCR text is included in the next operator prompt and is sent to the task's configured model provider**; image-capable routes also receive the screenshot as before.

## What it does not do yet

OCR does not recognize arbitrary icons, infer game state, or independently authorize taps. Canvas-rendered game controls with no legible text still need icon/template recognition or visual-model assistance. OCR coordinates are advisory; existing action-risk policy, stale-observation checks, and verification remain authoritative.

## Dependency

The Android plugin bundles `com.google.mlkit:text-recognition:16.0.1`. Google documents the bundled option as including the model in the app rather than downloading it at first use. See [ML Kit text recognition for Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).

The upstream documentation estimates approximately 4 MB of APK size increase per script per architecture. Device build size, latency, and real-screen OCR accuracy still need measurement on the target phone.

## Validation plan

1. Run the native Android unit tests.
2. Run the existing JS typecheck, lint, contract tests, and Android debug APK build.
3. On a physical device, test ordinary app screens, game HUD text, screen rotation, secure/password screens, and rapid screen changes.
4. Compare operator task latency and model input size with OCR enabled versus disabled.
5. Only after that, add app-specific visual templates and screen profiles.
