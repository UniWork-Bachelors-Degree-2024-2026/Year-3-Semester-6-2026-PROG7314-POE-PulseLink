#pragma once
#if __has_include(<PulseLinkNursePolicy.h>)
#include <PulseLinkNursePolicy.h>
#else
#include "../../libraries/PulseLinkCore/src/PulseLinkNursePolicy.h"
#endif
#include "PulseLinkNetwork.h"
#include "PulseLinkClinicalStore.h"
#include "PulseLinkTextEntry.h"
#include <mbedtls/sha256.h>

class PulseLinkNurseFlow {
public:
  enum class Page { Welcome, Home, Patients, Register, Doctors, AssignConfirm, Confirm, Guide, Leads, Monitor, Complete, Updates };
  struct Patient {
    char id[40] = {}, name[81] = {}, dob[11] = {}, doctor[129] = {}, doctorName[81] = {};
    uint32_t version = 0;
    bool local = false, requiresDoctorAssignment = false;
  };
  struct Doctor { char id[129] = {}, name[81] = {}; };
private:
  PulseLinkNetwork &net;
  PulseLinkClinicalStore &store;
  pl::NursePermission permission;
  static constexpr unsigned Rows = 2;
  Patient patients[Rows], selected;
  Doctor doctors[Rows];
  unsigned patientCount = 0, doctorCount = 0, doctorSelected = 0;
  Page page = Page::Welcome;
  PulseLinkTextEntry entry;
  enum class Edit { Name, Dob, Search } editField = Edit::Name;
  char nurseName[81] = {}, nurseUid[129] = {}, siteName[81] = {}, siteId[65] = {}, grantId[33] = {};
  char draftName[81] = {}, draftDob[11] = {}, search[41] = {};
  String patientAfter, doctorAfter, nextPatient, nextDoctor;
  char code[7] = {}, activeHex[33] = {};
  uint32_t codeAt = 0, requestId = 0, requestAt = 0, pollAt = 0, syncAt = 0, lastTouch = 0;
  bool checkedServer = false, pendingStart = false, placementChecked = false, saved = false, benchRequested = false;
  bool mustApprove = true, repairingAssignment = false;
  bool storageChecked = false, storageVerified = false;
  enum class Job { None, Code, Grant, Clinic, Register, AssignDoctor, Prepare, SyncPatient, SyncRecording, Logout, Updates, Receipt } job = Job::None;
  String targetMeta;
  uint16_t pendingCursor = 0;
  const char *notice = "Approve this unit from the nurse app.";
  char eventId[65] = {}, eventSession[41] = {};
  char eventAuthor[81] = {}, eventMessage[301] = {}, eventKind[33] = {};
  bool eventReceived = false, hasUnreadMessage = false, returnToMonitor = false, activeSessionSynced = false;

  static uint64_t parseUtc(const char *text) {
    if (!text || strlen(text) < 19) return 0;
    tm value{}; if (!strptime(text, "%Y-%m-%dT%H:%M:%S", &value)) return 0;
    const time_t epoch = mktime(&value); if (epoch < 1735689600) return 0;
    unsigned millisPart = 0;
    if (text[19] == '.') for (unsigned i = 0; i < 3; ++i) {
      const char c = text[20 + i]; millisPart = millisPart * 10 + (c >= '0' && c <= '9' ? c - '0' : 0);
      if (!c) { for (++i; i < 3; ++i) millisPart *= 10; break; }
    }
    return uint64_t(epoch) * 1000 + millisPart;
  }
  static String utc(uint64_t milliseconds) {
    time_t seconds = milliseconds / 1000; tm value{}; gmtime_r(&seconds, &value);
    char text[32], prefix[22]; strftime(prefix, sizeof(prefix), "%Y-%m-%dT%H:%M:%S", &value);
    snprintf(text, sizeof(text), "%s.%03uZ", prefix, unsigned(milliseconds % 1000)); return text;
  }
  static void newHex(char *hex) {
    uint8_t bytes[16]; esp_fill_random(bytes, sizeof(bytes));
    for (unsigned i = 0; i < 16; ++i) snprintf(hex + i * 2, 3, "%02x", bytes[i]);
  }
  static String uuid(const char *hex) {
    String value; for (unsigned i = 0; i < 32; ++i) { if (i == 8 || i == 12 || i == 16 || i == 20) value += '-'; value += hex[i]; } return value;
  }
  static String plainUuid(String value) { value.replace("-", ""); return value; }
  static bool validDob(const char *text) { return pl::validPatientBirthDate(text); }
  String cachePath() const {
    const String identity = String(grantId) + ":" + patientAfter + ":" + doctorAfter;
    uint8_t digest[32];
    if (mbedtls_sha256(reinterpret_cast<const uint8_t *>(identity.c_str()), identity.length(), digest, 0)) return "";
    char hex[65];
    for (unsigned i = 0; i < 32; ++i) snprintf(hex + i * 2, 3, "%02x", digest[i]);
    return String("/pulselink/private/c-") + hex;
  }
  static void patientFrom(JsonVariantConst value, Patient &patient) {
    patient = Patient{};
    strlcpy(patient.id, value["id"] | "", sizeof(patient.id)); strlcpy(patient.name, value["fullName"] | "", sizeof(patient.name));
    strlcpy(patient.dob, value["dateOfBirth"] | "", sizeof(patient.dob)); strlcpy(patient.doctor, value["primaryDoctorUid"] | "", sizeof(patient.doctor));
    strlcpy(patient.doctorName, value["doctorName"] | "", sizeof(patient.doctorName));
    patient.version = value["version"] | 0u; patient.local = value["local"] | false;
    patient.requiresDoctorAssignment = value["requiresDoctorAssignment"] | !patient.doctor[0];
  }
  void readClinic(JsonDocument &json) {
    if (json["grant"]["id"] != grantId || json["grant"]["siteId"] != siteId) return;
    strlcpy(siteName, json["siteName"] | "", sizeof(siteName));
    patientCount = doctorCount = 0;
    for (JsonVariantConst item : json["patients"]["items"].as<JsonArrayConst>()) {
      if (patientCount < Rows) patientFrom(item, patients[patientCount++]);
    }
    for (JsonVariantConst item : json["doctors"]["items"].as<JsonArrayConst>()) if (doctorCount < Rows) {
      strlcpy(doctors[doctorCount].id, item["uid"] | "", sizeof(doctors[doctorCount].id));
      strlcpy(doctors[doctorCount++].name, item["name"] | "", sizeof(doctors[0].name));
    }
    nextPatient = json["patients"]["nextCursor"].isNull() ? "" : json["patients"]["nextCursor"].as<String>();
    nextDoctor = json["doctors"]["nextCursor"].isNull() ? "" : json["doctors"]["nextCursor"].as<String>();
  }
  bool send(Job next, const String &path, bool post, const String &body = "", bool remove = false) {
    if (requestId) return false;
    net.holdBackground(true);
    requestId = net.submit(path.c_str(), post, body.c_str(), NetOwner::Workflow, remove);
    if (!requestId) { net.holdBackground(false); notice = "Network queue is busy. Try again."; return false; }
    requestAt = millis(); job = next; return true;
  }
  bool jsonRequest(Job next, const char *path, JsonDocument &json) {
    String body; serializeJson(json, body); return send(next, path, true, body);
  }
  void lock() {
    permission.clear(); pendingStart = false; repairingAssignment = false; patientCount = doctorCount = 0; selected = Patient{};
    entry.clear(); draftName[0] = draftDob[0] = search[0] = siteId[0] = eventId[0] = eventSession[0] = eventAuthor[0] = eventMessage[0] = eventKind[0] = 0;
    eventReceived = hasUnreadMessage = returnToMonitor = activeSessionSynced = false;
    memset(patients, 0, sizeof(patients)); memset(doctors, 0, sizeof(doctors));
    nurseName[0] = nurseUid[0] = siteName[0] = grantId[0] = 0; code[0] = 0; page = Page::Welcome;
  }
  void persistLock(bool locked) {
    mustApprove = locked;
    Preferences prefs;
    if (!prefs.begin("pl-nurse", false)) { mustApprove = true; return; }
    if (prefs.putBool("locked", locked) != 1) mustApprove = true;
    prefs.end();
  }
  void cachePatients() {
    JsonDocument cache; if (!store.load(cachePath(), cache)) return;
    auto rows = cache["patients"]["items"].to<JsonArray>(); rows.clear();
    for (unsigned i = 0; i < patientCount; ++i) {
      const auto &p = patients[i]; auto row = rows.add<JsonObject>();
      row["id"] = p.id; row["fullName"] = p.name; row["dateOfBirth"] = p.dob; row["primaryDoctorUid"] = p.doctor;
      row["requiresDoctorAssignment"] = p.requiresDoctorAssignment; row["doctorName"] = p.doctorName; row["version"] = p.version; row["local"] = p.local;
    }
    if (!store.save(cachePath(), cache)) notice = "Patient retained. Cached list update pending.";
  }
  void clinic() {
    if (!canWork()) return;
    patientCount = doctorCount = 0;
    JsonDocument cached;
    if (store.load(cachePath(), cached)) readClinic(cached);
    if (net.view().api != ApiState::Ready) { notice = patientCount ? "Offline. Using this cached patient page." : "This page is not cached. Connect to load it."; return; }
    String path = String("/api/device-workflow/clinic?grantId=") + grantId;
    if (patientAfter.length()) path += "&afterPatient=" + patientAfter;
    if (doctorAfter.length()) path += "&afterDoctor=" + doctorAfter;
    send(Job::Clinic, path, false);
  }
  bool saveLocalPatient() {
    if (!canWork() || strlen(draftName) < 2 || !validDob(draftDob) || doctorSelected >= doctorCount) { notice = "Check name, date of birth and doctor."; return false; }
    if (draftDob[0] && String(draftDob) > utc(permission.current(millis())).substring(0, 10)) { notice = "Date of birth cannot be in the future."; return false; }
    char hex[33]; newHex(hex); JsonDocument doc;
    doc["grantId"] = grantId; doc["registeredAtUtc"] = utc(permission.current(millis()));
    auto patient = doc["patient"].to<JsonObject>(); patient["registrationId"] = uuid(hex);
    patient["fullName"] = draftName;
    if (draftDob[0]) patient["dateOfBirth"] = draftDob; else patient["dateOfBirth"] = nullptr;
    patient["siteId"] = siteId; patient["primaryDoctorUid"] = doctors[doctorSelected].id;
    const String base = String("/pulselink/private/p-") + hex;
    if (!store.save(base, doc)) { notice = "Could not save private patient record."; return false; }
    selected = Patient{}; snprintf(selected.id, sizeof(selected.id), "PAT-%s", hex);
    strlcpy(selected.name, draftName, sizeof(selected.name)); strlcpy(selected.dob, draftDob, sizeof(selected.dob));
    strlcpy(selected.doctor, doctors[doctorSelected].id, sizeof(selected.doctor)); strlcpy(selected.doctorName, doctors[doctorSelected].name, sizeof(selected.doctorName));
    selected.version = 1; selected.local = true; page = Page::Confirm;
    for (unsigned i = min(patientCount, Rows - 1); i > 0; --i) patients[i] = patients[i - 1];
    patients[0] = selected; patientCount = min(patientCount + 1, Rows); cachePatients();
    notice = "Registered locally. Synchronisation pending.";
    if (net.view().api == ApiState::Ready) { targetMeta = base; jsonRequest(Job::Register, "/api/device-workflow/patients", doc); }
    return true;
  }
  void assignDoctor() {
    if (net.view().api != ApiState::Ready) { notice = "Reconnect to assign this patient's doctor."; return; }
    if (!canWork() || !repairingAssignment || selected.local || doctorSelected >= doctorCount) { notice = "Refresh this patient and select a doctor."; return; }
    JsonDocument doc; doc["grantId"] = grantId; doc["primaryDoctorUid"] = doctors[doctorSelected].id;
    doc["expectedVersion"] = selected.version;
    String body; serializeJson(doc, body);
    if (send(Job::AssignDoctor, String("/api/device-workflow/patients/") + selected.id + "/doctor", true, body))
      notice = "Saving doctor assignment...";
  }
  void prepare() {
    if (!canWork(90000)) { notice = "Approve a shift with at least 90s remaining."; return; }
    if (!selected.id[0] || !selected.version) { notice = "Select a registered patient first."; return; }
    if (selected.requiresDoctorAssignment || !selected.doctor[0]) { notice = "Assign an approved doctor before recording."; return; }
    newHex(activeHex); JsonDocument doc;
    doc["grantId"] = grantId; doc["recordingId"] = uuid(activeHex); doc["patientId"] = selected.id; doc["patientName"] = selected.name; doc["operatorUid"] = nurseUid; doc["siteId"] = siteId;
    doc["primaryDoctorUid"] = selected.doctor; doc["patientVersion"] = selected.version;
    doc["startedAtUtc"] = utc(permission.current(millis()));
    const String base = String("/pulselink/private/r-") + activeHex;
    if (!store.save(base, doc)) { notice = "Private recording metadata could not be saved."; return; }
    saved = false; activeSessionSynced = false;
    if (net.view().api == ApiState::Ready && !selected.local) {
      targetMeta = base; jsonRequest(Job::Prepare, "/api/device-workflow/recordings", doc);
      notice = "Preparing live capture session...";
    } else { pendingStart = true; page = Page::Monitor; notice = "Offline recording. Upload pending."; }
  }
  void synchronise() {
    if (net.view().api != ApiState::Ready || millis() - syncAt < 15000 || millis() - lastTouch < 5000) return;
    syncAt = millis();
    File pendingDirectory = SD.open("/pulselink/private");
    if (!pendingDirectory) return;
    for (uint16_t skip = 0; skip < pendingCursor; ++skip) {
      File skipped = pendingDirectory.openNextFile();
      if (!skipped) {
        pendingDirectory.close();
        pendingCursor = 0;
        pendingDirectory = SD.open("/pulselink/private");
        if (!pendingDirectory) return;
        break;
      }
      skipped.close();
    }
    for (unsigned scanned = 0; scanned < 12; ++scanned) {
      File file = pendingDirectory.openNextFile();
      if (!file) { pendingCursor = 0; pendingDirectory.close(); return; }
      ++pendingCursor;
      String name = file.name(); const bool candidate = !file.isDirectory() && name.endsWith(".0"); file.close();
      name = name.substring(name.lastIndexOf('/') + 1);
      if (!candidate || !(name.startsWith("p-") || name.startsWith("r-"))) continue;
      const String base = String("/pulselink/private/") + name.substring(0, name.length() - 2);
      JsonDocument doc; if (!store.load(base, doc) || doc["synchronised"] == true || doc["review"] == true) continue;
      if (name.startsWith("r-")) {
        const String hex = plainUuid(doc["recordingId"].as<String>());
        if (!SD.exists(String("/pulselink/") + hex + ".ready")) continue;
        // A recording cannot be bound until its locally created patient exists on the server.
        const String patientId = doc["patientId"] | "";
        if (patientId.startsWith("PAT-")) {
          const String patientBase = String("/pulselink/private/p-") + patientId.substring(4);
          if (SD.exists(patientBase + ".0") || SD.exists(patientBase + ".1")) {
            JsonDocument patient;
            if (!store.load(patientBase, patient) || patient["synchronised"] != true) continue;
          }
        }
      }
      pendingDirectory.close();
      targetMeta = base;
      jsonRequest(name.startsWith("p-") ? Job::SyncPatient : Job::SyncRecording,
        name.startsWith("p-") ? "/api/device-workflow/patients" : "/api/device-workflow/recordings", doc);
      return;
    }
    pendingDirectory.close();
  }
  void result(HttpResult &reply) {
    const Job completed = job; job = Job::None; requestId = 0;
    JsonDocument json; const bool parsed = !deserializeJson(json, reply.body);
    if (reply.status < 200 || reply.status >= 300 || (reply.status != 204 && !parsed)) {
      notice = reply.status == 404 ? "Publish the updated nurse-workflow API." : reply.status == 403 || reply.status == 401 ?
        "Nurse permission rejected. Approve again." : reply.status == 409 ? "Patient details changed. Clinic review needed." : "Connection failed. Saved work stays local.";
      if ((completed == Job::SyncPatient || completed == Job::SyncRecording || completed == Job::Register || completed == Job::Prepare) &&
          (reply.status == 400 || reply.status == 403 || reply.status == 409 || reply.status == 410)) {
        JsonDocument retained; if (store.load(targetMeta, retained)) { retained["review"] = true; retained["lastError"] = reply.status; store.save(targetMeta, retained); }
      }
      if (completed == Job::Prepare && reply.status < 0 && canWork(90000)) { pendingStart = true; page = Page::Monitor; }
      if ((reply.status == 401 || reply.status == 403) && completed != Job::SyncPatient && completed != Job::SyncRecording) lock();
      pollAt = millis(); return;
    }
    if (completed == Job::Code) {
      strlcpy(code, json["code"] | "", sizeof(code)); codeAt = millis(); pollAt = millis();
      notice = "Approve this code in the nurse Android app.";
    } else if (completed == Job::Grant) {
      checkedServer = true;
      if (json["grant"].isNull()) { if (grantId[0]) lock(); return; }
      const auto grant = json["grant"];
      const bool sameShift = grant["id"] == grantId;
      if (grant["deviceId"] != net.settings().deviceId || !permission.confirm(parseUtc(json["serverTimeUtc"] | ""), parseUtc(grant["expiresAtUtc"] | ""), millis())) {
        lock(); notice = "Server time or nurse permission is invalid."; return;
      }
      strlcpy(grantId, grant["id"] | "", sizeof(grantId)); strlcpy(siteId, grant["siteId"] | "", sizeof(siteId));
      strlcpy(nurseName, grant["nurseName"] | "", sizeof(nurseName)); strlcpy(nurseUid, grant["nurseUid"] | "", sizeof(nurseUid)); code[0] = 0;
      persistLock(false);
      if (!store.begin()) { lock(); notice = "Private card storage unavailable."; return; }
      pollAt = millis();
      if (!sameShift) {
        patientAfter = doctorAfter = ""; page = Page::Home; notice = "Nurse approved. Load patients to begin.";
      }
    } else if (completed == Job::Clinic) {
      readClinic(json);
      if (!store.save(cachePath(), json)) notice = "Loaded online. Offline cache could not save.";
      else notice = patientCount ? "Choose the patient being examined." : "No eligible patients on this page.";
    } else if (completed == Job::Register || completed == Job::SyncPatient) {
      JsonDocument metadata;
      if (!store.load(targetMeta, metadata) || json["id"] != String("PAT-") + plainUuid(metadata["patient"]["registrationId"].as<String>())) { notice = "Patient receipt mismatch. Kept for review."; return; }
      metadata["synchronised"] = true;
      if (!store.save(targetMeta, metadata)) notice = "Patient saved on server. Local receipt pending.";
      else {
        const uint32_t version = json["version"] | 1u;
        if (json["id"] == selected.id) { selected.local = false; selected.version = version; }
        for (unsigned i = 0; i < patientCount; ++i) if (json["id"] == patients[i].id) {
          patients[i].local = false; patients[i].version = version;
        }
        cachePatients(); notice = "Patient registration synchronised.";
      }
    } else if (completed == Job::AssignDoctor) {
      if (json["id"] != selected.id || doctorSelected >= doctorCount || json["primaryDoctorUid"] != doctors[doctorSelected].id ||
          (json["version"] | 0u) < selected.version || json["requiresDoctorAssignment"] == true) {
        notice = "Assignment receipt invalid. Refresh the patient."; return;
      }
      patientFrom(json, selected); selected.requiresDoctorAssignment = false;
      for (unsigned i = 0; i < patientCount; ++i) if (!strcmp(patients[i].id, selected.id)) patients[i] = selected;
      cachePatients(); repairingAssignment = false; page = Page::Confirm; notice = "Doctor assigned. Confirm patient details.";
    } else if (completed == Job::Prepare) {
      JsonDocument metadata;
      if (store.load(targetMeta, metadata)) { metadata["synchronised"] = true; store.save(targetMeta, metadata); activeSessionSynced = true; }
      pendingStart = true; page = Page::Monitor; notice = "Recording live to touchscreen, app & SD.";
    } else if (completed == Job::SyncRecording) {
      JsonDocument metadata;
      if (!store.load(targetMeta, metadata) || json["sessionId"] != String("SESSION-") + plainUuid(metadata["recordingId"].as<String>())) { notice = "Recording receipt mismatch. Kept locally."; return; }
      metadata["synchronised"] = true;
      if (!store.save(targetMeta, metadata)) { notice = "Could not retain the session receipt."; return; }
    } else if (completed == Job::Logout) { lock(); notice = "Nurse signed out. Recording files retained."; }
    else if (completed == Job::Updates) {
      eventId[0] = eventSession[0] = eventAuthor[0] = eventMessage[0] = eventKind[0] = 0;
      eventReceived = hasUnreadMessage = false;
      for (JsonVariantConst item : json["items"].as<JsonArrayConst>()) {
        const char *k = item["kind"] | "";
        if (strcmp(k, "doctor-coordination") == 0) {
          strlcpy(eventId, item["id"] | "", sizeof(eventId));
          strlcpy(eventSession, item["sessionId"] | "", sizeof(eventSession));
          strlcpy(eventKind, k, sizeof(eventKind));
          strlcpy(eventAuthor, item["author"] | "Attending Doctor", sizeof(eventAuthor));
          strlcpy(eventMessage, item["message"] | "", sizeof(eventMessage));
          hasUnreadMessage = true;
          break;
        } else if (strcmp(k, "upload-permitted") == 0 && !eventId[0]) {
          strlcpy(eventId, item["id"] | "", sizeof(eventId));
          strlcpy(eventSession, item["sessionId"] | "", sizeof(eventSession));
          strlcpy(eventKind, k, sizeof(eventKind));
        }
      }
      notice = hasUnreadMessage ? "New doctor coordination message." : eventId[0] ? "Upload permission received for this session." : "No current session updates.";
      if (!hasUnreadMessage && strcmp(eventKind, "upload-permitted") == 0 &&
          strncmp(eventSession, "SESSION-", 8) == 0 && strlen(eventSession) == 40) {
        const String hex = String(eventSession + 8);
        const String base = String("/pulselink/private/r-") + hex;
        if (!SD.exists(base + ".0") && !SD.exists(String("/pulselink/") + hex + ".ready") && canWork(90000)) {
          strlcpy(activeHex, hex.c_str(), sizeof(activeHex));
          JsonDocument doc;
          doc["grantId"] = grantId; doc["recordingId"] = uuid(activeHex);
          doc["patientId"] = selected.id[0] ? selected.id : "REMOTE"; doc["patientName"] = selected.name;
          doc["operatorUid"] = nurseUid; doc["siteId"] = siteId;
          doc["primaryDoctorUid"] = selected.doctor; doc["patientVersion"] = selected.version;
          doc["startedAtUtc"] = utc(permission.current(millis()));
          doc["synchronised"] = true;
          if (store.save(base, doc)) {
            activeSessionSynced = true; pendingStart = true; page = Page::Monitor;
            notice = "Live capture started from Android app.";
          }
        }
        send(Job::Receipt, String("/api/device-transport/events/") + eventId + "/received", true, "{}");
      }
    } else if (completed == Job::Receipt) {
      eventReceived = true; hasUnreadMessage = false;
      notice = "Doctor coordination message acknowledged.";
    }
  }
public:
  PulseLinkNurseFlow(PulseLinkNetwork &network, PulseLinkClinicalStore &storage) : net(network), store(storage) { }
  void begin() {
    setenv("TZ", "UTC0", 1); tzset(); store.begin();
    Preferences prefs;
    if (prefs.begin("pl-nurse", true)) { mustApprove = prefs.getBool("locked", true); prefs.end(); }
  }
  bool canWork(uint32_t remaining = 0) { return storageVerified && grantId[0] && permission.allows(millis(), remaining); }
  bool isActiveSessionSynchronised() const { return activeSessionSynced; }
  bool busy() const { return requestId != 0; }
  bool monitor() const { return page == Page::Monitor; }
  Page currentPage() const { return page; }
  bool editing() const { return entry.active(); }
  uint8_t entryKey() const { return entry.viewKey(); }
  const char *clinicSiteName() const { return siteName; }
  const char *activeNurseName() const { return nurseName; }
  void home() { returnToMonitor = false; page = canWork() ? Page::Home : Page::Welcome; }
  bool unreadDoctorMessage() const { return hasUnreadMessage && !eventReceived; }
  const char *messageAuthor() const { return eventAuthor; }
  const char *messageText() const { return eventMessage; }
  void openMessages(bool fromMonitor = false) {
    returnToMonitor = fromMonitor;
    page = Page::Updates;
    if (!busy() && net.view().api == ApiState::Ready) {
      net.clearPendingEvents();
      send(Job::Updates, "/api/device-transport/events", false);
    }
  }
  bool wantsBench() { const bool value = benchRequested; benchRequested = false; return value; }
  bool start(uint8_t *session) {
    if (!pendingStart) return false; pendingStart = false;
    if (!canWork(90000)) { notice = "Shift ending. Approve again before recording."; page = Page::Welcome; return false; }
    // Capture command time is saved after preflight, so network latency is not added to the session.
    const String base = String("/pulselink/private/r-") + activeHex;
    JsonDocument metadata;
    if (!store.load(base, metadata)) { notice = "Recording metadata unavailable. Not started."; page = Page::Complete; return false; }
    metadata["startedAtUtc"] = utc(permission.current(millis()));
    if (!store.save(base, metadata)) { notice = "Recording time could not be saved. Not started."; page = Page::Complete; return false; }
    for (unsigned i = 0; i < 16; ++i) { char pair[3] = {activeHex[i * 2], activeHex[i * 2 + 1], 0}; session[i] = strtoul(pair, nullptr, 16); }
    return true;
  }
  void started(bool ok) { if (!ok) { page = Page::Complete; saved = false; notice = "Capture did not start. Check card and C3."; } }
  void finished(bool complete) { saved = complete; returnToMonitor = false; page = Page::Complete; notice = complete ? "Saved on microSD. Server upload pending." : "Recording interrupted. Retained for review."; }
  const char *patientName() const { return selected.name; }
  const char *patientId() const { return selected.id; }
  const char *activeGrantId() const { return grantId; }
  const char *activeNurseUid() const { return nurseUid; }
  const char *activeSiteId() const { return siteId; }
  void poll(bool recording, bool cardReady) {
    if (auto reply = net.takeResult(NetOwner::Workflow)) {
      if (reply->id == requestId) { result(*reply); }
      delete reply; if (!requestId) net.holdBackground(false);
    }
    if (requestId && millis() - requestAt > 45000) {
      const bool preparing = job == Job::Prepare; requestId = 0; job = Job::None; net.holdBackground(false);
      notice = "Request timed out. Saved work stays local."; pollAt = millis();
      if (preparing && canWork(90000)) { pendingStart = true; page = Page::Monitor; }
    }
    if (recording || requestId || !cardReady || net.uploadActive()) return;
    if (code[0] && millis() - codeAt >= 300000) { code[0] = 0; checkedServer = true; notice = "Code expired. Request a new code."; }
    if (!storageChecked && net.view().api == ApiState::Ready) {
      net.holdBackground(true); storageVerified = store.selfTest(); storageChecked = true; net.holdBackground(false);
      pl::debug(storageVerified ? "PL_PRIVATE self_test=1\n" : "PL_PRIVATE self_test=0\n");
      if (!storageVerified) { lock(); notice = "Private card storage failed its safety check."; return; }
    }
    if (grantId[0] && !canWork()) { lock(); notice = "Nurse shift expired. Connect and approve again."; }
    if (canWork() && net.view().api == ApiState::Ready && net.view().pendingEvents > 0 && !hasUnreadMessage) {
      net.clearPendingEvents();
      send(Job::Updates, "/api/device-transport/events", false);
      return;
    }
    if (page == Page::Welcome && net.view().api == ApiState::Ready && millis() - pollAt >= 5000 && ((!mustApprove && !checkedServer) || code[0])) {
      pollAt = millis(); send(Job::Grant, "/api/device-workflow/authorisation", false);
    } else if (canWork() && net.view().api == ApiState::Ready && millis() - pollAt >= 60000) {
      pollAt = millis(); send(Job::Grant, "/api/device-workflow/authorisation", false);
    } else if (canWork() && net.view().api == ApiState::Ready && net.view().pendingEvents > 0 && !hasUnreadMessage) {
      net.clearPendingEvents();
      send(Job::Updates, "/api/device-transport/events", false);
    } else synchronise();
  }
  bool tap(int x, int y, bool recording, bool c3Fresh, uint8_t leadFlags, bool cardReady);
  void draw(PulseLinkDisplay &display, bool c3Fresh, uint8_t leadFlags, bool cardReady, const char *uploadStatus, bool fullRedraw = true);
};
