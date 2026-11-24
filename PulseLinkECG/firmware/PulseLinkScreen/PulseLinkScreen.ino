// PulseLink nurse workflow, local ECG journal and isolated network transport.
#include <Arduino.h>
#include <esp_bt.h>
#include <SPI.h>
#include <SD.h>
#include <memory>
#if __has_include(<PulseLinkSerial.h>)
#include <PulseLinkSerial.h>
#else
#include "../../libraries/PulseLinkCore/src/PulseLinkSerial.h"
#endif
#include "PulseLinkDisplay.h"
#include "PulseLinkBle.h"
#include "PulseLinkTouch.h"
#include "PulseLinkWifiView.h"
#include "PulseLinkUploader.h"
#include "PulseLinkNurseView.h"

PulseLinkDisplay display;
HardwareSerial c3(2);
SPIClass cardBus(VSPI);
pl::Parser parser;
pl::RecordingValidator recording;
pl::HealthInfo health{};
PulseLinkBle ble;
PulseLinkNetwork network;
PulseLinkWifiView wifiView;
PulseLinkClinicalStore clinicalStore;
PulseLinkNurseFlow workflow(network, clinicalStore);
PulseLinkUploader uploader(network, clinicalStore);
File journal;
uint64_t screenBoot, c3Boot = 0;
uint8_t session[16] = {};
char openPath[64] = {};
bool cardOk = false, requested = false, failed = false, recoveryPending = false;
bool stopRequested = false;
bool clinicalRecording = false;
uint32_t c3At = 0, lastUi = 0, lastHello = 0, lastStart = 0;
uint32_t recovered = 0;
uint32_t lastStop = 0;
pl::TouchLatch touch;
bool pairPage = false;
bool wifiPage = false;
uint32_t forgetConfirmAt = 0;
bool forgetConfirm = false;
const char *bleNotice = "";
constexpr unsigned WaveWidth = 238, WaveHeight = 114, WaveX = 8, WaveY = 36;
constexpr unsigned BpmX = 250, BpmWidth = 62;
int16_t waveform[WaveWidth] = {}; unsigned waveHead = 0;
std::unique_ptr<pl::UploadJournal> liveStream;
uint32_t liveBatchReq = 0, liveReqSeq = 0, liveReqCrc = 0, liveAckSeq = 0, liveAckCrc = 0;
bool liveAckValid = false, liveContiguous = false;
bool waveDirty = false;
uint32_t lastWaveUi = 0;
constexpr uint16_t NAVY = pl::ui::NavyBg, TEAL = pl::ui::TealBtn, WHITE = pl::ui::White, MUTED = pl::ui::MutedText;

void renameRecording(const char *extension) {
  if (journal) { journal.flush(); journal.close(); }
  char destination[64]; strlcpy(destination, openPath, sizeof(destination));
  char *dot = strrchr(destination, '.');
  if (dot) strlcpy(dot, extension, sizeof(destination) - (dot - destination));
  if (!SD.rename(openPath, destination)) { failed = true; recoveryPending = true; pl::debug("PL_FAULT storage_rename\n"); }
}

bool readBackComplete() {
  journal.flush(); journal.close();
  File input = SD.open(openPath, FILE_READ);
  if (!input) return false;
  pl::Parser reader; pl::RecordingValidator validator(true); pl::Frame f;
  bool valid = true;
  while (input.available())
    if (reader.feed(input.read(), f) && validator.accept(f) != pl::Accepted) valid = false;
  input.close();
  return valid && !reader.invalid && !reader.pending() && validator.complete();
}

void recover() {
  // Never resume an interrupted acquisition or modify its recorded bytes.
  File directory = SD.open("/pulselink");
  if (!directory) return;
  unsigned inspected = 0;
  for (File entry = directory.openNextFile(); entry; entry = directory.openNextFile()) {
    if (++inspected > 128) { recoveryPending = true; entry.close(); break; }
    String name = entry.name();
    if (!entry.isDirectory() && name.endsWith(".open")) {
      pl::Parser reader; pl::RecordingValidator validator(true); pl::Frame f;
      bool valid = true;
      while (entry.available()) {
        if (reader.feed(entry.read(), f) && validator.accept(f) != pl::Accepted) valid = false;
      }
      valid &= reader.invalid == 0 && reader.pending() == 0;
      String source = name.startsWith("/") ? name : String("/pulselink/") + name;
      entry.close();
      String destination = source.substring(0, source.length() - 5) +
        (valid && validator.complete() ? ".ready" : ".interrupted");
      if (!SD.rename(source, destination)) recoveryPending = true;
      else ++recovered;
    } else entry.close();
  }
  directory.close();
}

bool requestRecording(bool clinical) {
  if (!cardOk || recoveryPending || requested || journal || !c3At || millis() - c3At > 3000 || (clinical && health.leadFlags)) return false;
  if (!clinical) for (unsigned i = 0; i < 16; i += 4) { uint32_t r = esp_random(); memcpy(session + i, &r, 4); }
  char id[33]; for (unsigned i = 0; i < 16; ++i) snprintf(id + i * 2, 3, "%02x", session[i]);
  snprintf(openPath, sizeof(openPath), "/pulselink/%s.open", id);
  if (SD.exists(openPath)) return false;
  journal = SD.open(openPath, FILE_WRITE);
  if (!journal) { failed = true; return false; }
  clinicalRecording = clinical;
  liveStream.reset(); liveBatchReq = 0; liveAckValid = false; liveContiguous = clinical;
  if (clinical) {
    if (network.view().api == ApiState::Ready) ble.releaseIfIdle();
    liveStream.reset(new (std::nothrow) pl::UploadJournal(session));
  }
  journal.flush(); requested = true; stopRequested = false; failed = false; recording = pl::RecordingValidator(clinical);
  lastStart = millis() - 1000;
  return true;
}

void feedLiveBatch(const uint8_t *bytes, size_t count) {
  if (!clinicalRecording || !liveStream) return;
  for (size_t i = 0; i < count; ++i) liveStream->feed(bytes[i]);
  if (liveStream->state != pl::UploadReadState::Ready) return;
  if (liveStream->count > 0 && workflow.isActiveSessionSynchronised() &&
      network.view().api == ApiState::Ready && liveBatchReq == 0) {
    String body;
    if (body.reserve(4600)) {
      char hex[33]; for (unsigned i = 0; i < 16; ++i) snprintf(hex + i * 2, 3, "%02x", session[i]);
      const String sid = String("SESSION-") + hex;
      const auto config = network.settings();
      const auto &samples = liveStream->samples;
      const uint16_t n = liveStream->count, bpm = liveStream->bpm;
      JsonDocument json;
      json["schemaVersion"] = 2; json["sessionId"] = sid; json["deviceId"] = config.deviceId;
      char boot[17]; snprintf(boot, sizeof(boot), "%016llx", liveStream->record.identity.boot);
      json["bootId"] = boot; json["sequence"] = liveStream->sequence; json["firstSampleIndex"] = samples[0].index;
      json["timestamp"] = samples[0].us / 1000; json["firstSampleTimestampUs"] = samples[0].us;
      json["sampleRateHz"] = 250; json["firmwareVer"] = "pulselink-wifi-1";
      json["estimatedBpm"] = bpm; json["adaptiveThreshold"] = 0;
      bool leads = true, flagged = false;
      auto raw = json["samples"].to<JsonArray>(); auto offsets = json["sampleOffsetsUs"].to<JsonArray>();
      auto flags = json["sampleFlags"].to<JsonArray>();
      for (unsigned i = 0; i < n; ++i) {
        raw.add(samples[i].raw); offsets.add(samples[i].us - samples[0].us); flags.add(samples[i].flags);
        leads &= (samples[i].flags & 3) == 0; flagged |= samples[i].flags != 0;
      }
      json["leadConnected"] = leads; json["signalQuality"] = !leads ? "LeadsDisconnected" : flagged ? "Degraded" : bpm ? "Good" : "Unknown";
      serializeJson(json, body);
      liveReqSeq = liveStream->sequence; liveReqCrc = liveStream->prefixCrc;
      if (!liveContiguous || (liveAckValid && liveReqSeq != liveAckSeq + 1) || (!liveAckValid && liveReqSeq != 0)) liveContiguous = false;
      liveBatchReq = network.submitMoved(("/api/device-transport/recordings/" + sid + "/batches").c_str(), true, std::move(body), NetOwner::Upload);
    } else {
      liveContiguous = false;
    }
  } else {
    liveContiguous = false;
  }
  liveStream->nextBatch();
}

void acknowledge(const pl::Frame &f) {
  pl::Frame ack = pl::frame(pl::Ack, f.h.boot, f.h.sequence, f.h.session); pl::send(c3, ack);
}

void receive(pl::Frame &f) {
  if (f.h.type == pl::Health) {
    pl::HealthInfo value;
    if (!pl::get(f, value)) return;
    if (c3Boot && c3Boot != f.h.boot && journal) {
      pl::debug("PL_FAULT c3_restarted\n");
      renameRecording(".interrupted"); liveStream.reset(); liveBatchReq = 0; requested = false; failed = true;
      if (clinicalRecording) workflow.finished(false);
    }
    c3Boot = f.h.boot; c3At = millis(); health = value; return;
  }
  if (memcmp(f.h.session, session, 16)) return;
  // The last durable frame may be retried after its acknowledgement was lost.
  auto candidate = recording;
  const pl::Receipt receipt = candidate.accept(f);
  if (receipt == pl::Duplicate) { acknowledge(f); return; }
  if (receipt != pl::Accepted || !journal || failed) return;
  uint8_t bytes[pl::MaxFrame]; size_t count = pl::encode(f, bytes);
  if (journal.write(bytes, count) != count) {
    pl::debug("PL_FAULT storage_write\n");
    failed = true; pl::Frame stop = pl::frame(pl::Stop, screenBoot, 0, session); pl::send(c3, stop);
    renameRecording(".interrupted"); liveStream.reset(); liveBatchReq = 0; requested = false;
    if (clinicalRecording) workflow.finished(false);
    return;
  }
  journal.flush();
  // ACK follows the card flush. Consumer retries never append duplicate frames.
  recording = candidate; acknowledge(f); c3At = millis();
  feedLiveBatch(bytes, count);
  if (f.h.type == pl::Samples) {
    for (size_t offset = 0; offset < f.h.length; offset += sizeof(pl::Sample)) {
      pl::Sample sample; memcpy(&sample, f.payload + offset, sizeof(sample));
      // Display decimation only. The journal retains every received raw sample.
      if (sample.index % 4 == 0) {
        waveform[waveHead] = sample.flags & (pl::LeadPlus | pl::LeadMinus) ? -1 : sample.raw;
        waveHead = (waveHead + 1) % WaveWidth;
        waveDirty = true;
      }
    }
  } else if (f.h.type == pl::End) {
    const bool verified = recording.complete() && readBackComplete();
    if (recording.complete() && !verified) { failed = true; pl::debug("PL_FAULT readback\n"); }
    renameRecording(verified ? ".ready" : ".interrupted"); requested = false;
    liveStream.reset(); liveBatchReq = 0;
    if (verified && !failed && liveContiguous && liveAckValid) {
      char idHex[33]; for (unsigned i = 0; i < 16; ++i) snprintf(idHex + i * 2, 3, "%02x", session[i]);
      const String readyPath = String("/pulselink/") + idHex + ".ready";
      const String syncPath = String("/pulselink/") + idHex + ".sync";
      File rf = SD.open(readyPath, FILE_READ);
      if (rf) {
        const uint32_t rBytes = rf.size(); rf.close();
        File sf = SD.open(syncPath, FILE_WRITE);
        if (sf) {
          JsonDocument cp; cp["nextSequence"] = liveAckSeq + 1; cp["prefixCrc"] = liveAckCrc; cp["fileBytes"] = rBytes;
          serializeJson(cp, sf); sf.flush(); sf.close();
        }
      }
    }
    if (clinicalRecording) workflow.finished(verified && !failed);
    char log[160];
    snprintf(log, sizeof(log), "PL6_END captured=%lu slots=%lu gaps=%u reason=%u readback=%u sd_error=%u\n",
      (unsigned long)recording.captured, (unsigned long)recording.nextIndex,
      recording.gaps, recording.reason, verified, failed);
    pl::debug(log);
  }
}

uint16_t touchAxis(uint8_t command) {
  digitalWrite(33, LOW);
  for (int b = 7; b >= 0; --b) {
    digitalWrite(32, (command >> b) & 1); digitalWrite(25, HIGH); delayMicroseconds(2); digitalWrite(25, LOW);
  }
  delayMicroseconds(5); uint16_t value = 0;
  for (int b = 0; b < 16; ++b) {
    digitalWrite(25, HIGH); delayMicroseconds(2); value = (value << 1) | digitalRead(39);
    digitalWrite(25, LOW); delayMicroseconds(2);
  }
  digitalWrite(33, HIGH); return (value >> 3) & 4095;
}

void handleTouch() {
  if (!touch.ready(digitalRead(36) == LOW, millis())) return;
  auto axis = [](uint8_t command) {
    touchAxis(command);
    uint16_t values[5];
    for (int i = 0; i < 5; ++i) values[i] = touchAxis(command);
    for (int i = 0; i < 4; ++i)
      for (int j = i + 1; j < 5; ++j)
        if (values[i] > values[j]) { uint16_t v = values[i]; values[i] = values[j]; values[j] = v; }
    return values[2];
  };
  const uint16_t rawX = axis(0xd0), rawY = axis(0x90);
  int x, y;
  if (!pl::landscapeTouch(rawX, rawY, x, y)) return;
  touch.accept();
  char touchLog[64];
  snprintf(touchLog, sizeof(touchLog), "PL_TOUCH x=%d y=%d rawX=%u rawY=%u\n", x, y, rawX, rawY);
  pl::debug(touchLog);
  if (pairPage) {
    if ((!wifiPage || !wifiView.editing()) && y >= 34 && y <= 60) { wifiPage = x < 160; draw(); lastUi = millis(); return; }
    if (wifiPage) { if (wifiView.tap(x, y, network, requested)) pairPage = false; draw(); lastUi = millis(); return; }
    if (y >= 196) {
      if (x < 160) { pairPage = false; forgetConfirm = false; }
      else if (requested) bleNotice = "Stop recording before forgetting bonds.";
      else if (!forgetConfirm || millis() - forgetConfirmAt >= 5000) {
        forgetConfirm = true; forgetConfirmAt = millis(); bleNotice = "Tap Forget bonds again to confirm.";
      } else {
        bleNotice = ble.forget() ? "Saved bonds removed. BLE mode off." : "Could not remove bonds. Restart device.";
        forgetConfirm = false;
      }
      draw(); lastUi = millis();
    } else if (y >= 154 && y <= 188) {
      forgetConfirm = false;
      if (x < 160) {
        if (ble.view().state != pl::BleState::Off) { ble.disable(); bleNotice = "BLE mode off."; }
        else if (requested) bleNotice = "Enable BLE when recording has stopped.";
        else bleNotice = ble.enable() ? "BLE enabled. Tap Pair phone." : "BLE unavailable. Restart device.";
      } else if (requested) bleNotice = "Open pairing when recording has stopped.";
      else { ble.pair(); bleNotice = ""; }
      draw(); lastUi = millis();
    }
    return;
  }
  if (!workflow.monitor()) {
    if (workflow.tap(x, y, requested, c3At && millis() - c3At < 3000, health.leadFlags, cardOk && !failed && !recoveryPending)) {
      pairPage = true; wifiPage = true; bleNotice = "";
    }
    draw(); lastUi = millis();
    return;
  }
  if (y < 196) return;
  if (x < 160) {
    pairPage = false;
    if (requested) { stopRequested = true; lastStop = millis() - 300; }
    else workflow.home();
  } else {
    if (workflow.monitor()) {
      workflow.openMessages(true);
    } else {
      pairPage = true; wifiPage = true; bleNotice = "";
    }
  }
  draw(); lastUi = millis();
}

void draw() {
  struct WriteGuard {
    PulseLinkDisplay &d;
    explicit WriteGuard(PulseLinkDisplay &disp) : d(disp) { d.startWrite(); }
    ~WriteGuard() { d.endWrite(); }
  } guard(display);

  const uint32_t screenKey = (pairPage ? 1u : 0u) |
                             (wifiPage ? 2u : 0u) |
                             (static_cast<uint32_t>(workflow.currentPage()) << 2) |
                             (static_cast<uint32_t>(workflow.entryKey()) << 8) |
                             (static_cast<uint32_t>(wifiView.viewKey()) << 12);
  static uint32_t lastScreenKey = 0xFFFFFFFFu;
  const bool fullRedraw = screenKey != lastScreenKey;
  lastScreenKey = screenKey;

  const bool fresh = c3At && millis() - c3At < 3000;
  const auto pairing = ble.view();
  static auto previousBleState = pl::BleState::Off;
  if (pairing.state != previousBleState && !forgetConfirm) bleNotice = "";
  previousBleState = pairing.state;
  const bool pairingOpen = pairing.state == pl::BleState::Pairing || pairing.state == pl::BleState::Connecting;
  display.setFont(&fonts::Font0); display.setTextSize(1); display.setTextWrap(false, false);
  if (fullRedraw) display.fillScreen(NAVY);
  display.setTextColor(WHITE, NAVY);

  if (!pairPage && workflow.monitor()) {
    if (fullRedraw) {
      display.setCursor(8, 8); display.setTextColor(WHITE, NAVY); display.print("Pulse");
      display.setTextColor(pl::ui::MintGreen, NAVY); display.print("Link");
    }
    const String tag = clinicalRecording && workflow.patientId()[0] ? String(workflow.patientId()) : String("BENCH");
    display.label(66, 8, 100, 10, tag, WHITE, false, false, NAVY);
    const bool leadsClear = fresh && !health.leadFlags;
    display.fillCircle(172, 12, 3, leadsClear ? pl::ui::MintGreen : pl::ui::WarnRed);
    display.label(180, 8, 74, 10, !fresh ? "C3 wait" : leadsClear ? "Leads clear" : "Lead-off",
                  leadsClear ? pl::ui::MintGreen : pl::ui::WarnRed, false, false, NAVY);
    display.fillRoundRect(258, 5, 54, 15, 3, pl::ui::CardBg);
    display.drawRoundRect(258, 5, 54, 15, 3, cardOk && !failed ? pl::ui::MintGreen : pl::ui::WarnRed);
    display.label(260, 7, 50, 11, cardOk && !failed ? "SD saved" : "SD error",
                  cardOk && !failed ? pl::ui::MintGreen : pl::ui::WarnRed, false, true);

    display.label(8, 23, 206, 10, "LEAD I (R-L) * Uncalibrated prototype", MUTED, false, false, NAVY);
    const unsigned secs = recording.captured / 250;
    char timer[24]; snprintf(timer, sizeof(timer), "REC %02u:%02u / 01:30", secs / 60, secs % 60);
    display.label(216, 23, 96, 10, timer, pl::ui::WarnAmber, false, true, NAVY);
  } else {
    if (fullRedraw) {
      display.setTextSize(2); display.setCursor(8, 8); display.setTextColor(WHITE, NAVY); display.print("Pulse");
      display.setTextColor(pl::ui::MintGreen, NAVY); display.print("Link");
      display.setTextSize(1); display.setTextColor(WHITE, NAVY);
      display.drawFastHLine(8, 32, 304, pl::ui::GridSlate);
    }
    const String headerContext = workflow.clinicSiteName()[0] ?
      String(workflow.clinicSiteName()) + (workflow.activeNurseName()[0] ? String(" * ") + workflow.activeNurseName() : String("")) :
      String("ESP32 Nurse Unit");
    display.label(122, 12, 190, 10, headerContext, MUTED, false, false, NAVY);
  }

  if (pairPage) {
    if (!wifiPage || !wifiView.editing()) {
      display.fillRoundRect(8, 36, 148, 22, 3, wifiPage ? TEAL : pl::ui::CardBg);
      display.drawRoundRect(8, 36, 148, 22, 3, wifiPage ? pl::ui::MintGreen : pl::ui::GridSlate);
      display.label(14, 41, 136, 12, "Wi-Fi & API", WHITE, false, true);
      display.fillRoundRect(164, 36, 148, 22, 3, wifiPage ? pl::ui::CardBg : TEAL);
      display.drawRoundRect(164, 36, 148, 22, 3, wifiPage ? pl::ui::GridSlate : pl::ui::MintGreen);
      display.label(170, 41, 136, 12, "BLE Transfer", WHITE, false, true);
    }
    if (wifiPage) { wifiView.draw(display, network, fullRedraw); return; }

    display.label(8, 63, 304, 10, String("Device: ") + ble.name(), WHITE, false, false, NAVY);
    if (fullRedraw) {
      display.fillRoundRect(8, 76, 304, 28, 4, pl::ui::CardBg);
      display.drawRoundRect(8, 76, 304, 28, 4, pl::ui::GridSlate);
      display.label(134, 85, 170, 10, "Private BLE passkey", MUTED, false, false, pl::ui::CardBg);
    }
    display.fillRect(16, 81, 112, 18, pl::ui::CardBg);
    display.setFont(&fonts::Font0); display.setTextSize(2);
    display.setTextColor(pairingOpen ? pl::ui::MintGreen : MUTED, pl::ui::CardBg);
    display.setCursor(18, 82);
    if (pairingOpen) display.printf("%06lu", (unsigned long)pairing.code);
    else display.print("------");
    display.setTextSize(1);

    const String bleLine = String("BLE: ") + (pairing.state == pl::BleState::Off ? "OFF" :
      pairing.state == pl::BleState::Secured ? (pairing.transferUnlocked ? "SECURED | Staff transfer ready" : "SECURED | Staff transfer locked") :
      pairingOpen ? "PAIRING | Enter code on phone" : "ENABLED | Pairing closed");
    display.label(8, 109, 304, 10, bleLine, pairing.state == pl::BleState::Secured ? pl::ui::MintGreen : WHITE, false, false, NAVY);

    const auto connections = network.view();
    char expiryAndNet[80];
    snprintf(expiryAndNet, sizeof(expiryAndNet), "Code expires: %us | Wi-Fi:%s API:%s",
             pairing.remainingSeconds,
             connections.wifi == WifiState::Connected ? "OK" : connections.wifi == WifiState::Connecting ? "WAIT" : "OFF",
             connections.api == ApiState::Ready ? "OK" : "--");
    display.label(8, 122, 304, 10, expiryAndNet, MUTED, false, false, NAVY);
    display.label(8, 136, 304, 12, bleNotice[0] ? bleNotice : ble.message(), pl::ui::WarnAmber, false, false, NAVY);

    display.fillRoundRect(8, 156, 148, 28, 4, TEAL);
    display.drawRoundRect(8, 156, 148, 28, 4, pl::ui::MintGreen);
    display.label(14, 164, 136, 12, pairing.state == pl::BleState::Off ? "Enable BLE" : "Disable BLE", WHITE, false, true);
    display.fillRoundRect(164, 156, 148, 28, 4, TEAL);
    display.drawRoundRect(164, 156, 148, 28, 4, pl::ui::MintGreen);
    display.label(170, 164, 136, 12, "Pair phone", WHITE, false, true);
  } else if (!workflow.monitor()) {
    workflow.draw(display, fresh, health.leadFlags, cardOk && !failed && !recoveryPending, uploader.message(), fullRedraw);
    return;
  } else {
    if (fullRedraw) {
      display.drawRoundRect(WaveX, WaveY, WaveWidth, WaveHeight, 4, pl::ui::TealBorder);
      display.fillRoundRect(BpmX, WaveY, BpmWidth, WaveHeight, 4, pl::ui::CardBg);
      display.drawRoundRect(BpmX, WaveY, BpmWidth, WaveHeight, 4, pl::ui::TealBorder);
      display.label(BpmX + 3, WaveY + 6, BpmWidth - 6, 10, "PULSE BPM", MUTED, false, true, pl::ui::CardBg);
      display.label(BpmX + 3, WaveY + 48, BpmWidth - 6, 10, "Prototype", MUTED, false, true, pl::ui::CardBg);
      display.label(BpmX + 3, WaveY + 62, BpmWidth - 6, 10, "5s rolling", MUTED, false, true, pl::ui::CardBg);
      display.label(BpmX + 3, WaveY + 76, BpmWidth - 6, 10, "Lead I", pl::ui::MintGreen, false, true, pl::ui::CardBg);
      display.label(BpmX + 3, WaveY + 90, BpmWidth - 6, 10, "AD8232", MUTED, false, true, pl::ui::CardBg);
      display.label(BpmX + 3, WaveY + 102, BpmWidth - 6, 10, "12-bit ADC", MUTED, false, true, pl::ui::CardBg);
      display.label(8, 154, 304, 10, "*ECG & BPM live on touchscreen & PulseLink app.", MUTED, false, false, NAVY);
      display.label(8, 166, 304, 10, "90s prototype capture; not diagnostic sufficiency.", pl::ui::WarnAmber, false, false, NAVY);
    }

    // Enlarged ECG Telemetry Area (WaveWidth = 238, WaveHeight = 114)
    display.fillRect(WaveX + 1, WaveY + 1, WaveWidth - 2, WaveHeight - 2, pl::ui::CardBg);
    for (int x = WaveX + 16; x < WaveX + WaveWidth; x += 16) display.drawFastVLine(x, WaveY + 1, WaveHeight - 2, pl::ui::GridSlate);
    for (int y = WaveY + 16; y < WaveY + WaveHeight; y += 16) display.drawFastHLine(WaveX + 1, y, WaveWidth - 2, pl::ui::GridSlate);
    for (unsigned x = 1; x < WaveWidth; ++x) {
      const int a = waveform[(waveHead + x - 1) % WaveWidth], b = waveform[(waveHead + x) % WaveWidth];
      if (fresh && requested && a >= 0 && b >= 0)
        display.drawLine(WaveX + x - 1, (WaveY + WaveHeight - 5) - a * (WaveHeight - 10) / 4095,
                         WaveX + x,     (WaveY + WaveHeight - 5) - b * (WaveHeight - 10) / 4095, pl::ui::MintGreen);
    }

    // Compact BPM Readout using uniform Font0 family (textSize = 2)
    const bool validBpm = fresh && health.bpmValid && health.recording;
    const String bpm = validBpm ? String(health.bpm) : String("--");
    display.fillRect(BpmX + 4, WaveY + 21, BpmWidth - 8, 22, pl::ui::CardBg);
    display.setFont(&fonts::Font0); display.setTextSize(2);
    display.setTextColor(validBpm ? pl::ui::MintGreen : MUTED, pl::ui::CardBg);
    display.setCursor(BpmX + (BpmWidth - display.textWidth(bpm)) / 2, WaveY + 24);
    display.print(bpm);
    display.setTextSize(1);

    const auto monitorNet = network.view();
    const char *statusLine = failed ? "Recording retained. Storage/link needs review." :
      requested ? (workflow.isActiveSessionSynchronised() && monitorNet.api == ApiState::Ready ? "Streaming live to app & microSD" : "Recording locally | Upload follows capture") :
      recording.complete() && monitorNet.api != ApiState::Ready ? "Saved locally | Upload retained for retry" :
      monitorNet.api == ApiState::Ready ? uploader.message() :
      monitorNet.wifi == WifiState::Connected ? "Wi-Fi connected | Synchronising API..." :
      "Wi-Fi offline | Saved recording retained";
    display.label(8, 178, 304, 10, statusLine,
                  failed ? pl::ui::WarnRed : monitorNet.api == ApiState::Ready ? pl::ui::MintGreen : WHITE,
                  false, false, NAVY);
  }

  const uint16_t leftFill = (!pairPage && requested) ? pl::ui::WarnRed : pl::ui::CardBg;
  const uint16_t leftBorder = (!pairPage && requested) ? pl::ui::WarnRed : pl::ui::TealBorder;
  display.fillRoundRect(8, 198, 148, 34, 4, leftFill);
  display.drawRoundRect(8, 198, 148, 34, 4, leftBorder);
  if (!pairPage && requested) display.fillCircle(22, 215, 4, WHITE);
  display.label((!pairPage && requested) ? 30 : 14, 209, (!pairPage && requested) ? 120 : 136, 12,
                pairPage ? (workflow.monitor() ? "ECG monitor" : "Nurse home") :
                requested ? (clinicalRecording ? "Stop Recording" : "Stop Bench Test") : "Nurse Home",
                WHITE, false, !(!pairPage && requested));

  const bool rightAlert = !pairPage && workflow.monitor() && workflow.unreadDoctorMessage();
  const bool rightDanger = pairPage && forgetConfirm;
  const uint16_t rightFill = rightDanger ? pl::ui::WarnRed : rightAlert ? TEAL : pl::ui::CardBg;
  const uint16_t rightBorder = rightDanger ? pl::ui::WarnRed : rightAlert ? pl::ui::MintGreen : pl::ui::TealBorder;
  display.fillRoundRect(164, 198, 148, 34, 4, rightFill);
  display.drawRoundRect(164, 198, 148, 34, 4, rightBorder);
  display.label(170, 209, 136, 12,
                pairPage ? (forgetConfirm ? "Confirm forget" : "Forget bonds") :
                workflow.monitor() ? (workflow.unreadDoctorMessage() ? "Doctor Comms (!)" : "Doctor Comms") : "Connections",
                WHITE, false, true);
}

void setup() {
  Serial.setTxBufferSize(512);
  Serial.begin(115200); screenBoot = pl::bootId();
#if CONFIG_IDF_TARGET_ESP32
  esp_bt_controller_mem_release(ESP_BT_MODE_CLASSIC_BT);
#endif
  for (int16_t &value : waveform) value = -1;
  c3.enableRxInternalPull(false); c3.setRxBufferSize(2048); c3.setTxBufferSize(512);
  c3.begin(115200, SERIAL_8N1, 35, 22);
  pinMode(33, OUTPUT); digitalWrite(33, HIGH); pinMode(25, OUTPUT); digitalWrite(25, LOW);
  pinMode(32, OUTPUT); pinMode(39, INPUT); pinMode(36, INPUT);
  pinMode(5, OUTPUT); digitalWrite(5, HIGH);
  display.init(); display.setRotation(1); pinMode(21, OUTPUT); digitalWrite(21, HIGH);
  display.fillScreen(NAVY); display.setTextColor(WHITE, NAVY); display.setCursor(12, 20); display.print("PulseLink | Checking recordings...");
  cardBus.begin(18, 19, 23, 5);
  cardOk = SD.begin(5, cardBus, 10000000) && SD.cardType() != CARD_NONE;
  if (cardOk) { cardOk = SD.exists("/pulselink") || SD.mkdir("/pulselink"); if (cardOk) recover(); }
  network.begin(ble.name());
  workflow.begin();
  draw();
}

void loop() {
  pl::Frame f;
  for (unsigned budget = 0; budget < 4096 && c3.available(); ++budget)
    if (parser.feed(c3.read(), f)) receive(f);
  const uint32_t now = millis();
  if (!failed && now - lastHello >= 1000) { lastHello = now; f = pl::frame(pl::Health, screenBoot); pl::send(c3, f); }
  if (requested && !recording.begun && now - lastStart >= 1000) {
    lastStart = now; f = pl::frame(pl::Start, screenBoot, 0, session); pl::put(f, pl::StartInfo{90, static_cast<uint8_t>(clinicalRecording ? 0 : 1)}); pl::send(c3, f);
  }
  if (requested && stopRequested && now - lastStop >= 300) {
    lastStop = now; f = pl::frame(pl::Stop, screenBoot, 0, session); pl::send(c3, f);
  }
  if (journal && c3At && now - c3At > 10000) {
    pl::debug("PL_FAULT c3_timeout\n");
    renameRecording(".interrupted"); liveStream.reset(); liveBatchReq = 0; requested = false; failed = true;
    if (clinicalRecording) workflow.finished(false);
  }
  handleTouch();
  if (requested) {
    if (auto r = network.takeResult(NetOwner::Upload)) {
      if (r->id == liveBatchReq) {
        liveBatchReq = 0;
        JsonDocument ack;
        if (r->status >= 200 && r->status < 300 && !deserializeJson(ack, r->body) && ack["persisted"] == true && liveContiguous) {
          liveAckSeq = liveReqSeq; liveAckCrc = liveReqCrc; liveAckValid = true;
        } else {
          liveContiguous = false;
        }
      }
      delete r;
    }
  }
  workflow.poll(requested, cardOk && !failed);
  if (!requested && workflow.wantsBench()) { clinicalRecording = false; requestRecording(false); }
  if (!requested && workflow.start(session)) workflow.started(requestRecording(true));
  if (!workflow.busy()) uploader.poll(requested, cardOk && !failed);
  if (forgetConfirm && now - forgetConfirmAt >= 5000) { forgetConfirm = false; bleNotice = ""; }
  if (waveDirty && requested && workflow.monitor() && !pairPage && now - lastWaveUi >= 200) {
    lastWaveUi = now; waveDirty = false; draw();
  }
  if (now - lastUi >= 1000) {
    lastUi = now; lastWaveUi = now; waveDirty = false; const auto connectionLoop = network.view(); ble.poll(c3At && now - c3At < 3000, cardOk && !failed, requested, workflow.activeGrantId(), workflow.activeNurseUid(), workflow.activeSiteId(), workflow.canWork(), static_cast<uint8_t>(connectionLoop.wifi), static_cast<uint8_t>(connectionLoop.api), &clinicalStore); draw();
    const auto connection = network.view();
    char log[220];
    snprintf(log, sizeof(log), "PL_SCREEN c3=%u ble=%u sd=%u recording=%u samples=%lu errors=%lu recovered=%lu failed=%u heap=%lu min_heap=%lu wifi=%u api=%u stored=%u\n",
      c3At && now - c3At < 3000, static_cast<unsigned>(ble.view().state), cardOk,
      requested, (unsigned long)recording.captured, (unsigned long)parser.invalid, (unsigned long)recovered, failed,
      (unsigned long)ESP.getFreeHeap(), (unsigned long)ESP.getMinFreeHeap(),
      static_cast<unsigned>(connection.wifi), static_cast<unsigned>(connection.api), connection.settingsStored);
    pl::debug(log);
    // Observe acquisition via its existing UART heartbeat without opening native C3 USB.
    snprintf(log, sizeof(log), "PL_ACQ captured=%lu slots=%lu missed=%lu late_us=%lu queue=%u recording=%u leads=%u lo_p=%u lo_m=%u raw=%u\n",
      (unsigned long)health.captured, (unsigned long)health.slots, (unsigned long)health.missedSlots,
      (unsigned long)health.maxLatenessUs, health.queueDepth, health.recording,
      health.leadFlags, (health.leadFlags & pl::LeadPlus) != 0, (health.leadFlags & pl::LeadMinus) != 0, health.raw);
    pl::debug(log);
    static uint32_t reportedRequest = 0;
    if (connection.requestCount != reportedRequest) {
      reportedRequest = connection.requestCount;
      snprintf(log, sizeof(log), "PL_NET req=%lu kind=%u http=%d tls=%d ms=%lu rssi=%d block=%lu\n",
        (unsigned long)connection.requestCount, connection.requestKind, connection.httpCode, connection.tlsError,
        (unsigned long)connection.requestMs, connection.rssi, (unsigned long)connection.maxBlock);
      pl::debug(log);
    }
  }
  delay(1);
}
