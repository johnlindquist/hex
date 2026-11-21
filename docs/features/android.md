# Dictate with your own keyboard on Android

Internal ARM64 debug app, Android 13+. The Android settings and Accessibility services are independent of the desktop listener and the iOS keyboard. [Build and device setup](../../android/README.md#build-test-and-install) includes model provisioning and recovery after uninstall.

```ts
Prepare Hex on the phone                              // android.setup
├── Install the debug APK → tap Download in Setup for a pinned, SHA-256-verified Parakeet TDT model
├── Open Hex → grant Microphone and enable dictation in Android Accessibility
├── Try Hex or General → tap the scratchpad to test with your current keyboard
├── Dictation setup → import the four offline model files from Android’s document picker as an offline fallback
├── General → check readiness, copy or clear scratchpad text
├── Audio & language → choose Auto or a Parakeet language for formatting, not recognition
├── Always use phone microphone → prefer built-in input when available
├── About → open Privacy Policy, Support or offline open-source notices
└── Labs → Run setup again to revisit the three onboarding steps

Tap the floating mic                                  // android.dictate
├── Speak → local Parakeet recognizes phrases → insert at the original cursor
├── Finish → deliver remaining words; never press Send
├── ↶ Undo last dictation → remove the last confirmed insertion only while the editor and text still match
├── Cancel → discard unfinished work, not text already inserted
└── Change editor → do not redirect pending words to the new field
```

**Optional Hex Keyboard** (`android.keyboard`): In General, open Keyboard settings to enable Hex in Android, then Switch keyboard to select it. Full keyboard offers seven layouts (Automatic follows the device language), space-triggered autocorrect, key pop-ups, haptics and local dictation. In English, a space fixes known typos, missing apostrophes, and lowercase `i`; an immediate backspace restores the original and suppresses that correction for the session. One-edit matches against common words and custom vocabulary appear in the suggestion strip rather than being applied automatically. Drag the spacebar to move the cursor; with Shift held or active, drag to extend or shrink a selection. Dictate with text selected to replace it. For 12 seconds after a confirmed phrase, Undo dictation restores the original text if the cursor and replacement still match. Dictation bar replaces the letter keys with voice and editing controls. Labs › Emoji keyboard adds the searchable emoji panel and contextual suggestions; it starts off. The floating MicStrip hides while Hex Keyboard is visible and remains available with your system keyboard. Neither enabling the keyboard nor switching modes starts recording.

Labs › Live punctuation drums (off by default) covers the letter keys with six pads while recording. A tap inserts a mark at the current dictation cursor; undelivered phrases append after it, including the last phrase after Finish. Cancel keeps already inserted marks and words. `LivePunctuationTest` checks text edits, not device layout or editor delivery.

Backspace deletes the selection or a Unicode code point in a text editor. Raw editors and editors without text before the cursor get a Delete key event instead, including on each accelerated Backspace repeat. Return sends an Enter key event to raw editors without a labeled action. `TerminalDeleteTest` checks these routing decisions, not delivery to a running terminal.

General’s **Dictate & copy** and the Quick Settings tile offer dictation without a focused text field. Finish to copy; cancel to leave the clipboard unchanged. An ongoing dictation notification provides controls while recording.

Sources: [manifest and permission boundary](../../android/app/src/main/AndroidManifest.xml), [dictation service configuration](../../android/app/src/main/res/xml/bubble.xml), [speech dependencies and build target](../../android/app/build.gradle). The [push script](../../android/scripts/push-model.sh) checks SHA-256 on the host and device. Those checks establish file identity, not that a particular phone can load the model or that a particular app accepted its output.

```ts
Choose how words come out                             // android.output
├── Choose Polished, Casual, Notes or Verbatim → style the result locally
├── Spoken @ handles and email addresses → normalize supported phrases locally
├── Custom vocabulary → add or remove preferred spellings; switching it off preserves saved terms
├── Dictation learning (opt-in) → learn a single-word edit after two matching sightings; remove or clear learned fixes
├── History → search as you type and copy a retained dictation
└── History → choose Off, seven days or 30 days; distinct from unfinished drafts

Enable a Lab (each starts off)                        // android.labs
├── Recovery drafts → keep encrypted checkpoints for an interrupted take
├── Clean up with Gemma → review with a separately installed local model
├── Words on screen → separately enable the hint service and approve a window read
└── Clean up what I say → apply localized spoken cleanup rules without loading Gemma
```

Sources: [Android core settings and Lab registry](../../android/app/src/main/java/anomaly/hex/android/core), [hint service configuration](../../android/app/src/main/res/xml/hint_window.xml), [optional cleanup model](../../android/scripts/cleanup-model.sh). File provisioning alone does not enable recovery, read a window or prove cleanup quality.

```ts
Inspect or drive a debug session over ADB             // android.control
├── describe / status → available operations and current identities
├── start with target → stop or cancel with the same target and session
└── Stale identity → inspect again; never switch destination as an automatic retry
```

[ADB contract](../../android/README.md#adb-agent-control). The manifest requires `android.permission.DUMP` for the receiver. Available host check: `cd android && ./gradlew :app:testDebugUnitTest`. Native checks still require a consenting device user, a real permission grant, a supported editor and independent inspection of the inserted text. No physical phone, OEM compatibility, battery, or latency result is claimed here.
