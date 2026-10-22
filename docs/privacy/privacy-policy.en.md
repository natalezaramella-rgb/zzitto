# Privacy Policy – Zzitto

*Added on 22/10/2026 at 11:51 (Italian time). References: Spec §1, §5.1-bis, §5.6, §8.1, §8.2, §10. Version 1.0.*
*Text intended for publication (web page linked from the app and the store listings). Before publishing: fill in the
`[[TO BE COMPLETED]]` fields and have it reviewed by a privacy advisor. The Italian version prevails in case of discrepancy.*

**Last updated:** [[TO BE COMPLETED: publication date]]

This policy explains how the **Zzitto** app for Android and Windows handles data, under Articles 13 and 14 of
Regulation (EU) 2016/679 (GDPR) and the Italian Privacy Code (Legislative Decree 196/2003).

**In short:** Zzitto processes your voice and text **only on your device**. No audio and no dictated text is ever sent
to us or to third parties. We have no servers and use no advertising, usage analytics or profiling. The only internet
access is used, during the free trial, to check through Google Play or the Microsoft Store whether you have bought the app.

## 1. Data controller

Zaramella Consulting Srl
Registered office: [[TO BE COMPLETED: full address]]
VAT number: [[TO BE COMPLETED: VAT number]]
Privacy contact email: [[TO BE COMPLETED: contact email address]]

The controller has not appointed a Data Protection Officer, as the conditions of Art. 37 GDPR do not apply.
[[TO BE COMPLETED: confirm or name the DPO]]

## 2. How Zzitto works and which data it touches

| Data | What happens | Where it stays | Leaves the device? |
|---|---|---|---|
| **Voice audio** | Recorded from the microphone only while you dictate, transcribed by the Whisper model on the device | Working memory (RAM) only; deleted right after transcription, never written to storage | **No** |
| **Dictated text** | Corrected, rewritten or translated by the Phi-4-mini model on the device and inserted into the text field you are using | In the text field of the app you are typing in; if you turn history on, also in Zzitto's encrypted store | **No** |
| **Text selected for voice rewriting** | Read only when you select text and ask by voice to rewrite it; the result replaces the selection | Memory only, during processing | **No** |
| **Dictation history** (optional) | Your latest dictations (raw and corrected text), so you can look them up | Encrypted store on the device; can be turned off, deleted with one tap, retention configurable | **No** |
| **Personal dictionary and voice shortcuts** | Words, names and texts you enter, used to improve recognition | Encrypted store on the device | **No** |
| **Settings** | Language, correction mode, timings | On the device | **No** |
| **Clipboard** | Only when an app does not accept direct insertion: the text goes through the clipboard and the previous content is restored | System clipboard, for a moment | **No** |
| **Licence status** | Result of the purchase check | On the device, encrypted | See §3 |
| **Trial start date** (Android only) | Saved with Google's Block Store service | On the device and in your Google account backup | See §4 |

Zzitto does **not** collect: name, email, contacts, location, advertising IDs, usage data, analytics or crash reports.
It does not ask you to create an account.

## 3. Licence check during the free trial

- **Android:** the app is a free download and is unlocked forever with a one-time in-app purchase on Google Play
  ("Unlock Zzitto"). During the 30-day trial, at every launch Zzitto asks the Google Play Billing library whether the
  purchase has been made.
- **Windows:** the app is sold on the Microsoft Store with a 30-day free trial managed by the Store. During the trial,
  at every launch Zzitto reads the licence status through the Microsoft Store APIs.
- We send no data of our own in this check: the request goes through the store app installed on your device, which uses
  your Google or Microsoft account. We never receive your payment details or your account.
- Without a connection the trial simply continues. Once the purchase is confirmed, the unlock is stored encrypted on the
  device and **Zzitto never connects to the internet again**.

Google (Google Ireland Ltd) and Microsoft (Microsoft Ireland Operations Ltd) process purchase data as independent
controllers under their own policies: https://policies.google.com/privacy — https://privacy.microsoft.com/privacystatement

## 4. Trial start date on Android (Block Store)

To prevent the trial from restarting after uninstalling and reinstalling, Zzitto stores **only the trial start date**
with Google Play services Block Store. It stays on the device and, if Google backup is on, is saved in your Google account
backup with end-to-end encryption where the device supports it (Android 9 or later with a screen lock). It contains
nothing else: no audio, no text, no information about how you use the app.

## 5. Permissions

**Android**

| Permission | Why |
|---|---|
| Microphone (`RECORD_AUDIO`) | To hear your voice while you dictate. The microphone is active only after you tap the icon. |
| Accessibility service | **Only** to detect which text field is active, show the microphone icon next to it, insert the dictated text and read the text you select for voice rewriting. Zzitto does not read the rest of the screen, does not log what you type on your keyboard, and does not store or transmit other apps' content. |
| Internet | Only for the purchase check during the trial (§3). |
| Battery optimisation exemption (optional) | To stop the system from closing the dictation service. |

Before the accessibility service is enabled, the app shows a screen explaining these uses and asks for your consent.

**Windows:** microphone; internet only for the Store licence; UI Automation and keyboard input simulation to insert text
into the active field; clipboard as a fallback.

## 6. Purposes and legal bases

| Purpose | Data | Legal basis |
|---|---|---|
| Providing dictation, correction, rewriting and translation | Audio and text, processed on the device only | Performance of the licence contract (Art. 6(1)(b) GDPR) |
| History, dictionary, shortcuts | Data you choose to save | Performance of the contract; optional features you turn on |
| Purchase and trial check | Licence status, trial start date | Performance of the contract (Art. 6(1)(b)) and legitimate interest in preventing trial abuse (Art. 6(1)(f)) |

Since audio and text never leave the device, the controller has no access to them and does not store them.

## 7. Retention

- Audio: not retained (working memory only, deleted after transcription).
- History: for the period you choose in settings, until you delete it or uninstall the app.
- Dictionary, shortcuts, settings, licence status: on the device until you delete them or uninstall the app.
- Trial start date (Android): in Block Store as long as the app backup remains in your Google account.

## 8. Recipients and transfers abroad

The controller does not disclose data to third parties or transfer it outside the European Union. Google and Microsoft may
process purchase and backup data outside the EU as independent controllers, with the safeguards described in their policies.

## 9. Security

Encrypted stores with keys held in the Android Keystore (Android) or Windows system protection; no audio or dictated text
in diagnostic logs; AI models verified by SHA-256 fingerprint; no secrets in the code. Details are in the project's
ISO/IEC 27001, 27017 and 27018 compliance annex.

## 10. Your rights

You have the rights of access, rectification, erasure, restriction, portability and objection (Arts. 15–21 GDPR).
Since data stays on your device, you can exercise them directly in the app: view and delete history, edit the dictionary
and shortcuts, turn history off, uninstall the app. For any request write to [[TO BE COMPLETED: contact email address]].

You can lodge a complaint with the Italian Data Protection Authority (https://www.garanteprivacy.it) or the supervisory
authority of your country.

## 11. Children

Zzitto is not intended for children under 14.

## 12. Artificial intelligence

Zzitto uses AI models (Whisper for transcription, Phi-4-mini for correction, rewriting and translation) that run entirely
on the device. The output may contain mistakes: review it before sending. No data is used to train models.

## 13. Changes

Any changes to this policy will be published on this page with the update date and, if significant, announced in the app.
