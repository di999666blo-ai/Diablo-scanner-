# Diablo Scanner — Accepted UI and Trading Workflow Requirements

Status: requirements committed for implementation; this document does not claim the features below are already implemented.

## Visual design
- Use the approved black/red Diablo visual direction across the app.
- Keep the Diablo launcher icon, floating D bubble, black/red scanning-ray animation, and an expandable, scrollable bubble panel.
- Ensure the panel remains usable on Android 8 and small phone screens; buttons must not be pushed off-screen.
- Show meaningful live progress rather than leaving the user waiting blindly.

## Timed Signal mode
- Keep this mode independent from the deeper SmolVLM workflow so it can use lightweight image processing.
- Aim for a 10-second scan interval where the device can sustain it; never promise a signal every 10 seconds.
- Analyse the currently visible chart and report UP, DOWN, or WAIT.
- Use the user-selected trade duration as the signal expiry. Scanning interval and signal duration are separate settings.
- Continue monitoring where possible, explain invalidation, and do not silently extend a signal.

## AI Scalping mode — multi-timeframe workflow
- Keep the bundled SmolVLM model pair and native inference runtime as the deep visual-analysis engine, if packaging and on-device execution are verified.
- Use a lightweight chart-feature engine for quick checks and monitoring; do not require full SmolVLM inference on every 10-second check.
- Default guided timeframe sequence: 4H -> 15M -> 1M. Make the sequence configurable later.
- Ask the user to change the broker chart timeframe at each stage and tap a confirmation button. Never assume the chart changed solely because the user tapped OK; validate what can be validated and clearly state uncertainty.
- Display each stage, its status, elapsed time, findings, and next action. Persist each completed timeframe result within the current analysis session so it does not need to be reanalysed without reason.
- Aim to finish the analysis session within five minutes, excluding time while waiting for the user to change the chart. This is a target, not a guarantee. If processing times out, fails, or exceeds the target, report it honestly; never show a fabricated completion.
- After the 1M stage, use adaptive decision C: provide a setup when evidence is sufficient, otherwise keep monitoring for an entry trigger, or return WAIT/no setup when the evidence remains weak or time expires.
- Proposed setup may include direction, entry, stop loss, take profit, risk-to-reward, rationale, invalidation conditions, and selected holding duration. Derive levels only when the screenshot supports them; otherwise label them unavailable/estimated or request better chart visibility. Risk-to-reward such as 1:3, 1:4, or 1:6 is a possibility, not a forced target or promise.
- The model must never claim it ran or confirmed a setup if inference failed or timed out.

## Shared requirements
- Apply the selected trade duration in Settings to the relevant mode; keep analysis timeout/session deadline distinct from holding duration.
- Support live screen capture and saved-chart analysis, with clearly labelled source.
- Keep history, chart-pattern memory, and manual outcome tracking; avoid duplicate/spam history entries.
- Signals only: no automatic order placement or broker clicking.
- Show that confidence is experimental and not a verified probability of winning.
- Preserve the app's ability to run on the user's Android 8 / 4 GB RAM device as far as performance allows; test real inference and memory use before claiming support.

## Acceptance checks before recommending APK download
1. Confirm the actual UI matches the approved design on-device or via screenshots.
2. Verify the D bubble remains visible and the results/settings panel scrolls.
3. Verify 10-second lightweight scan timing under realistic device load and safe handling when it cannot keep up.
4. Verify guided 4H -> 15M -> 1M progress, user confirmations, session persistence, timeout and failure states.
5. Verify both model files and native runtime are present in the APK and inference actually runs on-device.
6. Verify configurable duration, saved-chart analysis, history, and manual outcome tracking.
7. Run the Android build and inspect the resulting APK before declaring it ready.