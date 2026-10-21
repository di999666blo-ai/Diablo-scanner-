# Diablo Scanner verification checklist

## Automated CI checks
- Check that the fast Timed Signal analyzer remains the primary path.
- Check that optional SmolVLM assist is wired to the Timed Signal setting.
- Run Android unit-test tasks and compile the debug APK.
- Validate the APK ZIP structure, package ID, app label, bundled SmolVLM files, and native inference binaries for ARM64 and ARMv7.
- Confirm the launcher manifest references the bundled vector icon.

## Required phone acceptance tests
These require installing the newly built APK on a physical Android phone; CI cannot prove overlay behaviour or real inference on the target device.

1. Install and launch DIABLO SCANNER; confirm the Diablo D launcher icon appears.
2. Grant overlay and screen-capture permissions; switch to a broker chart and confirm the floating D bubble stays visible.
3. Tap scan once and confirm a scan status/result appears; repeat with a non-chart screen and expect WAIT.
4. Run Timed Signal with SmolVLM assist OFF; confirm the fast analyzer completes the observation window and saves history.
5. Enable optional SmolVLM assist; confirm fast sampling completes first, then the optional review runs once at the end.
6. Analyze a saved chart with assist OFF and ON; confirm the same mode routing is used.
7. Switch to Scalping; confirm SmolVLM is the only currently packaged model and do not present Kronos as active.
8. Verify mode switching, duration/timeframe settings, history, and manual WIN/LOSS recording.
9. Confirm no automated Buy/Sell action occurs.

## Known unverified items
- Kronos is not bundled in this Android project. The official model expects OHLCV/time-series input and a compatible tokenizer/runtime; this app currently extracts pixels from a screen capture, not broker OHLC data.
- Actual device inference speed, RAM usage, and overlay persistence still need a physical-device run.
