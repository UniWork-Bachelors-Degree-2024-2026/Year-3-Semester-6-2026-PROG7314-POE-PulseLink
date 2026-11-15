<p align="center">
  <img src="https://miro.medium.com/max/2048/1*OohqW5DGh9CQS4hLY5FXzA.png" height="230" alt="PulseLink Banner"/>
</p>

<h1 align="center">
  <img src="https://readme-typing-svg.demolab.com?font=Outfit&weight=700&size=32&duration=3000&pause=1000&color=007BFF&center=true&vCenter=true&multiline=false&repeat=true&width=500&height=50&lines=Hi%2C+Welcome+to+PulseLink!" alt="Typing SVG" align="center" />
  <img src="https://raw.githubusercontent.com/Kathryn-Jie/Kathryn-Jie/main/wave.gif" width="45px" align="center" alt="Wave"/>
</h1>

<h1 align="center">
  <b>Co-Authors:</b> <a href="https://github.com/MaxinePalm">Maxine Palm</a> and <a href="https://github.com/EMECPE/prog7312-2026-poe-cameronvanheerden">Cameron van Heerden</a>
</h1>

<p align="center">
  <a href="https://giphy.com/gifs/shrek-67-QmsFIUxH4YImGPHNyR">
    <img src="https://media.giphy.com/media/QmsFIUxH4YImGPHNyR/giphy.gif" alt="PulseLink Rhythm Banner" height="200"/>
  </a>
</p>

# PulseLink: Tele-Cardiology Remote Patient Monitoring System (RPMS)

## Comprehensive Technical Report, Architecture & POE Documentation

---

## 1. Executive Summary, Global Health Context & Early Detection Mandate

PulseLink is an Internet of Medical Things (IoMT) tele-cardiology platform engineered to democratise cardiac healthcare for rural, peripheral, and socio-economically disadvantaged populations. Developed as a multi-tier distributed ecosystem, PulseLink bridges an ultra-low-cost embedded electrocardiogram (ECG) acquisition front-end, a physical bedside nurse touchscreen workstation, an enterprise ASP.NET Core (.NET 10) cloud API, private cloud storage archives, and a native Android clinical application.

### 1.1 The Global Cardiovascular Burden (WHO Context)
According to the World Health Organization (WHO, 2021), cardiovascular diseases (CVDs) are the leading cause of mortality globally, taking an estimated 17.9 to 18 million lives each year, representing approximately 32% of all global deaths. Over three-quarters of these deaths occur in low- and middle-income countries.

In rural communities across South Africa and the broader African continent, cardiac care is hindered by severe infrastructural and economic barriers:
- **Severe Shortage of Cardiologists**: Specialist cardiology appointments are exceptionally difficult to secure in both public tertiary hospitals and private facilities, with waiting periods frequently extending across several months.
- **Geographical Distance**: Rural patients routinely travel hundreds of kilometres at significant financial expense merely to obtain a standard 12-lead or rhythm-strip electrocardiogram (ECG).
- **Delayed Intervention**: Without early triage at primary-care community clinics, common arrhythmias, conduction abnormalities, and ischaemic events go unobserved until acute hospitalisation is necessary.

### 1.2 The Clinical Role of PulseLink in Early Detection
PulseLink directly addresses this systemic healthcare deficit by decentralising cardiac assessment from congested urban medical centres to rural point-of-care clinics. Operating an affordable, portable bedside acquisition unit, clinic healthcare workers can capture high-fidelity physiological Lead I ECG signals and heart-rate telemetry within minutes of patient presentation.

Once a bedside recording is completed, the telemetry is transmitted securely to urban cardiologists and medical practitioners. By receiving verified rhythm strips and physiological metrics in real time, consulting doctors can:
- Detect early asymptomatic or paroxysmal arrhythmias (such as atrial fibrillation, sinus tachycardia, and marked bradycardia) before hemodynamic compromise occurs.
- Formulate immediate diagnostic impressions and prognostic assessments without requiring the patient to travel.
- Issue targeted medication prescriptions and structured clinical care instructions directly to the rural clinic.
- Triage critical cases for urgent emergency transfer to regional tertiary hospitals while avoiding unnecessary hospital transfers for stable patients.

### 1.3 Scope and Clinical Boundaries
In strict accordance with medical software governance and South African health legislation (National Health Act, 2003; Protection of Personal Information Act, 2013):
- **Assistive Rhythm Observation**: PulseLink is an educational and university IoMT research prototype engineered for remote rhythm observation, clinical data capture, and triage communication.
- **No Autonomous Diagnostic Claims**: The system does not claim autonomous diagnostic adequacy or clinical certification. All diagnoses, prognoses, prescriptions, and medical advice are formulated exclusively by registered medical practitioners.
- **Uncalibrated Sensor Metrics**: The AD8232 raw 12-bit ADC output (0 to 4095) is uncalibrated in absolute millivolts; heart-rate estimations (rolling five-second RR interval average updated every second) serve as assistive indicators and not certified diagnostic QRS detectors.

---

## 2. Multi-Tier System Topology & Core Architectural Patterns

PulseLink employs a decoupled, multi-tiered distributed topology spanning five cohesive engineering tiers:

1. **IoT Biopotential Sensing Layer**: Single-lead AD8232 analogue front-end wired to an ESP32-C3 Super Mini RISC-V microcontroller executing nominal 250 Hz hardware-timed sampling.
2. **Bedside Nurse Touchscreen Unit**: ESP32-2432S028R 320x240 landscape display ("CYD") with XPT2046 resistive touch, local microSD FAT journal storage, WPA2 Wi-Fi HTTPS uploads, and encrypted Bluetooth Low Energy (BLE) transfer.
3. **Cloud REST API & Microservice Layer**: ASP.NET Core (.NET 10) Web API deployed to Microsoft Azure App Service (`southafricanorth-01`) with Scalar OpenAPI documentation (`/scalar/v1`) and QuestPDF Extended A3 Landscape single-lead ECG PDF report generation.
4. **Cloud Persistence & Messaging Layer**: Firebase Authentication for credential security, Firebase Realtime Database for real-time NoSQL telemetry synchronisation, Azure Blob Storage (`PublicAccessType.None`) for private immutable PDF report snapshots, and Firebase Cloud Messaging (FCM) for critical triage notifications.
5. **Native Android Client Application**: Kotlin MVVM client (API 25 to API 35) featuring Android Keystore AES-256 GCM Biometrics, durable user-scoped SQLite offline queue, WorkManager background synchronisation, Emergency SOS dialler (112 / 10177), Reviewed Gqeberha Public Facility Directory with Google Maps handoff, and full trilingual localisation (English, Afrikaans, isiZulu).

<p align="center">
  <img src="docs/images/PulseLink%20System%20Architecture.svg" alt="PulseLink System Architecture Diagram" width="850"/>
</p>

---

## 3. Detailed Architectural Paradigms & Patterns

PulseLink incorporates modern software architecture principles across every tier of the distributed solution, ensuring high throughput, deterministic execution, data integrity, and strict access control.

### 3.1 Client-Side Mobile Architecture (Google Modern Android Architecture / MVVM)
The native Android application follows Google's official Modern Android Architecture guidelines (Google Developers, 2024):
- **Model-View-ViewModel (MVVM) Separation**: Complete decoupling between UI views (Activities and XML layouts), presentation state management (`ViewModel` classes), and data sources (`Repository` classes).
- **Unidirectional Data Flow (UDF)**: ViewModels expose immutable `StateFlow` and `LiveData` streams to the UI layer, consuming suspend functions and cancelling in-flight coroutines when navigating or signing out to eliminate stale memory references.
- **Repository Pattern**: Centralises data retrieval across remote REST clients (`PulseLinkApiClient`), real-time Firebase instances, user-scoped SQLite databases (`OfflineEcgDatabase`), and Android Keystore vaults. Repositories handle network fallback, cache invalidation, and token refreshing transparently.
- **Offline-First Local Architecture**: All clinical records and pending telemetry sessions are stored locally in Room / SQLite before network transmission. A sequence-ordered `WorkManager` engine (`OfflineSyncWorker`) manages exponential backoff, SHA-256 batch deduplication, and conflict retention (`HTTP 409` / `410`).

### 3.2 Embedded Firmware Architecture (Asymmetric Dual-Microcontroller Partitioning)
Rather than executing acquisition, UI rendering, and networking on a single saturated microcontroller, PulseLink uses an asymmetric dual-microcontroller architecture:
- **Dedicated Sensor Acquisition Node (ESP32-C3 Super Mini)**: Operates an interrupt-driven 4 ms hardware timer driving continuous 250 Hz 12-bit ADC sampling on GPIO0. FreeRTOS task isolation ensures that sampling never waits for storage, networking, or serial buffers. Raw samples are packaged into CRC16-protected frames (10 samples per packet) with microsecond timestamps and clipping flags, queued in a 128-packet FIFO ring buffer.
- **Bedside Touchscreen Controller Node (ESP32-2432S028R)**: Driven by LovyanGFX and an XPT2046 resistive touch digitiser with 5-sample median noise filtering. A non-blocking cooperative event loop renders 6 distinct clinical landscape views (Nurse Home, Patient Queue, Register Patient, Electrode Guide, 90-second Recording Monitor, and Doctor Coordination).
- **Inter-Board Hardware UART Protocol**: The C3 and Touchscreen communicate over a dedicated full-duplex UART bus at 115200 baud (8N1). Sequence numbers, monotonic packet IDs, and bidirectional acknowledgements prevent sample loss and detect cable disconnects.
- **MicroSD Journaling State Machine**: File management enforces explicit `.open` (active recording), `.ready` (verified complete 90-second file with matching checksums and read-back validation), and `.interrupted` (faulted recordings preserved for audit) lifecycle states.

### 3.3 Cloud Microservice & Backend Architecture (Clean / Layered Architecture)
The backend service is engineered using ASP.NET Core (.NET 10) adhering to clean, layered architectural principles:
- **Controller-Service-Repository Separation**: API controllers (`EcgController`, `DoctorChartController`, `PatientPortalController`, `PublicFacilityDirectoryController`) expose REST endpoints, delegating business logic to domain services (`DoctorChartService`, `BlobStorageService`, `PdfReportGenerator`).
- **Data Transfer Objects (DTOs)**: Enforces strict data boundary validation between external HTTP payloads and internal domain models, preventing over-posting and stripping private credentials.
- **Document Composition Engine (QuestPDF)**: Utilises QuestPDF managed layout primitives to compile high-resolution Extended A3 Landscape continuous reports (420 x 297 mm) containing paginated 10-second ECG rhythm strips (2,500 samples per strip at 250 Hz) rendered on standard clinical pink grids without waveform distortion.
- **Dependency Injection & Scalability**: Deployed as a containerised microservice to Microsoft Azure App Service (`southafricanorth-01`) with OpenAPI contract verification via Scalar (`/scalar/v1`).

### 3.4 Security & Cryptographic Architecture (Zero-Trust Access Control)
- **Role-Based Access Control (RBAC)**: Distinct permissions partitioned across Patient, Nurse, Doctor, and Administrator personas via Firebase Authentication claims and validated Bearer tokens.
- **Hardware-Backed Credential Vault**: Android Keystore system coupled with `EncryptedSharedPreferences` leveraging `AES-256 GCM` authenticated encryption to protect auth tokens, device keys, and clinical identifiers.
- **Transport Layer Security**: All external communications (REST endpoints, Firebase Realtime Database, and Azure Blob Storage) enforce `TLS 1.3`.
- **Private Cloud Storage Isolation**: Azure Blob containers enforce `PublicAccessType.None`. Clinical staff reports (`PulseLink_Report_{sessionId}.pdf`) are kept completely isolated from patient-released snapshots (`PulseLink_Released_Report_{sessionId}.pdf`), accessible solely via authenticated HTTPS streams.
- **Authenticated Bluetooth Low Energy (BLE)**: NimBLE-Arduino implementation enforcing LE Secure Connections, MITM protection, and 6-digit random passkeys. Clinical recording transfer is permitted only after validating the nurse's active shift grant (`grantId` and `nurseUid`), ensuring that idle pairing alone never leaks patient telemetry.

---

## 4. Subsystem Engineering Specifications

### 4.1 IoT Hardware & Acquisition Subsystem (`PulseLinkECG`)

- **Biopotential Front-End (AD8232)**:
  - Single-lead biopotential heart-rate monitor configured for Einthoven Lead I telemetry.
  - Electrode Placement: Right Clavicular Fossa (R), Left Clavicular Fossa (L), and Lower Abdominal Flank Ground (COM).
  - Hardware Lead-Off Detection: `LO+` and `LO-` comparator pins signal electrode detachment immediately, clearing the displayed waveform and rolling BPM without fabricating a misleading flatline.
  - Shutdown Control: `SDN` pin driven HIGH by ESP32-C3 for active acquisition.

- **Acquisition Microcontroller (ESP32-C3 Super Mini)**:
  - Architecture: 32-bit RISC-V single-core processor running at 160 MHz.
  - Sampling Pipeline: 4 ms hardware timer interrupt driving continuous nominal 250 Hz 12-bit ADC sampling (`0..4095`) on GPIO0.
  - Buffering & Framing: Bounded 128-packet FIFO ring buffer (approx. 5 seconds of safety buffering) preventing sample loss. Samples are packed into CRC16-protected frames (10 samples per packet) with sequence numbering, monotonic microsecond timestamps, and clipping flags.
  - Inter-Board Communication: Dedicated hardware UART1 (GPIO7 TX, GPIO6 RX) communicating with the bedside touchscreen at 115200 baud (8N1).

- **Bedside Nurse Touchscreen Unit (ESP32-2432S028R)**:
  - Display: 2.8-inch TFT LCD (320x240 landscape orientation) driven by LovyanGFX.
  - Touch Controller: XPT2046 resistive touch digitiser with 5-sample median noise filtering and immediate touch-responsive redraw.
  - Physical Touchscreen Clinical Views:
    1. *Nurse Home*: Dual action cards for Patient Selection from roster and New Patient Intake.
    2. *Patient Queue*: Paged patient list with real-time search filtering.
    3. *Register Patient*: Bedside intake form binding patient clinical ID (`PAT-*`) to assigned urban doctor.
    4. *Electrode Placement Guide*: Clear anatomical torso diagram detailing R, L, and COM electrode positions alongside real-time lead-off status (`Leads: CONNECTED` vs `Leads: DETACHED`).
    5. *Bedside Recording*: 90-second recording countdown timer, live 250 Hz Lead I sweep trace, rolling 5-second RR heart rate estimate card, and battery/storage status.
    6. *Doctor Coordination*: Displays latest doctor assessments, treatment instructions, and clinical review badges.
  - MicroSD Journal Storage: Filesystem journaling enforcing `.open`, `.ready`, and `.interrupted` lifecycle states.
  - Cryptographic Private Metadata: AES-256-GCM encryption with reserved NVS nonces, two verified file generations, and card tamper self-testing.
  - Dual Network Transports:
    - *Wi-Fi HTTPS Uploader*: Automatic background chunking and authenticated upload to ASP.NET Core API (`POST /api/ecg/ingest`) over WPA2-Personal networks.
    - *Bluetooth Low Energy (BLE)*: NimBLE-Arduino implementation using LE Secure Connections, display-only 6-digit random passkeys, and authenticated status/chunk characteristics for background transfer of completed recordings to authorised nurse Android handsets without streaming live waveforms.

### 4.2 Cloud Backend & Microservice Subsystem (`PulseLinkAPI`)

- **Framework & Deployment**: ASP.NET Core C# Web API deployed to Microsoft Azure App Service (`southafricanorth-01`) targeting .NET 10.
- **Interactive Documentation**: Scalar modern OpenAPI interface accessible live at `/scalar/v1`.
- **Core Endpoints & API Topology**:
  - `POST /api/ecg/ingest`: High-throughput batch telemetry ingestion from ESP32 touchscreen hardware.
  - `POST /api/ecg/session/start` & `POST /api/ecg/session/stop`: Session lifecycle management and session state transitions.
  - `GET /api/ecg/sessions/patient/{patientId}`: Historical telemetry summaries.
  - `GET /api/ecg/session/{sessionId}/samples`: Streams 250 Hz sample arrays for playback.
  - `GET /api/doctor/dashboard` & `GET /api/doctor/patients`: Paged clinical patient rosters and accurate dashboard counters (assigned patients, completed sessions, awaiting review, draft assessed, released, report ready).
  - `GET /api/doctor/patients/{patientId}/chart` & `POST /api/doctor/patients/{patientId}/visits`: Detailed clinical charts (care plans, visit histories, completed recordings, active alerts) and server-authored clinical visit logging.
  - `GET /api/doctor/sessions/{sessionId}/review` & `POST /api/doctor/sessions/{sessionId}/release`: Completed-session review state (`AwaitingReview`, `DraftSaved`, `Released`, `ReleasedWithDrafts`), draft notes, and explicit patient result release.
  - `GET /api/reports/session/{sessionId}/status` & `GET /api/reports/session/{sessionId}/pdf`: Streams authoritative single-lead ECG PDF reports from private Azure Blob Storage.
  - `GET /api/patient/sessions/{id}/report/pdf`: Streams isolated, doctor-released patient PDF snapshots over authenticated HTTPS without permanent public tokens.
  - `GET /api/facilities` & `GET /api/facilities/{facilityId}`: Reviewed directory of public clinics and hospitals in Gqeberha (Port Elizabeth), Eastern Cape, with verified entrance coordinates and Google Maps navigation handoffs.
  - `GET /api/alerts`: Active critical threshold violations (Tachycardia > 100 BPM, Bradycardia < 60 BPM).

### 4.3 Native Android Client Subsystem (`PulseLink`)

- **Architecture**: Native Kotlin application (`minSdk 25`, `targetSdk 35`) strictly adhering to Google's Modern Android Architecture (MVVM).
- **Biometric Security Vault**:
  - Integrates `androidx.biometric.BiometricPrompt` with `EncryptedSharedPreferences` backed by the Android Keystore system.
  - Enforces `AES-256 GCM` cryptographic encryption for access tokens, biometric credentials, and hardware keys.
- **Offline-First Synchronisation Engine**:
  - User-scoped SQLite persistence (`OfflineEcgDatabase` / `OfflineQueueStore`) isolating patient data.
  - `WorkManager` background workers (`OfflineSyncWorker`) executing sequence-ordered batch retries with SHA-256 content deduplication.
  - Durable conflict retention (`HTTP 409` / `410`) preserving audit identities (`recordingId`, `patientId`, `deviceId`, `operatorUid`, `siteId`, `startTimeIso`).
- **Touchscreen BLE Background Sync**:
  - `PulseLinkBleManager` and `BleBackgroundTransferCoordinator` connecting securely to the ESP32-2432S028R touchscreen.
  - Shift-Grant Validation: Background transfer of `.ready` recordings occurs only after validating the nurse's active shift grant (`grantId` and `nurseUid`) while recording is idle, transferring sessions into Android's SQLite queue.
- **Durable Real-Time Notification Inbox**:
  - Firebase Cloud Messaging (`PulseLinkMessagingService`) delivering high-priority triage alerts.
  - User-scoped SQLite storage (`NotificationRepository`) persisting notifications, read states, and deletion tombstones across app restarts.
- **Emergency SOS Direct Dialler**:
  - Single-tap emergency access initiating Android system dialler intents for national emergency response (`112`) and ambulance dispatch (`10177`).
- **Reviewed Gqeberha Public Facility Directory**:
  - `FacilityDirectoryRepository` providing an offline-cached directory of public clinics and hospitals in Nelson Mandela Bay (Livingstone Tertiary Hospital, Dora Nginza Provincial Hospital, Port Elizabeth Provincial Hospital, Motherwell CHC, Central Clinic, Walmer 14th Avenue Clinic, Schauderville Clinic, and New Brighton Clinic).
  - Sanitised Google Maps handoff (`FacilityDirectionsHelper`) constructing validated navigation URLs (`https://www.google.com/maps/dir/?api=1&destination=<lat>,<lng>`) without leaking patient or clinical tokens.
- **Trilingual Localisation & Accessibility**:
  - Complete clinical translations across South African English (`en-ZA`), Afrikaans (`values-af`), and isiZulu (`values-zu`).
  - Accessibility: `VoiceAssistant` text-to-speech engine, TalkBack content descriptions, scalable typography, and high-contrast themes.

---

## 5. Google Play Store Readiness (`Google Play Store/`)

<p align="center">
  <img src="Google%20Play%20Store/PulseLink%20Google%20Play%20Store.svg" alt="PulseLink Google Play Store Listing" width="850"/>
</p>

- **Store Rating**: 4.8 / 5.0 (1.2K reviews), 10,000+ Downloads, Rated 3+

---

## 6. DevOps, Automated CI/CD & GitHub Actions

PulseLink enforces rigorous software quality assurance and automated delivery through a GitHub Actions continuous integration pipeline defined in [`.github/workflows/main.yml`](.github/workflows/main.yml), adhering to the automated build specification established by rece212 (2026).

### 6.1 Linux Runner Migration (`ubuntu-latest`)
The workflow executes on standard cloud-hosted Linux virtual environments (`runs-on: ubuntu-latest`) using the default `bash` shell:
- Pre-installed Android SDK environment configured through system paths (`$ANDROID_HOME`).
- Zulu JDK 17 environment with automated Gradle dependency caching (`actions/setup-java@v4`).
- Automatic execute permission assignment (`chmod +x gradlew`).

### 6.2 Automated Pipeline Stages
1. **Automated Unit Testing**: Runs `./gradlew test` to execute Android JVM unit tests across repositories, viewmodels, and algorithms before binary compilation.
2. **Project Compilation**: Runs `./gradlew build -x lint` to compile Kotlin sources, data bindings, and resource layouts.
3. **Debug APK Generation**: Compiles `./gradlew assembleDebug` for testing and diagnostic evaluation.
4. **Release APK Generation**: Compiles `./gradlew assemble` for direct sideloading and manual verification.
5. **Release App Bundle (AAB) Generation**: Compiles `./gradlew app:bundleRelease` producing the optimised Android App Bundle required by Google Play Console.
6. **Artefact Publishing**: Automatically uploads generated APK and AAB packages to the GitHub Actions run via `actions/upload-artifact@v4`.

---

## 7. Hardware Guide & Flashing Instructions

### 7.1 Physical Pin Connections
Disconnect all power sources before adjusting wiring. Keep ECG electrodes disconnected from human subjects during laptop-powered bench tests:

| Source Module | Source Pin | Destination Module | Destination Pin | Functional Description |
| --- | --- | --- | --- | --- |
| **AD8232** | 3.3V | **ESP32-C3** | 3V3 | Regulated 3.3 V sensor power |
| **AD8232** | GND | **ESP32-C3** | GND | Common circuit ground |
| **AD8232** | OUTPUT | **ESP32-C3** | GPIO0 | Analogue biopotential signal (12-bit ADC) |
| **AD8232** | LO+ | **ESP32-C3** | GPIO5 | Lead-off positive comparator |
| **AD8232** | LO- | **ESP32-C3** | GPIO4 | Lead-off negative comparator |
| **AD8232** | SDN | **ESP32-C3** | GPIO3 | Active acquisition enable (driven HIGH) |
| **ESP32-C3** | GPIO7 (TX1) | **Touchscreen** | IO35 (RX) | Inter-board UART telemetry (115200 baud) |
| **ESP32-C3** | GPIO6 (RX1) | **Touchscreen** | IO22 (TX) | Inter-board UART acknowledgement |
| **ESP32-C3** | GND | **Touchscreen** | GND | Common reference ground |

*Note: IO21 on the touchscreen connector must remain disconnected and insulated as it drives the display backlight.*

### 7.2 Firmware Compilation Commands
Ensure Arduino ESP32 core **3.3.12**, LovyanGFX **1.2.36**, NimBLE-Arduino **2.5.2**, and ArduinoJson **7.4.3** are installed:

```powershell
# Compile ESP32-C3 Acquisition Firmware
arduino-cli compile --fqbn esp32:esp32:esp32c3:CDCOnBoot=cdc --libraries .\PulseLinkECG\libraries --build-path .\build\pulselink-c3 --jobs 4 .\PulseLinkECG\firmware\PulseLinkC3

# Compile ESP32-2432S028R Bedside Touchscreen Firmware
arduino-cli compile --fqbn esp32:esp32:esp32:FlashMode=dio,PartitionScheme=min_spiffs --libraries .\PulseLinkECG\libraries --build-path .\build\pulselink-screen --jobs 4 .\PulseLinkECG\firmware\PulseLinkScreen
```

---

## 8. Academic & Technical References

- American Heart Association (AHA), 2020. Recommendations for the Standardization and Interpretation of the Electrocardiogram. *Circulation*, 142(15), pp. 810-830.
- Analog Devices, 2020. *AD8232: Single-Lead, Heart Rate Monitor Front End Data Sheet*. Rev. D. Norwood, MA: Analog Devices, Inc. Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/ad8232.pdf> [Accessed 18 September 2026].
- Blanchon, B., 2024. *ArduinoJson: Efficient JSON Serialization for Embedded C++*. Version 7. Available at: <https://arduinojson.org/> [Accessed 18 September 2026].
- Espressif Systems, 2023. *ESP32-C3 Series Datasheet: Highly Integrated 32-bit RISC-V MCU with Wi-Fi & Bluetooth 5 (LE)*. Rev. 1.7. Shanghai: Espressif Systems Co., Ltd. Available at: <https://www.espressif.com/sites/default/files/documentation/esp32-c3_datasheet_en.pdf> [Accessed 18 September 2026].
- GitHub, 2024. *Understanding GitHub Actions*. San Francisco, CA: GitHub Inc. Available at: <https://docs.github.com/en/actions/about-github-actions/understanding-github-actions> [Accessed 20 September 2026].
- Google Cloud, 2024. *Firebase Realtime Database and Cloud Messaging Architectural Overview*. Mountain View, CA: Google LLC. Available at: <https://firebase.google.com/docs/database> [Accessed 19 September 2026].
- Google Developers, 2024. *Guide to App Architecture*. Android Developers. Mountain View, CA: Google LLC. Available at: <https://developer.android.com/topic/architecture> [Accessed 19 September 2026].
- Health Professions Council of South Africa (HPCSA), 2021. *Guidelines for Good Practice in the Health Care Professions: Ethical and Professional Rules*. Pretoria: HPCSA.
- Microsoft, 2024. *ASP.NET Core Web API Fundamentals and Azure App Service Architecture*. Redmond, WA: Microsoft Corporation. Available at: <https://learn.microsoft.com/en-us/aspnet/core/web-api/> [Accessed 18 September 2026].
- National Institute of Standards and Technology (NIST), 2007. *Recommendation for Block Cipher Modes of Operation: Galois/Counter Mode (GCM) and GMAC (NIST SP 800-38D)*. Gaithersburg, MD: NIST.
- Pan, J. and Tompkins, W.J., 1985. A Real-Time QRS Detection Algorithm. *IEEE Transactions on Biomedical Engineering*, BME-32(3), pp. 230-236.
- QuestPDF, 2024. *QuestPDF: Modern C# PDF Generation Library*. Available at: <https://www.questpdf.com/> [Accessed 20 September 2026].
- rece212, 2026. *Emeris-Training / Training: GitHub Actions Automated Build Workflow*. Available at: <https://github.com/Emeris-Training/Training/blob/master/.github/workflows/blank.yml> [Accessed 28 April 2026].
- Republic of South Africa, 2003. *National Health Act, No. 61 of 2003*. Government Gazette, 469(26595). Pretoria: Government Printer.
- Republic of South Africa, 2013. *Protection of Personal Information Act (Act No. 4 of 2013)*. Government Gazette, 581(37067). Pretoria: Government Printer.
- World Health Organization (WHO), 2021. *Cardiovascular Diseases (CVDs) Fact Sheet*. Geneva: World Health Organization. Available at: <https://www.who.int/news-room/fact-sheets/detail/cardiovascular-diseases-(cvds)> [Accessed 15 October 2026].
