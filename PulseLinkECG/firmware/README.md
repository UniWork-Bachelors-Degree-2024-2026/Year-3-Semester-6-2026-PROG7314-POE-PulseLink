# PulseLink recording and touchscreen firmware

## Nurse workflow

The PulseLink touchscreen starts with nurse access and Connections. An approved nurse at the device's site authorises its temporary code from Android; BLE pairing uses a separate code and does not grant clinical access. The nurse home provides patient selection, local clinical registration against an approved doctor, patient confirmation, a labelled R/L/COM guide, lead-off status and recording controls. PulseLink branding remains visible. Live ECG/BPM remains on the touchscreen only. Android shows status and completed reports.

Current permission lasts eight hours. After a full restart the screen reconnects and checks server time/access before exposing patient data. An already-authorised unit may work offline until expiry. Only previously loaded roster pages are cached, with two patient/doctor rows per page; an empty eligible page can still have a next page. Local registration synchronises before dependent recordings. Changed assignments are retained for review. Patients with a missing or ineligible primary doctor remain visible with an assignment-required state. Repairing an existing assignment requires internet, a current nurse shift and explicit confirmation; the API checks the patient version, site and doctor eligibility. The screen cannot begin a recording while this known assignment problem remains unresolved. The matching API must be published before the new assignment action works on a physical device. The current UI does not resolve historical recording conflicts or author doctor care messages.

Android remembers only the last approved device identifier per nurse account. On returning to the nurse view it checks the current approval with the API rather than asking for another code. The server returns only that nurse's own active shift and never extends its expiry. A temporary connection failure is shown as unavailable with a retry, not as proof that the shift ended. An expired shift or touchscreen sign-out requires fresh approval. This Android/API follow-up needs matching published server and app builds; its local tests do not establish a live phone result.

Private metadata uses AES-256-GCM, reserved NVS nonces, two verified file generations and a card tamper self-test. Cache filenames hash paging identifiers to remain bounded. Raw ECG journals remain pseudonymous and unencrypted; the prototype NVS key does not establish production flash or physical security. Bench recordings remain excluded from clinical upload.

Online recording uses a permission preflight which creates neither a session nor an upload permit. The screen saves estimated UTC capture-command time after preflight, so HTTPS waiting time is not counted as capture time. Actual sample timing is retained as journal offsets; the UTC command estimate is not an externally calibrated ADC timestamp. Completed metadata binds the original nurse/site/patient before raw upload. The server contract is defined by `PulseLinkAPI/Transport/NurseWorkflow.cs`; Android consumes it through `NurseScreenApprovalRepository`.

The nurse build uses 4,096-byte screen UART RX and 512-byte TX buffers. The earlier 8,192/2,048 buffers, together with the new workflow state, left insufficient TLS allocation margin on the physical board. The smaller buffers and two-row caches restored observed authenticated health/event/authorisation requests. A fresh final 90-second disconnected bench run captured 22,500 samples over 22,500 slots, with zero missed slots, parser/storage faults or final queue backlog. C3 maximum reported scheduling lateness was 14 microseconds, not an external ADC timing measurement. Six observed concurrent/final API checks returned HTTP 200/TLS zero. BLE was off; minimum reported heap was 4,080 bytes. This short test does not establish production memory margin, clinical accuracy or BLE coexistence.

Local verification: native policy/protocol suite 6,290 checks; API suite 67 passed and two emulator tests skipped; Android 17 unit tests passed. Both targets compiled and were flashed with verified hashes. The user confirmed physical touch/Connections, Android-to-screen nurse approval, dummy patient/doctor selection, confirmation/lead guide and recording blocked by lead-off. Final startup reported a successful private-storage tamper self-test and authenticated reconnect. The user confirmed the final saved bench message, which follows full journal read-back, and responsive touch. The user reports publishing the revised API. End-to-end clinical capture/upload, further offline-recovery cases and real-phone BLE acceptance remain unverified. README changes remain for review; these figures do not establish clinical validation.

## Build requirements

Use Arduino ESP32 core **3.3.12**, LovyanGFX **1.2.36**, NimBLE-Arduino **2.5.2**, ArduinoJson **7.4.3** and the shared library under `PulseLinkECG/libraries`. Keep `PulseLinkScreen/build_opt.h`, which disables legacy BLE pairing throughout the NimBLE C and C++ sources. The C3 needs only the shared recording library; LovyanGFX, NimBLE and ArduinoJson are touchscreen dependencies.

Run from the repository root. Supply Arduino CLI's `--config-file` where necessary for your installed core configuration.

```powershell
arduino-cli compile --fqbn esp32:esp32:esp32c3:CDCOnBoot=cdc --libraries .\PulseLinkECG\libraries --build-path .\build\pulselink-c3 --jobs 4 .\PulseLinkECG\firmware\PulseLinkC3
arduino-cli compile --fqbn esp32:esp32:esp32:FlashMode=dio,PartitionScheme=min_spiffs --libraries .\PulseLinkECG\libraries --build-path .\build\pulselink-screen --jobs 4 .\PulseLinkECG\firmware\PulseLinkScreen
```

The display profile is ESP32 Dev Module, 4 MB flash, DIO, Minimal SPIFFS (1.9 MB APP with OTA/128 KB SPIFFS). Wi-Fi/TLS and BLE exceed the default 1.25 MB application partition; recordings remain on microSD. Changing the partition layout requires a full firmware upload, not an application-only OTA update. C3 requires USB CDC On Boot enabled. Identify the actual chip and port before upload. Native USB ports may change between bootloader and application.

## Wiring and power

Disconnect all USB power before changing wiring. Keep electrodes disconnected from people during laptop-powered bench tests.

| Connection | Wiring |
| --- | --- |
| C3 to screen | GPIO7 TX to screen IO35 RX; GPIO6 RX to screen IO22 TX; common GND |
| Screen cable IO21 | Disconnected and insulated; the board uses IO21 for its backlight |
| AD8232 supply | AD8232 3.3 V to C3 3V3; GND to C3 GND |
| AD8232 signal | OUTPUT to C3 GPIO0 |
| AD8232 lead-off | LO+ to C3 GPIO5; LO- to C3 GPIO4 |
| AD8232 enable | SDN to C3 GPIO3, driven HIGH |

The C3-to-screen UART uses 115200 baud, 8N1. C3 uses UART1 for the screen and native USB CDC for logging. C3 GPIO1 and GPIO10 are no longer assigned to an external serial link.

USB powers each board for bench tests. C3 supplies the AD8232 only. Do not join regulator outputs or power the touchscreen from C3 3.3 V. A single-source design needs suitable regulated distribution and a separate electrical review.

## Acquisition and storage

A dedicated task receives 4 ms timer notifications for nominal 250 Hz sampling. Raw 12-bit samples retain their scheduled index, actual monotonic timestamp and lead-off, clipping and timing flags. Missed slots remain explicit gaps. No catch-up samples are fabricated.

Ten samples form a CRC-protected UART packet. A bounded 128-packet queue gives about five seconds of buffering. Acquisition never waits for SD, display, BLE or networking. Exhausted buffering stops acquisition with an overflow reason rather than overwriting samples.

The screen creates a random 128-bit session identifier and opens a journal before requesting capture. It acknowledges the beginning before C3 starts sampling. Packets carry ordered sequences, a C3 boot identifier and session identifier. Accepted bytes are flushed before acknowledgement. Lost acknowledgements cause retries after 300 ms. Duplicate retries do not append duplicate data. CRC detects accidental corruption; it is not authentication or radio encryption.

Files under `/pulselink/` use these suffixes:

| Suffix | Meaning |
| --- | --- |
| `.open` | Recording active or final rename interrupted |
| `.ready` | Complete local bench file with matching totals, no index gaps and successful read-back |
| `.interrupted` | Faulted, incomplete, damaged or gapped recording retained for review |

On boot, open journals are validated without changing their bytes. Only a valid complete ending can become ready. Capture never resumes automatically. A changed screen boot cancels the C3's old transaction; a changed C3 boot interrupts the screen journal. Persistent card faults require inspection and restart. Startup scanning stops after 128 entries and locks new recording if maintenance remains necessary.

Consumer microSD and FAT cannot guarantee recovery from every power cut. Arduino `File.flush()` is the exposed acknowledgement boundary, not clinical durability certification. Real card-removal, slow-card and power-interruption tests remain outstanding. Interrupted files must not be silently discarded or presented as complete clinical ECGs.

Local completeness describes transport, not signal quality. A file can contain flagged lead-off or clipped samples. Ready does not mean uploaded, clinically authorised, converted to PDF or reviewed. UTC acquisition time, millivolt calibration and paper scale are not invented. The later uploader must combine small UART packets into stable API batches rather than exceed the server's 600-batch limit.

## BPM and display

Only the nurse touchscreen displays live ECG and BPM. PulseLink remains visible on boot, monitor and pairing views. Display decimation does not replace stored raw samples. Resistive touch axes are mapped to landscape orientation, median readings reject isolated noise, and a stable release is required before accepting a second press.

The prototype positive R-peak estimator uses baseline removal, smoothing, an adaptive threshold, a refractory period and RR bounds. It averages complete valid RR intervals within five seconds and requires at least two intervals. Display updates occur each second. Lead-off, clipping, timing discontinuity, settling, stale samples and missing beats clear the estimate. `--` means unavailable.

This is not a clinically validated QRS detector. Movement, noise and inverted signals can defeat it. `Leads: CONNECTED` only means neither AD8232 lead-off output is asserted. It cannot prove correct anatomical placement or all three contacts. There is one waveform and no respiration, SpO2 or blood pressure measurement.

## BLE mode and pairing

Pair the **touchscreen directly with a staff Android phone**. The C3 remains wired to the screen. BLE is off after boot. The monitor's Connections button opens Wi-Fi settings; select the BLE tab to open the pairing view without starting advertising.

Enable BLE while recording is idle, then tap Pair phone. The screen opens a 120-second advertising window and displays its own `PulseLink-XXXXXX` name and random six-digit passkey. Enter the code into the phone's system pairing dialogue. Leading zeroes matter. The code is never advertised, logged, stored in the journal or sent over UART. It disappears after successful authentication, expiry or disconnect.

NimBLE requires bonding, MITM protection and LE Secure Connections with display-only passkey entry. Legacy pairing is compiled out. Authentication requires encryption, authenticated pairing, bonding and a 16-byte key. Only one connection is allowed. A failed attempt closes the pairing window when disconnected. Opening another window requires a local action. Reopen pairing to reconnect a bonded phone.

Disable BLE stops advertising and disconnects the phone. The controller remains initialised to avoid repeated allocation and teardown; free and minimum heap are reported for bench monitoring without exposing the code. Enablement, pairing and bond removal are restricted to idle recording. Disablement remains available during capture.

Forget bonds requires a second confirmation tap within five seconds, removes saved phone bonds and leaves BLE off. The phone may also need to forget the device. Bond revocation is separate from the later server-side staff/device access policy.

GATT exposes three characteristics on service `b6f0a100-5664-4fb0-9f96-6f60754c4e4b`, all requiring an authenticated, encrypted link:
- Status characteristic `b6f0a101-5664-4fb0-9f96-6f60754c4e4b` (`READ | READ_ENC | READ_AUTHEN`): reports `PulseLink;device=<id>;c3_uart=<ok|timeout>;sd=<ready|check>;wifi=<0..3>;api=<0..5>;staff_transfer=<locked|unlocked>;queued=<n>`, keeping touchscreen Wi-Fi, API reachability, C3 UART and microSD states distinct.
- Control characteristic `b6f0a102-5664-4fb0-9f96-6f60754c4e4b` (`WRITE | WRITE_ENC | WRITE_AUTHEN`) and chunk characteristic `b6f0a103-5664-4fb0-9f96-6f60754c4e4b` (`READ | READ_ENC | READ_AUTHEN`): service background transfer of completed `.ready` clinical recordings on the display task while capture is idle (`recording == false`). No live ECG waveform notifications, live BPM streaming or remote recording start/stop controls are exposed. Pairing alone does not unlock clinical data: Android must first present the active nurse shift `grantId` and `nurseUid` matching the touchscreen's active shift (`op = "auth"`), after which `list`, `manifest`, ordered `batch`, idempotent `ack` and `complete` commands transfer completed recordings into Android's SQLite offline queue and write a local `.uploaded` receipt to prevent duplicate transfers. Idle BLE connections time out after five minutes (`IdleTimeoutMs = 300000`), and bonded phones can reconnect when BLE is enabled and idle.

## Verification and limitations

The active build targets are C3 acquisition and the touchscreen recorder. Compile both targets after changes; current hardware verification is recorded separately from the earlier baseline. The native C++ suite exercises the shared protocol, validator, estimator and pairing policy, including corrupt/fragmented frames, changed/duplicate retries, identity/order/timestamp/size bounds, a 22,500-sample journal, torn endings, gaps, overflow, synthetic BPM invalidation, passkey expiry including clock wrap, mode disablement and authentication prerequisites. Any C++17 host compiler can run it:

```text
g++ -std=c++17 -O2 -I PulseLinkECG/libraries/PulseLinkCore/src PulseLinkECG/tests/core_tests.cpp -o build/pulselink-core-tests
```

The earlier acquisition baseline verified C3 and touchscreen flash hashes. The user confirmed readability, Record-button operation and the saved message. A physical 90-second bench run reported 22,500 captured samples over 22,500 slots, no missed slots, no final queue backlog, no UART parser errors and no storage failure. C3 reported 12 microseconds maximum lateness relative to its schedule for this run, not externally measured ADC jitter or clinical accuracy. The saved message follows complete-file read-back validation.

The earlier BLE-focused two-board build compiled and uploaded with verified flash hashes. Physical testing confirmed the corrected button mapping, BLE enablement, advertising, and the touchscreen's own name/passkey display. That native suite passed 4,894 checks. A fresh 90-second recording captured 22,500 samples over 22,500 slots with no missed slots or gaps, zero parser errors and successful full-file read-back. Maximum reported scheduling lateness was 14 microseconds. Minimum reported screen heap was 150,552 bytes. BLE was off during this full run, with its controller still initialised; this does not verify connected-phone or concurrent Wi-Fi performance.

Real phone authentication, wrong-code rejection, physical timeout, bond revocation and connected-phone recording tests remain pending at the user's request. The earlier recording run does not verify the new BLE workload. Physical card-removal, disconnect, backpressure and reset recovery remain outstanding unless separately recorded. No meter or ECG simulator is available, so electrical safety, calibrated amplitude, lead-off behaviour and ECG/BPM accuracy are not certified.

## Wi-Fi and restricted API transport

From Connections, select Wi-Fi, scan for an ordinary 2.4 GHz network, choose its name and enter the password on the masked touchscreen keyboard. Save the field, then Connect. Saved settings reconnect after reboot with bounded retry delays. Forget removes the saved network and password. API settings use a separate view. PulseLink branding remains visible during entry. Credentials and touch coordinates are excluded from debug logs.

The API indicator becomes authenticated only after a successful device-specific health response. Wi-Fi association alone does not establish API access. HTTPS checks the server hostname and built-in CA bundle; redirects and insecure TLS are disabled. Network time must be obtained before certificate verification. Ordinary personal Wi-Fi and open networks are supported. Eduroam/PEAP and captive portals are not implemented: the university CA, expected server domain and confirmed EAP configuration are still required.

The administrator first assigns the displayed PulseLink device identifier to an active site. An authenticated administrator calls `POST /api/admin/devices/{id}/credential`; the random device token is returned once with `Cache-Control: no-store`, while only its SHA-256 hash is stored server-side. Enter that token in the masked Device key field. Rotation invalidates the old token, and DELETE on the same route revokes it. Do not put administrator passwords, Firebase service accounts or Blob keys on either microcontroller. Changing the API address clears the old token.

Device requests use `Authorization: Device identifier:token`. Device credentials cannot access staff routes. An approved nurse grants a time-limited upload permit for an existing session through `POST /api/access/recordings/{id}/upload-permit`. Each upload request rechecks device/site activity, nurse approval, patient assignment and the primary doctor. Permits expire within 72 hours. Device health, progress, batches, completion and paged permission events are under `/api/device-transport`. Event delivery is separate from nurse acknowledgement or clinical message reading.

An independent network task performs HTTPS without accessing the card. The display task alone reads recording files. Completed clinical journals are uploaded while capture is idle, in ordered batches of up to 250 samples with actual microsecond offsets, quality flags and journalled BPM. Unknown BPM remains zero/unavailable. The raw ADC values are not converted into calibrated millivolts. CRC and immutable API batch receipts support safe retries, including a lost acknowledgement. A `.sync` checkpoint records the accepted prefix and file length; it is checked against the journal before resuming. A validated `.uploaded` receipt follows server completion. Raw recordings are retained. Interrupted files, malformed journals and bench recordings are never promoted to clinical uploads.

The current capture buttons still produce bench-only files. Patient selection, authorised clinical start and offline clinical permissions require the nurse workflow implementation. Local API and synthetic journal tests verify transport behaviour, not a completed physical patient-to-cloud path. Live sample streaming during capture has not been enabled or load-tested.

Wi-Fi passwords and device tokens are stored in Preferences/NVS on this prototype without flash encryption. Physical access can expose them. Production provisioning, encrypted storage and appropriate device protection remain release work. The published API now accepts the enrolled screen for authenticated health and scoped event polling. Authorised clinical recording upload and live Blob verification remain pending. The generic BLE status service does not transfer patient records.

The current local native suite passes 6,269 checks, including clinical journal decoding, missing BPM snapshots, partial final batches, damaged/torn journals and repeatable checkpoint prefixes. The local API suite passes 58 tests; two Firebase emulator integration tests are skipped. These local results complement the Wi-Fi-associated bench results below. They do not establish live deployment, deliberate access-point loss recovery or concurrent HTTPS/BLE performance.

## Network and clinical boundaries

C3 networking remains disabled. The touchscreen owns Wi-Fi and HTTPS upload. Android SQLite transfer, landscape reports and private Blob access remain pending. Doctor/nurse messages will use the authenticated API over Wi-Fi and appear on the touchscreen. Android keeps session-status updates and completed reports rather than live telemetry.

The planned doctor patient chart will include prior visits, completed ECG reports and visit notes. Doctors review completed reports rather than Android live telemetry.

References: [AD8232 data sheet](https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf), [NimBLE security callbacks](https://h2zero.github.io/NimBLE-Arduino/class_nim_b_l_e_server_callbacks.html).


The updated C3 acquisition and Wi-Fi-capable screen builds were uploaded after chip identification, with flash hashes verified. The touchscreen uses the Minimal SPIFFS application layout specified above. Physical association, saved-network reconnect and Wi-Fi-associated recording results are recorded below. HTTPS upload load remains unverified. Azure publishing remains pending.


On-device Wi-Fi setup succeeded on the user's ordinary network. A controlled idle screen restart restored the saved settings and reconnected automatically. A subsequent Wi-Fi-associated 90-second bench recording completed 22,500 samples over 22,500 slots, with zero missed slots, zero final C3 queue backlog and 12 microseconds maximum reported scheduling lateness. The user confirmed the saved message (which follows complete journal read-back) and working touch. No patient electrodes were used. API authentication was unconfigured and BLE was off, so this does not establish concurrent HTTPS/BLE performance, calibrated ECG accuracy or live cloud upload.

The longer USB status line was found to exceed the default UART transmit capacity as the sample counter grew. The touchscreen configures a 512-byte debug transmit buffer before serial startup so its non-blocking logger can retain the full line. The diagnostic correction compiled and was uploaded with verified flash hashes on 6 November 2026. Startup logs confirmed saved Wi-Fi connected, C3 UART ready, microSD ready and zero parser/storage errors. The full 90-second run was not repeated for this logging-only change.


## Connection feedback

The Wi-Fi view gives field-specific password/API validation, router-reported authentication or handshake failures, unavailable networks, weak signal, timeouts and settings-storage failures. A handshake failure advises checking password and signal rather than asserting a definite wrong password. Scanned secured networks require a password; selecting a different network clears the old password from the entry field. Errors remain visible during retries and clear on successful connection or a new settings submission. Wi-Fi association and authenticated API reachability remain separate indicators.

New network settings are persisted after successful association. Failed credentials (`WifiIssue::Credentials`, `WifiIssue::Handshake` or `WifiIssue::Security`) do not replace the last working saved network, and the touchscreen automatically falls back to reconnecting its previously saved working network while keeping the credential warning visible. Forget explicitly clears the saved Wi-Fi fields. If storing settings fails, the interface warns that persistence is unavailable. BLE starts off after boot. BLE feedback covers unavailable service, pairing start failure, authentication failure/cancellation, code expiry, idle timeout and phone disconnect. A fresh local Pair phone action opens a new code window; neither the code nor password enters serial logs. Staff recording transfer remains locked until the connected phone presents the active nurse shift grant matching the touchscreen.

The updated native suite passes 6,277 checks, including open-network, minimum-length, maximum-length and hexadecimal PSK validation. Specific on-device wrong-password and phone pairing failure cases require physical acceptance checks; compilation and policy tests do not prove those paths on every router or phone.

The connection-feedback build compiled and was uploaded with verified flash hashes on 6 November 2026. Startup confirmed automatic saved-network association, C3 UART and microSD ready, zero parser errors and no storage failure. Wrong-password, deliberate network-loss and real-phone BLE failure/expiry checks remain manual acceptance work.

Credential entry includes a Show/Hide control for Wi-Fi passwords and device keys, with a character counter. Values are hidden on every new edit and after Save field or Cancel. Revealing a value affects only the local editor; the settings overview stays masked and serial logs never contain credentials.


## HTTPS memory and request diagnostics

Event reply storage grows only with received body bytes after the TLS handshake, within a 6 KB hard limit. It does not reserve a fixed 6 KB block before certificate processing or after the handshake. Each request explicitly closes its connection after consuming the bounded response, releasing TLS resources before parsing events. Certificate-chain and hostname validation remain enabled. The connection timeout is five seconds, with ten-second TLS-handshake and HTTP-read limits. A slower request remains pending until its bounded timeout; authentication failure and transport failure remain distinct states.

Redacted `PL_NET` diagnostics report a request counter, category (1 health, 2 events, 3 recording transport), HTTP/client result, negative TLS error, elapsed milliseconds, RSSI and largest available allocation after HTTP cleanup. They contain no credential, URL query, patient identity or payload. Result -105 denotes an oversized or unreadable response; allocation failure uses -8. These diagnostics support physical network testing without logging protected data.

Acquisition counters are available in redacted `PL_ACQ` UART-heartbeat diagnostics from the screen. `PL_FAULT` identifies C3 restart/link timeout, SD write/rename and read-back failures. This permits observing acquisition without opening the C3 native USB monitor during a recording.


On 6 November 2026, before the response-buffer correction, event polling intermittently failed during certificate processing with TLS -0x3000 while health authentication succeeded. A fixed buffer allocated after handshake also failed under recording load. With bounded actual-sized event buffering, the final 130-second physical check observed 13 authenticated health/event requests, all HTTP 200 and TLS zero, with no rejected/unavailable status samples. Concurrent bench capture completed 22,500 samples/slots, zero gaps/missed slots, zero final queue backlog, zero parser/storage errors and successful full-card read-back. Maximum C3 scheduling lateness was 16 microseconds relative to its own schedule. BLE was off. This is a short bench check, not a long-duration reliability, full clinical-upload, BLE coexistence or diagnostic validation claim.

One earlier combined test was interrupted when the C3 appeared to restart while its native USB monitor was opened. The user did not touch either board. Monitoring side effects remain a possible confound, not a proven reset cause. Two later full recordings observed through the touchscreen UART completed without that restart; the interrupted journal was retained rather than promoted to a completed patient recording.
