# Hex for Android

**Status:** Development APK (`anomaly.hex.android`) for ARM64 devices running Android 13 (API 33) or later. Dictation uses an Accessibility floating mic and on-device Parakeet. This is not a Play Store release or the desktop Rust runtime.

## Capabilities and architecture

- **Keep your keyboard.** Enable Hex's dictation service in Android Accessibility, grant Microphone, then focus a supported text editor. The service exposes a floating mic without replacing Gboard, a hardware keyboard, or another input method. Unlike the [iPhone keyboard](../ios/README.md), Hex need not own a keyboard layout, autocorrect or the user's typing. The trade-off is an explicit Accessibility grant and dependence on each editor exposing a usable input connection. Password fields are not a dictation target.
- **Speak and insert.** Tap the mic to start. `AudioRecord` supplies 16 kHz mono audio; sherpa-onnx runs the local Parakeet TDT model. Completed phrases go into the original editor at its cursor. Finish delivers the last phrase; cancel does not undo words already inserted. Hex does not press Send. A changed editor is not permission to insert into a new one.
- **Choose the result.** Output styles are Polished (default), Casual, Notes and Verbatim. Custom vocabulary is for preferred spellings. History retention is separate from recovery: Off, seven days (default), or 30 days. Recovery is for *unfinished* work and is off by default.
- **Private processing.** The app manifest removes Internet permission. Downloads below run on the development computer. Installing a model does not grant microphone or Accessibility access, enable a Lab, or start inference.

### Labs

Each switch starts off; there is no master switch.

| Lab | What turning it on permits |
| --- | --- |
| Recovery drafts | Keep encrypted checkpoints so interrupted takes can be retried. Turning this off removes drafts. |
| Clean up with Gemma | Review an on-device cleanup using the separately provisioned Gemma weights. Review is not approval to replace text. |
| Words on screen | With separate Accessibility approval on Android 14+, request one window image for local spelling hints during review. Not continuous screen capture. |
| Clean up what I say | Apply spoken cleanup rules before formatting rather than running Gemma. |

## Build, test and install

Use an Android SDK with platform 35 and platform-tools (`adb`), a Gradle-compatible JDK, and a debuggable ARM64 target. From the repository root:

```sh
cd android
./scripts/fetch-deps.sh
# Offline alternative: verify the same files from an existing checkout
# ./scripts/fetch-deps.sh --from-local ~/dev/rea-moshi/local-voice
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb devices -l
SERIAL=YOUR_DEVICE_SERIAL
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
./scripts/push-model.sh "$SERIAL"
adb -s "$SERIAL" shell am start -n anomaly.hex.android/.ui.HexHomeActivity
```

The fetch checks the pinned SHA-256 for sherpa-onnx 2.0.1 (`app/libs/sherpa.aar`) and four files in `model/`. The default APK excludes the speech weights. The push checks both ends, skips matching files, and promotes verified files in `no_backup/models/parakeet-tdt-0.6b-ultra-int8/` through `run-as`. Stop dictation before provisioning. Uninstalling removes these private files; push them again after reinstalling. See [model notices](app/src/main/assets/notices/models.txt).

Only for **Clean up with Gemma**, obtain the [pinned Gemma 4 E2B LiteRT-LM file](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/3d48132a00cf76de57d926de30f5c5c5ed9b8c0d/gemma-4-E2B-it.litertlm). Allow another 2.6 GB on the device:

```sh
./scripts/cleanup-model.sh "$SERIAL" install /path/to/gemma-4-E2B-it.litertlm
./scripts/cleanup-model.sh "$SERIAL" status
```

`install` checks both ends before promoting `no_backup/gemma-4-E2B-it.litertlm`; `status` lists the file, not inference readiness. Neither script works with a non-debuggable package.

## ADB agent control

Use an explicit broadcast to `.control.HexControlReceiver` on the chosen device. The receiver requires `android.permission.DUMP`; it is a shell testing interface, not an unauthenticated recording API. Start only with the user's consent, an eligible field focused, the service enabled and the microphone granted.

```sh
control() { adb -s "$SERIAL" shell am broadcast -n anomaly.hex.android/.control.HexControlReceiver --es op "$@"; }
control describe
control status
control start --es target "$TARGET"                   # target from status
control stop --es target "$TARGET" --es session "$SESSION"  # session from start
```

Use the installed receiver's `describe` for available operations. Inspect its result, not just `am`'s exit status. On a stale target or session, stop and inspect; do not retry against whichever app is now in front. Status is for state and opaque identities, not reading the focused field. A successful control response is not proof the destination received text.

## Verification limits

Host unit tests exercise policy, not Android permission dialogs, mic quality or insertion in a real application. To verify end to end, complete setup on a device, focus a disposable field, dictate, and compare the field itself; repeat after changing focus before delivery. A working emulator or an upstream Inkvoice run is not proof on a physical phone. See the [Android feature map](../docs/features/android.md).
