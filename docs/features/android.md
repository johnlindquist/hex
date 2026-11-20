# Dictate with your own keyboard on Android

Internal ARM64 debug app, Android 13+. The Android settings and Accessibility services are independent of the desktop listener and the iOS keyboard. [Build and device setup](../../android/README.md#build-test-and-install) includes model provisioning and recovery after uninstall.

```ts
Prepare Hex on the phone                              // android.setup
├── Install the debug APK → push the verified Parakeet files into private storage
├── Open Hex → grant Microphone and enable dictation in Android Accessibility
└── Keep the current keyboard → focus a supported, non-password text editor

Tap the floating mic                                  // android.dictate
├── Speak → local Parakeet recognizes phrases → insert at the original cursor
├── Finish → deliver remaining words; never press Send
├── Cancel → discard unfinished work, not text already inserted
└── Change editor → do not redirect pending words to the new field
```

Sources: [manifest and permission boundary](../../android/app/src/main/AndroidManifest.xml), [dictation service configuration](../../android/app/src/main/res/xml/bubble.xml), [speech dependencies and build target](../../android/app/build.gradle). The [push script](../../android/scripts/push-model.sh) checks SHA-256 on the host and device. Those checks establish file identity, not that a particular phone can load the model or that a particular app accepted its output.

```ts
Choose how words come out                             // android.output
├── Choose Polished, Casual, Notes or Verbatim → style the result locally
├── Custom vocabulary → prefer the spellings you supply
└── History → choose Off, seven days or 30 days; distinct from unfinished drafts

Enable a Lab (each starts off)                        // android.labs
├── Recovery drafts → keep encrypted checkpoints for an interrupted take
├── Clean up with Gemma → review with a separately installed local model
├── Words on screen → separately enable the hint service and approve a window read
└── Clean up what I say → apply spoken cleanup rules without loading Gemma
```

Sources: [Android core settings and Lab registry](../../android/app/src/main/java/anomaly/hex/android/core), [hint service configuration](../../android/app/src/main/res/xml/hint_window.xml), [optional cleanup model](../../android/scripts/cleanup-model.sh). File provisioning alone does not enable recovery, read a window or prove cleanup quality.

```ts
Inspect or drive a debug session over ADB             // android.control
├── describe / status → available operations and current identities
├── start with target → stop or cancel with the same target and session
└── Stale identity → inspect again; never switch destination as an automatic retry
```

[ADB contract](../../android/README.md#adb-agent-control). The manifest requires `android.permission.DUMP` for the receiver. Available host check: `cd android && ./gradlew :app:testDebugUnitTest`. Native checks still require a consenting device user, a real permission grant, a supported editor and independent inspection of the inserted text. No physical phone, OEM compatibility, battery, or latency result is claimed here.
