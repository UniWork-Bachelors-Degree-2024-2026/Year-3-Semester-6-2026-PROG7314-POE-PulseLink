#pragma once
#include <SD.h>
#include <ArduinoJson.h>
#if __has_include(<PulseLinkUpload.h>)
#include <PulseLinkUpload.h>
#else
#include "../../libraries/PulseLinkCore/src/PulseLinkUpload.h"
#endif
#include <memory>
#include "PulseLinkNetwork.h"
#include "PulseLinkClinicalStore.h"

class PulseLinkUploader {
  PulseLinkNetwork &net;
  PulseLinkClinicalStore &privateStore;
  String path, session;
  uint8_t selectedSessionBytes[16] = {};
  uint32_t inputOffset = 0, inputBytes = 0;
  uint16_t directoryCursor = 0;
  std::unique_ptr<pl::UploadJournal> reader;
  uint32_t requestId = 0, requestAt = 0, checkAt = 0, retryAt = 0;
  uint32_t resumeSequence = 0, resumeCrc = 0;
  bool checkedOnce = false;
  enum class Stage { Idle, Permission, Reading, Batch, Complete } stage = Stage::Idle;
  const char *notice = "No authorised recordings queued.";

  String checkpoint() const { return path.substring(0, path.length() - 6) + ".sync"; }
  String uploaded() const { return path.substring(0, path.length() - 6) + ".uploaded"; }
  bool hasReceipt(const String &id, uint32_t bytes) {
    File marker = SD.open(uploaded(), FILE_READ);
    if (!marker) return false;
    if (marker.size() > 4096) { marker.close(); return false; }
    JsonDocument json;
    const bool valid = !deserializeJson(json, marker) && json["sessionId"] == id &&
      json["status"] == "Completed" && json["fileBytes"] == bytes &&
      json["receivedSampleCount"].as<uint32_t>() > 0 && json["receivedBatchCount"].as<uint32_t>() > 0;
    marker.close(); return valid;
  }
  void close(const char *message) {
    char diagnostic[120]; snprintf(diagnostic, sizeof(diagnostic), "PL_UPLOAD state=%u %s\n", static_cast<unsigned>(stage), message); pl::debug(diagnostic);
    reader.reset(); inputOffset = 0; inputBytes = 0; notice = message; stage = Stage::Idle; checkAt = millis(); requestId = 0;
    net.holdBackground(false, NetOwner::Upload);
  }
  void saveCheckpoint() {
    File file = SD.open(checkpoint(), FILE_WRITE);
    if (!file) return;
    JsonDocument json; json["nextSequence"] = reader->sequence + 1; json["prefixCrc"] = reader->prefixCrc;
    json["fileBytes"] = inputBytes; serializeJson(json, file); file.flush(); file.close();
  }
  void loadCheckpoint() {
    resumeSequence = resumeCrc = 0;
    File file = SD.open(checkpoint(), FILE_READ);
    if (!file) return;
    if (file.size() > 256) { file.close(); return; }
    JsonDocument json;
    if (!deserializeJson(json, file)) {
      const uint32_t next = json["nextSequence"] | 0u;
      if (next <= 600 && (json["fileBytes"] | 0u) == inputBytes) {
        resumeSequence = next; resumeCrc = json["prefixCrc"] | 0u;
      }
    }
    file.close();
  }
  void openNext() {
    if (!net.reserveUploadMemory()) return;
    File directory = SD.open("/pulselink");
    if (!directory) { close("Recording directory unavailable."); return; }
    for (uint16_t skip = 0; skip < directoryCursor; ++skip) {
      File skipped = directory.openNextFile();
      if (!skipped) {
        directory.close();
        directoryCursor = 0;
        directory = SD.open("/pulselink");
        if (!directory) { close("Recording directory unavailable."); return; }
        break;
      }
      skipped.close();
    }
    unsigned visited = 0, readyFiles = 0, receipted = 0, metadataMissing = 0, pendingBinding = 0, review = 0, invalid = 0, bench = 0;
    // Retain a lightweight directory index so a large queue cannot starve later recordings without holding VFS buffers during TLS.
    while (visited++ < 128) {
      File file = directory.openNextFile();
      if (!file) { directoryCursor = 0; break; }
      ++directoryCursor;
      String name = file.name();
      if (file.isDirectory() || !name.endsWith(".ready")) { file.close(); continue; }
      ++readyFiles;
      path = name.startsWith("/") ? name : String("/pulselink/") + name;
      const String base = path.substring(path.lastIndexOf('/') + 1, path.length() - 6);
      if (base.length() != 32) { file.close(); continue; }
      bool hex = true; for (char c : base) if (!isxdigit(c)) hex = false;
      const uint32_t fileBytes = file.size();
      if (!hex || fileBytes == 0 || fileBytes > 600000) { ++invalid; file.close(); continue; }
      if (hasReceipt("SESSION-" + base, fileBytes)) { ++receipted; file.close(); continue; }
      JsonDocument metadata;
      if (!privateStore.load(String("/pulselink/private/r-") + base, metadata)) { ++metadataMissing; file.close(); continue; }
      if (metadata["synchronised"] != true || !metadata["patientId"].is<const char *>()) {
        if (metadata["review"] == true) ++review; else ++pendingBinding;
        file.close(); continue;
      }
      String identity = metadata["recordingId"].as<String>(); identity.replace("-", "");
      if (!base.equalsIgnoreCase(identity)) { ++invalid; file.close(); continue; }
      pl::Parser probe; pl::Frame first; bool found = false;
      for (unsigned budget = 0; budget < 256 && file.available(); ++budget) if (probe.feed(file.read(), first)) { found = true; break; }
      pl::StartInfo start;
      if (!found || first.h.type != pl::Begin || !pl::get(first, start)) { ++invalid; file.close(); continue; }
      if (start.benchOnly != 0) { ++bench; file.close(); continue; }
      char headerId[33]; for (unsigned i = 0; i < 16; ++i) snprintf(headerId + i * 2, 3, "%02x", first.h.session[i]);
      if (!base.equalsIgnoreCase(headerId)) { file.close(); continue; }
      file.close();
      memcpy(selectedSessionBytes, first.h.session, sizeof(selectedSessionBytes));
      inputBytes = fileBytes; inputOffset = 0;
      session = "SESSION-" + base;
      stage = Stage::Permission; notice = "Checking upload permission..."; break;
    }
    directory.close();
    char diagnostic[200]; snprintf(diagnostic, sizeof(diagnostic), "PL_QUEUE ready=%u uploaded=%u missing_metadata=%u pending_binding=%u review=%u invalid=%u bench=%u selected=%u\n",
      readyFiles, receipted, metadataMissing, pendingBinding, review, invalid, bench, stage != Stage::Idle); pl::debug(diagnostic);
    checkAt = millis(); checkedOnce = true;
    if (stage == Stage::Idle) net.holdBackground(false, NetOwner::Upload);
  }
  void readBatch() {
    File input = SD.open(path, FILE_READ);
    if (!input || input.size() != inputBytes || !input.seek(inputOffset)) {
      if (input) input.close();
      close("Recording file unavailable. Retrying shortly."); retryAt = millis() + 5000; return;
    }
    for (unsigned budget = 0; budget < 4096 && input.available() && reader->state == pl::UploadReadState::Reading; ++budget)
      reader->feed(input.read());
    inputOffset = input.position();
    const bool atEnd = !input.available();
    input.close();
    if (atEnd && reader->state == pl::UploadReadState::Reading) reader->endOfFile();
    if (reader->state == pl::UploadReadState::Invalid || reader->state == pl::UploadReadState::Bench) {
      close("Invalid or bench recording. Not uploaded."); return;
    }
    if (reader->state == pl::UploadReadState::Ready && reader->sequence < resumeSequence) {
      if (reader->sequence + 1 == resumeSequence && reader->prefixCrc != resumeCrc) {
        close("Local recording changed. Review required."); return;
      }
      reader->nextBatch();
    }
  }
  void sendBatch() {
    String body;
    if (!body.reserve(4600)) {
      close("Upload memory tight. Retrying shortly."); retryAt = millis() + 5000; return;
    }
    {
      JsonDocument json; const auto config = net.settings();
      const auto &samples = reader->samples; const uint16_t count = reader->count, bpm = reader->bpm;
      json["schemaVersion"] = 2; json["sessionId"] = session; json["deviceId"] = config.deviceId;
      char boot[17]; snprintf(boot, sizeof(boot), "%016llx", reader->record.identity.boot);
      json["bootId"] = boot; json["sequence"] = reader->sequence; json["firstSampleIndex"] = samples[0].index;
      json["timestamp"] = samples[0].us / 1000; json["firstSampleTimestampUs"] = samples[0].us;
      json["sampleRateHz"] = 250; json["firmwareVer"] = "pulselink-wifi-1";
      json["estimatedBpm"] = bpm; json["adaptiveThreshold"] = 0;
      bool leads = true, flagged = false;
      auto raw = json["samples"].to<JsonArray>(); auto offsets = json["sampleOffsetsUs"].to<JsonArray>();
      auto flags = json["sampleFlags"].to<JsonArray>();
      for (unsigned i = 0; i < count; ++i) {
        raw.add(samples[i].raw); offsets.add(samples[i].us - samples[0].us); flags.add(samples[i].flags);
        leads &= (samples[i].flags & 3) == 0; flagged |= samples[i].flags != 0;
      }
      json["leadConnected"] = leads; json["signalQuality"] = !leads ? "LeadsDisconnected" : flagged ? "Degraded" : bpm ? "Good" : "Unknown";
      serializeJson(json, body);
    }
    requestId = net.submitMoved(("/api/device-transport/recordings/" + session + "/batches").c_str(), true, std::move(body));
    if (requestId) { stage = Stage::Batch; notice = "Uploading saved recording..."; }
    else { close("Upload queue busy. Retrying shortly."); retryAt = millis() + 5000; }
  }
public:
  PulseLinkUploader(PulseLinkNetwork &net, PulseLinkClinicalStore &storage) : net(net), privateStore(storage) { }
  const char *message() const { return notice; }
  void poll(bool recording, bool cardReady) {
    // Only the display task touches SD; HTTPS runs independently without holding files or locks.
    if (recording || !cardReady) { if (stage != Stage::Idle) close("Upload paused. Saved recording retained."); return; }
    if (requestId && millis() - requestAt > 45000) {
      close("Upload timed out. Saved recording will retry."); retryAt = millis() + 10000;
    }
    if (auto result = net.takeResult()) {
      if (result->id != requestId) { delete result; return; }
      requestId = 0; JsonDocument json; const bool parsed = !deserializeJson(json, result->body);
      const int status = result->status; delete result;
      char diagnostic[100]; snprintf(diagnostic, sizeof(diagnostic), "PL_UPLOAD response=%d stage=%u sequence=%lu\n", status, static_cast<unsigned>(stage), reader ? (unsigned long)reader->sequence : 0ul); pl::debug(diagnostic);
      if (status == 401 || status == 403) { close("Device or session permission required."); return; }
      if (status == 409 || status == 400 || status == 410) { close("Upload conflict. Recording retained."); return; }
      if (status < 200 || status >= 300 || !parsed) {
        close("Upload connection failed. Recording will retry."); retryAt = millis() + 10000; return;
      }
      if (stage == Stage::Permission) {
        if (json["sessionId"] != session) { close("Permission response mismatch."); return; }
        reader.reset(new (std::nothrow) pl::UploadJournal(selectedSessionBytes));
        if (!reader) { close("Upload memory unavailable."); retryAt = millis() + 5000; return; }
        loadCheckpoint();
        stage = Stage::Reading;
      } else if (stage == Stage::Batch) {
        if (json["sessionId"] != session || json["sequence"] != reader->sequence || json["persisted"] != true) {
          close("Upload receipt mismatch."); return;
        }
        saveCheckpoint(); reader->nextBatch(); stage = Stage::Reading;
      } else if (stage == Stage::Complete) {
        if (json["sessionId"] == session && json["status"] == "Completed" &&
            json["receivedSampleCount"] == reader->record.captured && json["receivedBatchCount"] == reader->sequence) {
          json["fileBytes"] = inputBytes;
          File marker = SD.open(uploaded(), FILE_WRITE);
          if (marker) { serializeJson(json, marker); marker.flush(); marker.close(); }
          close("Server received recording. Report pending.");
        } else { SD.remove(checkpoint()); close("Server needs missing data. Retry pending."); }
        return;
      }
    }
    const bool apiReady = net.view().api == ApiState::Ready;
    if (pl::shouldReleaseIdleUpload(apiReady, requestId, stage == Stage::Idle)) {
      close("API unavailable. Saved recording retained."); return;
    }
    if (!apiReady || requestId || static_cast<int32_t>(millis() - retryAt) < 0) return;
    if (stage == Stage::Idle) { if (!checkedOnce || millis() - checkAt >= 15000) openNext(); }
    if (stage == Stage::Permission) {
      requestId = net.submit(("/api/device-transport/recordings/" + session + "/progress").c_str(), false);
      if (!requestId) { close("Upload queue busy. Retrying shortly."); retryAt = millis() + 5000; return; }
    } else if (stage == Stage::Reading) {
      if (reader->state == pl::UploadReadState::Reading || reader->sequence < resumeSequence) readBatch();
      if (stage == Stage::Idle) return;
      if (reader->state == pl::UploadReadState::Ready) sendBatch();
      else if (reader->state == pl::UploadReadState::Complete) {
        String body;
        {
          JsonDocument json; json["expectedBatchCount"] = reader->sequence; json["expectedSampleCount"] = reader->record.captured;
          json["recordedDurationSeconds"] = reader->record.nextIndex / 250;
          serializeJson(json, body);
        }
        requestId = net.submitMoved(("/api/device-transport/recordings/" + session + "/complete").c_str(), true, std::move(body));
        if (requestId) stage = Stage::Complete;
        else { close("Completion queue busy. Retrying shortly."); retryAt = millis() + 5000; return; }
      }
    } else if (stage == Stage::Complete && !requestId) stage = Stage::Reading;
    if (requestId) requestAt = millis();
  }
};
