#pragma once
#include <Arduino.h>
#include <SD.h>
#include <ArduinoJson.h>
#include <esp_mac.h>
#include <NimBLEDevice.h>
#if __has_include(<PulseLinkPairing.h>)
#include <PulseLinkPairing.h>
#else
#include "../../libraries/PulseLinkCore/src/PulseLinkPairing.h"
#endif
#if __has_include(<PulseLinkUpload.h>)
#include <PulseLinkUpload.h>
#else
#include "../../libraries/PulseLinkCore/src/PulseLinkUpload.h"
#endif
#include <memory>
#include "PulseLinkClinicalStore.h"
#if MYNEWT_VAL(BLE_SM_LEGACY)
#error Use build_opt.h to disable legacy BLE pairing.
#endif

// BLE pairs the ESP32-2432S028R touchscreen with an Android phone.
// C3 remains wired over UART. Live ECG waveform and live BPM are never streamed over BLE.
class PulseLinkBle : private NimBLEServerCallbacks, private NimBLECharacteristicCallbacks {
  portMUX_TYPE lock = portMUX_INITIALIZER_UNLOCKED;
  pl::PairingPolicy policy;
  NimBLEServer *server = nullptr;
  NimBLECharacteristic *status = nullptr;
  NimBLECharacteristic *control = nullptr;
  NimBLECharacteristic *chunk = nullptr;
  uint16_t peer = BLE_HS_CONN_HANDLE_NONE;
  char name_[24] = {};
  bool initialised = false;
  bool shuttingDown = false;
  bool authenticationFailed = false;
  bool bondedReconnectAttempt = false;
  bool pendingDeinit = false;
  const char *feedback = "BLE is off. Enable BLE to pair.";

  // Authorised background transfer command queued from GATT write callback and serviced on the display task.
  char pendingCommand[196] = {};
  bool hasPendingCommand = false;
  char activeGrantId_[36] = {}, activeNurseUid_[129] = {}, activeSiteId_[65] = {};
  bool shiftAllows_ = false;
  uint32_t queuedCount_ = 0;

  void deinitialiseBle() {
    if (!initialised) { pendingDeinit = false; return; }
    NimBLEDevice::deinit(true);
    server = nullptr; status = nullptr; control = nullptr; chunk = nullptr;
    initialised = false; pendingDeinit = false;
  }

  bool failedInitialisation() {
    NimBLEDevice::deinit(true);
    server = nullptr; status = nullptr; control = nullptr; chunk = nullptr; initialised = false;
    pendingDeinit = false;
    portENTER_CRITICAL(&lock);
    policy.disable(); peer = BLE_HS_CONN_HANDLE_NONE; shuttingDown = false; bondedReconnectAttempt = false;
    feedback = "BLE unavailable. Restart and try again.";
    portEXIT_CRITICAL(&lock);
    return false;
  }

  void onConnect(NimBLEServer *s, NimBLEConnInfo &info) override {
    const bool peerBonded = info.isBonded() || NimBLEDevice::isBonded(info.getAddress());
    portENTER_CRITICAL(&lock);
    const bool accepted = !shuttingDown && policy.connect(millis(), peerBonded);
    if (accepted) {
      peer = info.getConnHandle();
      bondedReconnectAttempt = peerBonded;
    }
    portEXIT_CRITICAL(&lock);
    if (!accepted) { s->disconnect(info); return; }
    s->updateConnParams(info.getConnHandle(), 24, 40, 0, 200);
    if (peerBonded) NimBLEDevice::startSecurity(info.getConnHandle());
  }
  uint32_t onPassKeyDisplay() override { return view().code; }
  void onAuthenticationComplete(NimBLEConnInfo &info) override {
    portENTER_CRITICAL(&lock);
    const bool accepted = peer == info.getConnHandle() && policy.authenticate(millis(),
      info.isEncrypted(), info.isAuthenticated(), info.isBonded(), info.getSecKeySize(), bondedReconnectAttempt);
    feedback = accepted ? "Phone secured. Awaiting nurse shift check." : "Pairing failed. Check code; tap Pair phone.";
    authenticationFailed = !accepted;
    bondedReconnectAttempt = false;
    portEXIT_CRITICAL(&lock);
    if (!accepted) server->disconnect(info);
  }
  void onDisconnect(NimBLEServer *, NimBLEConnInfo &info, int) override {
    portENTER_CRITICAL(&lock);
    if (peer == info.getConnHandle()) {
      if (!authenticationFailed && policy.view(millis()).state == pl::BleState::Secured) feedback = "Phone disconnected. Tap Pair phone.";
      else if (!authenticationFailed && policy.view(millis()).state == pl::BleState::Connecting) feedback = "Pairing ended. Check code; tap Pair phone.";
      authenticationFailed = false;
      bondedReconnectAttempt = false;
      hasPendingCommand = false; pendingCommand[0] = 0;
      peer = BLE_HS_CONN_HANDLE_NONE; policy.disconnect(); shuttingDown = false;
    }
    portEXIT_CRITICAL(&lock);
  }
  void onWrite(NimBLECharacteristic *chr, NimBLEConnInfo &info) override {
    if (chr != control || !info.isEncrypted() || !info.isAuthenticated() || !info.isBonded()) return;
    const std::string value = chr->getValue();
    if (value.empty() || value.size() >= sizeof(pendingCommand)) return;
    portENTER_CRITICAL(&lock);
    if (peer == info.getConnHandle() && policy.view(millis()).state == pl::BleState::Secured) {
      memcpy(pendingCommand, value.data(), value.size());
      pendingCommand[value.size()] = 0;
      hasPendingCommand = true;
      policy.touchActivity(millis());
    }
    portEXIT_CRITICAL(&lock);
  }

  void stopConnection(bool disableMode) {
    portENTER_CRITICAL(&lock);
    const uint16_t previous = peer;
    authenticationFailed = false;
    bondedReconnectAttempt = false;
    hasPendingCommand = false; pendingCommand[0] = 0;
    if (disableMode) { policy.disable(); feedback = "BLE is off. Enable BLE to pair."; pendingDeinit = true; } else policy.disconnect();
    shuttingDown = previous != BLE_HS_CONN_HANDLE_NONE;
    portEXIT_CRITICAL(&lock);
    if (initialised) {
      NimBLEDevice::getAdvertising()->stop();
      if (choiceValid() && chunk) chunk->setValue("{\"status\":\"locked\"}");
      if (previous != BLE_HS_CONN_HANDLE_NONE && !server->disconnect(previous)) {
        portENTER_CRITICAL(&lock);
        if (peer == previous) { peer = BLE_HS_CONN_HANDLE_NONE; shuttingDown = false; }
        portEXIT_CRITICAL(&lock);
      }
      if (disableMode && peer == BLE_HS_CONN_HANDLE_NONE) {
        deinitialiseBle();
      }
    }
  }
  bool choiceValid() const { return initialised && server != nullptr; }

  static bool hasUploadedReceipt(const String &sessionId, uint32_t fileBytes, const String &uploadedPath) {
    File marker = SD.open(uploadedPath, FILE_READ);
    if (!marker) return false;
    if (marker.size() > 4096) { marker.close(); return false; }
    JsonDocument json;
    const bool valid = !deserializeJson(json, marker) && json["sessionId"] == sessionId &&
      json["status"] == "Completed" && json["fileBytes"] == fileBytes &&
      json["receivedSampleCount"].as<uint32_t>() > 0 && json["receivedBatchCount"].as<uint32_t>() > 0;
    marker.close();
    return valid;
  }

  void servicePendingCommand(bool recording, bool cardReady, PulseLinkClinicalStore *store) {
    char cmd[196] = {};
    portENTER_CRITICAL(&lock);
    if (hasPendingCommand) {
      strlcpy(cmd, pendingCommand, sizeof(cmd));
      hasPendingCommand = false;
      pendingCommand[0] = 0;
    }
    portEXIT_CRITICAL(&lock);
    if (!cmd[0] || !chunk) return;
    if (recording || !cardReady || !store) {
      chunk->setValue("{\"status\":\"busy\",\"reason\":\"recording_or_sd_unavailable\"}");
      return;
    }
    JsonDocument req;
    if (deserializeJson(req, cmd)) {
      chunk->setValue("{\"status\":\"error\",\"reason\":\"invalid_command\"}");
      return;
    }
    const char *op = req["op"] | "";
    const uint32_t now = millis();
    if (!strcmp(op, "auth")) {
      const char *reqGrant = req["grantId"] | "";
      const char *reqNurse = req["nurseUid"] | "";
      portENTER_CRITICAL(&lock);
      const bool ok = policy.authoriseTransfer(now, activeGrantId_, activeNurseUid_, shiftAllows_, reqGrant, reqNurse);
      feedback = ok ? "Phone authorised for offline transfer." : "Nurse shift does not match this unit.";
      portEXIT_CRITICAL(&lock);
      chunk->setValue(ok ? "{\"status\":\"authorised\",\"staffTransfer\":\"unlocked\"}" :
                           "{\"status\":\"denied\",\"staffTransfer\":\"locked\"}");
      return;
    }
    portENTER_CRITICAL(&lock);
    const bool allowed = policy.transferAllowed(now, activeGrantId_, shiftAllows_);
    portEXIT_CRITICAL(&lock);
    if (!allowed) {
      chunk->setValue("{\"status\":\"locked\",\"reason\":\"authorisation_required\"}");
      return;
    }
    if (!strcmp(op, "list")) {
      File dir = SD.open("/pulselink");
      if (!dir) { chunk->setValue("{\"status\":\"error\",\"reason\":\"sd_unavailable\"}"); return; }
      JsonDocument out; out["status"] = "ok";
      auto items = out["sessions"].to<JsonArray>();
      unsigned count = 0;
      for (File f = dir.openNextFile(); f && count < 8; f = dir.openNextFile()) {
        String fname = f.name();
        const uint32_t fsize = f.size();
        const bool isReady = !f.isDirectory() && fname.endsWith(".ready");
        f.close();
        if (!isReady) continue;
        const String full = fname.startsWith("/") ? fname : String("/pulselink/") + fname;
        const String base = full.substring(full.lastIndexOf('/') + 1, full.length() - 6);
        if (base.length() != 32 || hasUploadedReceipt("SESSION-" + base, fsize, full.substring(0, full.length() - 6) + ".uploaded"))
          continue;
        JsonDocument meta;
        if (!store->load(String("/pulselink/private/r-") + base, meta) || !meta["patientId"].is<const char *>())
          continue;
        items.add("SESSION-" + base);
        ++count;
      }
      dir.close();
      portENTER_CRITICAL(&lock); queuedCount_ = count; portEXIT_CRITICAL(&lock);
      String payload; serializeJson(out, payload);
      chunk->setValue(payload.c_str());
      return;
    }
    const String sessionId = req["sessionId"] | "";
    if (!sessionId.startsWith("SESSION-") || sessionId.length() != 40) {
      chunk->setValue("{\"status\":\"error\",\"reason\":\"invalid_session_id\"}");
      return;
    }
    const String base = sessionId.substring(8);
    const String readyPath = String("/pulselink/") + base + ".ready";
    const String metaPath = String("/pulselink/private/r-") + base;
    if (!strcmp(op, "manifest")) {
      JsonDocument meta;
      File file = SD.open(readyPath, FILE_READ);
      if (!file || !store->load(metaPath, meta)) {
        if (file) file.close();
        chunk->setValue("{\"status\":\"error\",\"reason\":\"recording_not_found\"}");
        return;
      }
      uint8_t expected[16] = {};
      for (unsigned i = 0; i < 16; ++i) {
        char pair[3] = {base[i * 2], base[i * 2 + 1], 0};
        expected[i] = static_cast<uint8_t>(strtoul(pair, nullptr, 16));
      }
      pl::UploadJournal reader(expected);
      uint32_t totalBatches = 0;
      while (file.available() && reader.state != pl::UploadReadState::Invalid && reader.state != pl::UploadReadState::Bench) {
        reader.feed(file.read());
        if (reader.state == pl::UploadReadState::Ready) { ++totalBatches; reader.nextBatch(); }
      }
      reader.endOfFile(); file.close();
      if (reader.state != pl::UploadReadState::Complete) {
        chunk->setValue("{\"status\":\"error\",\"reason\":\"invalid_or_bench_journal\"}");
        return;
      }
      const uint32_t resumeSeq = req["resumeSequence"] | 0u;
      portENTER_CRITICAL(&lock);
      policy.beginSessionTransfer(now, sessionId.c_str(), resumeSeq);
      portEXIT_CRITICAL(&lock);
      char boot[17]; snprintf(boot, sizeof(boot), "%016llx", reader.record.identity.boot);
      JsonDocument out;
      out["status"] = "manifest";
      out["sessionId"] = sessionId;
      out["recordingId"] = meta["recordingId"] | "";
      out["patientId"] = meta["patientId"] | "";
      out["patientName"] = meta["patientName"] | "";
      out["deviceId"] = name();
      out["operatorUid"] = (meta["operatorUid"] | activeNurseUid_);
      out["siteId"] = (meta["siteId"] | activeSiteId_);
      out["grantId"] = meta["grantId"] | activeGrantId_;
      out["bootId"] = boot;
      out["startedAtUtc"] = meta["startedAtUtc"] | "";
      out["durationSeconds"] = reader.record.nextIndex / 250;
      out["expectedBatchCount"] = totalBatches;
      out["expectedSampleCount"] = reader.record.captured;
      String payload; serializeJson(out, payload);
      chunk->setValue(payload.c_str());
      return;
    }
    if (!strcmp(op, "batch")) {
      const uint32_t targetSeq = req["sequence"] | 0u;
      File file = SD.open(readyPath, FILE_READ);
      if (!file) { chunk->setValue("{\"status\":\"error\",\"reason\":\"recording_not_found\"}"); return; }
      uint8_t expected[16] = {};
      for (unsigned i = 0; i < 16; ++i) {
        char pair[3] = {base[i * 2], base[i * 2 + 1], 0};
        expected[i] = static_cast<uint8_t>(strtoul(pair, nullptr, 16));
      }
      pl::UploadJournal reader(expected);
      bool found = false;
      while (file.available() && reader.state != pl::UploadReadState::Invalid && reader.state != pl::UploadReadState::Bench) {
        reader.feed(file.read());
        if (reader.state == pl::UploadReadState::Ready) {
          if (reader.sequence == targetSeq) { found = true; break; }
          reader.nextBatch();
        }
      }
      file.close();
      if (!found) { chunk->setValue("{\"status\":\"error\",\"reason\":\"batch_not_found\"}"); return; }
      char boot[17]; snprintf(boot, sizeof(boot), "%016llx", reader.record.identity.boot);
      JsonDocument out;
      out["status"] = "batch";
      out["sessionId"] = sessionId;
      out["bootId"] = boot;
      out["sequence"] = reader.sequence;
      out["firstSampleIndex"] = reader.samples[0].index;
      out["firstSampleTimestampUs"] = reader.samples[0].us;
      out["sampleCount"] = reader.count;
      out["estimatedBpm"] = reader.bpm;
      auto raw = out["samples"].to<JsonArray>();
      for (unsigned i = 0; i < reader.count; ++i) raw.add(reader.samples[i].raw);
      String payload; serializeJson(out, payload);
      chunk->setValue(payload.c_str());
      return;
    }
    if (!strcmp(op, "ack")) {
      const uint32_t seq = req["sequence"] | 0u;
      portENTER_CRITICAL(&lock);
      const bool ok = policy.acknowledgeBatch(now, sessionId.c_str(), seq);
      portEXIT_CRITICAL(&lock);
      chunk->setValue(ok ? "{\"status\":\"ack_ok\"}" : "{\"status\":\"error\",\"reason\":\"sequence_mismatch\"}");
      return;
    }
    if (!strcmp(op, "complete")) {
      const uint32_t expectedBatches = req["expectedBatchCount"] | 0u;
      const uint32_t expectedSamples = req["expectedSampleCount"] | 0u;
      File file = SD.open(readyPath, FILE_READ);
      const uint32_t fsize = file ? file.size() : 0;
      if (file) file.close();
      portENTER_CRITICAL(&lock);
      const bool ok = fsize > 0 && policy.completeSessionTransfer(now, sessionId.c_str(), expectedBatches);
      if (ok) feedback = "Recording transferred to phone.";
      portEXIT_CRITICAL(&lock);
      if (!ok) {
        chunk->setValue("{\"status\":\"error\",\"reason\":\"incomplete_batches\"}");
        return;
      }
      File marker = SD.open(String("/pulselink/") + base + ".uploaded", FILE_WRITE);
      if (marker) {
        JsonDocument receipt;
        receipt["sessionId"] = sessionId;
        receipt["status"] = "Completed";
        receipt["transport"] = "ble";
        receipt["fileBytes"] = fsize;
        receipt["receivedBatchCount"] = expectedBatches;
        receipt["receivedSampleCount"] = expectedSamples;
        serializeJson(receipt, marker); marker.flush(); marker.close();
      }
      chunk->setValue("{\"status\":\"completed\"}");
    }
  }
public:
  const char *name() {
    if (!name_[0]) {
      uint8_t mac[6]; ESP_ERROR_CHECK(esp_efuse_mac_get_default(mac));
      snprintf(name_, sizeof(name_), "PulseLink-%02X%02X%02X", mac[3], mac[4], mac[5]);
    }
    return name_;
  }
  bool enable() {
    if (!initialised) {
      // Initialise only while idle, so allocation cannot interrupt a recording.
      if (!NimBLEDevice::init(name())) return failedInitialisation();
      NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);
      NimBLEDevice::setSecurityAuth(true, true, true);
      server = NimBLEDevice::createServer();
      if (!server) return failedInitialisation();
      server->setCallbacks(this, false);
      server->advertiseOnDisconnect(false);
      auto service = server->createService("b6f0a100-5664-4fb0-9f96-6f60754c4e4b");
      if (!service) return failedInitialisation();
      status = service->createCharacteristic("b6f0a101-5664-4fb0-9f96-6f60754c4e4b",
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC | NIMBLE_PROPERTY::READ_AUTHEN, 160);
      control = service->createCharacteristic("b6f0a102-5664-4fb0-9f96-6f60754c4e4b",
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC | NIMBLE_PROPERTY::WRITE_AUTHEN, 196);
      chunk = service->createCharacteristic("b6f0a103-5664-4fb0-9f96-6f60754c4e4b",
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC | NIMBLE_PROPERTY::READ_AUTHEN, 512);
      if (!status || !control || !chunk) return failedInitialisation();
      control->setCallbacks(this);
      status->setValue("PulseLink;staff_transfer=locked");
      chunk->setValue("{\"status\":\"locked\"}");
      service->start();
      auto advertising = NimBLEDevice::getAdvertising();
      advertising->enableScanResponse(true);
      if (!advertising->setName(name_) || !advertising->addServiceUUID(service->getUUID())) return failedInitialisation();
      initialised = true;
    }
    portENTER_CRITICAL(&lock); policy.enable(); feedback = "BLE enabled. Tap Pair phone."; portEXIT_CRITICAL(&lock);
    return true;
  }
  void disable() { stopConnection(true); }
  bool pair() {
    if (!initialised) return false;
    uint32_t random; do { random = esp_random(); } while (random >= 4294000000u);
    const uint32_t pin = random % 1000000;
    portENTER_CRITICAL(&lock);
    const bool accepted = !shuttingDown && policy.open(millis(), pin);
    feedback = accepted ? "Enter this code on the phone. Keep it private." : "Enable BLE or disconnect the phone first.";
    portEXIT_CRITICAL(&lock);
    if (!accepted) return false;
    NimBLEDevice::getAdvertising()->stop();
    NimBLEDevice::setSecurityPasskey(pin);
    if (!NimBLEDevice::getAdvertising()->start()) {
      stopConnection(false); portENTER_CRITICAL(&lock); feedback = "Pairing could not start. Please try again."; portEXIT_CRITICAL(&lock); return false;
    }
    return true;
  }
  bool forget() {
    if (!initialised && !enable()) return false;
    stopConnection(true);
    return initialised && NimBLEDevice::deleteAllBonds();
  }
  pl::PairingView view() {
    portENTER_CRITICAL(&lock); const auto value = policy.view(millis()); portEXIT_CRITICAL(&lock);
    return value;
  }
  const char *message() {
    portENTER_CRITICAL(&lock); const char *value = feedback; portEXIT_CRITICAL(&lock); return value;
  }
  void releaseIfIdle() {
    if (!initialised) return;
    const auto state = view().state;
    if (peer == BLE_HS_CONN_HANDLE_NONE && state != pl::BleState::Pairing && state != pl::BleState::Connecting) {
      stopConnection(true);
    }
  }
  void poll(bool c3Fresh, bool cardReady, bool recording = false,
            const char *grantId = "", const char *nurseUid = "", const char *siteId = "",
            bool shiftAllows = false, uint8_t wifiState = 0, uint8_t apiState = 0,
            PulseLinkClinicalStore *store = nullptr) {
    portENTER_CRITICAL(&lock);
    strlcpy(activeGrantId_, grantId ? grantId : "", sizeof(activeGrantId_));
    strlcpy(activeNurseUid_, nurseUid ? nurseUid : "", sizeof(activeNurseUid_));
    strlcpy(activeSiteId_, siteId ? siteId : "", sizeof(activeSiteId_));
    shiftAllows_ = shiftAllows;
    if (!shiftAllows_) policy.revokeTransfer();
    const bool expired = policy.expire(millis());
    if (expired) feedback = "BLE session timed out. Tap Pair phone.";
    const auto currentView = policy.view(millis());
    const uint32_t queued = queuedCount_;
    const bool shouldDeinit = pendingDeinit && peer == BLE_HS_CONN_HANDLE_NONE;
    portEXIT_CRITICAL(&lock);
    if (expired) {
      stopConnection(false);
      if (peer == BLE_HS_CONN_HANDLE_NONE) deinitialiseBle();
    } else if (shouldDeinit) {
      deinitialiseBle();
    }
    if (initialised) {
      servicePendingCommand(recording, cardReady, store);
      char statusLine[160] = {};
      snprintf(statusLine, sizeof(statusLine),
        "PulseLink;device=%s;c3_uart=%s;sd=%s;wifi=%u;api=%u;staff_transfer=%s;queued=%lu",
        name(),
        c3Fresh ? "ok" : "lost",
        cardReady ? "ready" : "unavailable",
        static_cast<unsigned>(wifiState),
        static_cast<unsigned>(apiState),
        currentView.transferUnlocked ? "unlocked" : "locked",
        static_cast<unsigned long>(queued));
      status->setValue(statusLine);
    }
  }
};
