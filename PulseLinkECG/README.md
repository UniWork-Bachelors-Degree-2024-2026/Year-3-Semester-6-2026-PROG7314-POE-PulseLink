# PulseLink embedded firmware

PulseLink captures a single AD8232 ECG waveform and estimates BPM on an ESP32-C3 Super Mini. A wired ESP32-2432S028R touchscreen displays live ECG and BPM and stores recordings on microSD. The touchscreen also provides optional secure BLE pairing and generic status for a staff phone. The touchscreen provides Wi-Fi configuration and a restricted HTTPS upload transport to the C# API.

The nurse workflow authorises a site-bound touchscreen from the signed-in Android nurse app. It provides patient selection/registration, primary-doctor confirmation, a labelled lead-placement guide and clinical recording controls. A matching published API and current nurse permission are required. Disconnected bench checks remain separate: bench files contain no patient identifiers and must not become patient results. Physical acceptance and remaining limitations are recorded in the firmware guide.

| Target | Purpose |
| --- | --- |
| [C3 acquisition](firmware/PulseLinkC3/PulseLinkC3.ino) | Timed raw samples, BPM, lead-off flags and bounded UART retries |
| [Touchscreen recorder](firmware/PulseLinkScreen/PulseLinkScreen.ino) | PulseLink monitor, microSD journal, recovery and pairing display |

The retired single-board acquisition/upload sketch has been removed. Separate raw C3 and screen verification sketches remain for fault isolation.

Read [the firmware guide](firmware/README.md) for wiring, builds, security, storage and measured verification results. Android live ECG is excluded from the agreed design. Authorised Android background transfer and clinical messaging remain outstanding. The upload transport accepts only authorised clinical journals; existing bench files remain local.

Lead-off indication cannot establish correct anatomical placement, all three contacts, calibration or clinical signal quality. This prototype is not a validated diagnostic device.
