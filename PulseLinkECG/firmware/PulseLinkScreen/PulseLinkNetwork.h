#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <NetworkClientSecure.h>
#include <Preferences.h>
#include <time.h>
#include <new>
#include <memory>
#if __has_include(<PulseLinkCredentials.h>)
#include <PulseLinkCredentials.h>
#else
#include "../../libraries/PulseLinkCore/src/PulseLinkCredentials.h"
#endif
#if __has_include(<PulseLinkNetworkMemory.h>)
#include <PulseLinkNetworkMemory.h>
#else
#include "../../libraries/PulseLinkCore/src/PulseLinkNetworkMemory.h"
#endif
#include "PulseLinkAzureCa.h"

enum class WifiState : uint8_t { NotConfigured, Connecting, Connected, Disconnected };
enum class ApiState : uint8_t { NotConfigured, WaitingForWifi, WaitingForClock, Ready, Unauthorised, Unavailable };
enum class WifiIssue : uint8_t { None, Credentials, Handshake, NotFound, Security, Signal, Timeout, Lost, Scan };
enum class NetAction : uint8_t { Configure, Scan, Request };
enum class NetOwner : uint8_t { Upload, Workflow };
struct StoredNetworkConfig { char ssid[33] = {}, password[65] = {}; char api[192] = "https://pulselinkv2-drhahreccedabcfk.southafricanorth-01.azurewebsites.net"; char deviceId[65] = {}, token[44] = {}; };
struct NetworkConfig {
  char ssid[33] = {}, password[65] = {};
  char api[192] = "https://pulselinkv2-drhahreccedabcfk.southafricanorth-01.azurewebsites.net";
  char deviceId[65] = {}, token[44] = {}; char identity[65] = {};
};
struct NetworkView {
  WifiState wifi = WifiState::NotConfigured;
  ApiState api = ApiState::NotConfigured;
  char ssid[33] = {}, networks[6][33] = {};
  uint8_t networkCount = 0;
  bool scanning = false;
  bool settingsStored = false;
  bool saveFailed = false;
  bool applying = false;
  bool secured[6] = {};
  WifiIssue issue = WifiIssue::None;
  uint8_t pendingEvents = 0;
  int rssi = 0;
  int httpCode = 0, tlsError = 0;
  uint32_t requestCount = 0, requestMs = 0, maxBlock = 0;
  uint8_t requestKind = 0;
};
struct HttpWork {
  uint32_t id;
  bool post;
  bool remove = false;
  char path[384];
  String body;
  NetOwner owner = NetOwner::Upload;
};
struct HttpResult { uint32_t id; int status; String body; };

class BoundedReply : public Stream {
  char *buffer = nullptr; String *text = nullptr; size_t capacity, used = 0;
public:
  bool overflow = false, allocationFailed = false;
  BoundedReply(char *buffer, size_t capacity) : buffer(buffer), capacity(capacity) { buffer[0] = 0; }
  BoundedReply(String &text, size_t capacity) : text(&text), capacity(capacity) { text.clear(); }
  size_t write(uint8_t value) override { return write(&value, 1); }
  size_t write(const uint8_t *data, size_t count) override {
    if (count >= capacity - used) { overflow = true; return 0; }
    if (text) {
      if (!text->concat(reinterpret_cast<const char *>(data), count)) { allocationFailed = true; return 0; }
    } else { memcpy(buffer + used, data, count); buffer[used + count] = 0; }
    used += count; return count;
  }
  int available() override { return 0; }
  int read() override { return -1; }
  int peek() override { return -1; }
  void flush() override { }
};

class PulseLinkNetwork {
  struct Command { NetAction action; NetworkConfig config; HttpWork *request; };
  QueueHandle_t commands = nullptr, results[2] = {};
  portMUX_TYPE lock = portMUX_INITIALIZER_UNLOCKED;
  NetworkView state;
  NetworkConfig config;
  NetworkConfig desired, savedConfig;
  bool hasSavedConfig = false;
  bool running = false;
  pl::NetworkMemoryGate memoryGate;
  std::unique_ptr<NetworkClientSecure> secureClient;
  std::unique_ptr<HTTPClient> httpClient;
  uint32_t nextRequest = 1;
  uint16_t disconnectReason = 0;

  void setWifi(WifiState value) {
    const int rssi = WiFi.RSSI();
    portENTER_CRITICAL(&lock); state.wifi = value;
    strlcpy(state.ssid, config.ssid, sizeof(state.ssid)); state.rssi = rssi;
    portEXIT_CRITICAL(&lock);
  }
  void setApi(ApiState value) { portENTER_CRITICAL(&lock); state.api = value; portEXIT_CRITICAL(&lock); }
  void setIssue(WifiIssue value) { portENTER_CRITICAL(&lock); state.issue = value; portEXIT_CRITICAL(&lock); }
  static WifiIssue issueFor(uint16_t reason) {
    switch (reason) {
      case WIFI_REASON_AUTH_FAIL: case WIFI_REASON_802_1X_AUTH_FAILED: return WifiIssue::Credentials;
      case WIFI_REASON_4WAY_HANDSHAKE_TIMEOUT: case WIFI_REASON_HANDSHAKE_TIMEOUT: return WifiIssue::Handshake;
      case WIFI_REASON_NO_AP_FOUND: return WifiIssue::NotFound;
      case WIFI_REASON_NO_AP_FOUND_W_COMPATIBLE_SECURITY: case WIFI_REASON_NO_AP_FOUND_IN_AUTHMODE_THRESHOLD: return WifiIssue::Security;
      case WIFI_REASON_NO_AP_FOUND_IN_RSSI_THRESHOLD: return WifiIssue::Signal;
      default: return WifiIssue::Lost;
    }
  }
  int request(const char *path, bool post, const char *body, char *reply, size_t capacity, String *textReply = nullptr, bool remove = false, bool background = false, bool upload = false) {
    (void)upload;
    const uint32_t started = millis();
    if (textReply) textReply->clear();
    if (WiFi.status() != WL_CONNECTED) return -1;
    if (time(nullptr) < 1735689600) { setApi(ApiState::WaitingForClock); return -2; }
    if (!config.token[0] || !config.deviceId[0]) return -3;
    portENTER_CRITICAL(&lock); const bool memoryClaimed = memoryGate.begin(background); portEXIT_CRITICAL(&lock);
    if (!memoryClaimed) return -109;
    if (!secureClient) secureClient.reset(new (std::nothrow) NetworkClientSecure());
    if (!httpClient) httpClient.reset(new (std::nothrow) HTTPClient());
    if (!secureClient || !httpClient) {
      portENTER_CRITICAL(&lock); memoryGate.finish(); portEXIT_CRITICAL(&lock); return -8;
    }
    auto &tls = *secureClient; auto &http = *httpClient;
    // Explicit root verification handles Azure's cross-signed chain without the bundle callback.
    if (!tls.connected()) {
      tls.stop();
      if (String(config.api).endsWith(".azurewebsites.net")) tls.setCACert(PulseLinkAzureRoot);
      else tls.useBuiltinCACertBundle();
      tls.setHandshakeTimeout(15);
    }
    http.setConnectTimeout(10000); http.setTimeout(25000); http.setReuse(false);
    auto finish = [&](int result) {
      char ignored[1]; const int error = tls.lastError(ignored, sizeof(ignored));
      // Retain a validated upload connection so batch buffers do not compete with a new TLS handshake.
      http.end(); tls.stop(); httpClient.reset(); secureClient.reset();
      char diagnostic[120]; snprintf(diagnostic, sizeof(diagnostic), "PL_TLS result=%d error=%d free=%lu block=%lu stack=%u\n", result, error,
        (unsigned long)ESP.getFreeHeap(), (unsigned long)ESP.getMaxAllocHeap(), (unsigned)uxTaskGetStackHighWaterMark(nullptr)); pl::debug(diagnostic);
      const uint32_t elapsed = millis() - started, block = ESP.getMaxAllocHeap();
      portENTER_CRITICAL(&lock); state.httpCode = result; state.tlsError = result < 0 && error < 0 ? error : 0;
      state.requestMs = elapsed; state.maxBlock = block; ++state.requestCount; memoryGate.finish();
      state.requestKind = !strcmp(path, "/api/device-transport/health") ? 1 : !strncmp(path, "/api/device-transport/events", 28) ? 2 : 3;
      portEXIT_CRITICAL(&lock); return result;
    };
    http.setFollowRedirects(HTTPC_DISABLE_FOLLOW_REDIRECTS);
    if (!http.begin(tls, String(config.api) + path)) return finish(-4);
    http.addHeader("Authorization", String("Device ") + config.deviceId + ":" + config.token);
    http.addHeader("Content-Type", "application/json");
    const int code = remove ? http.sendRequest("DELETE") : post ? http.POST(reinterpret_cast<uint8_t *>(const_cast<char *>(body)), strlen(body)) : http.GET();
    if (code > 0 && (reply || textReply) && capacity) {
      const int length = http.getSize();
      if (length > static_cast<int>(capacity - 1)) return finish(-105);
      // Event storage grows with the actual body, after the TLS handshake, within a hard limit.
      BoundedReply sink = textReply ? BoundedReply(*textReply, capacity) : BoundedReply(reply, capacity);
      const int received = http.writeToStream(&sink);
      if (sink.allocationFailed) return finish(-8);
      if (received < 0 || sink.overflow) return finish(-105);
    }
    if (code == 401 || code == 403) setApi(ApiState::Unauthorised);
    else if (code < 0 || code >= 500 || code == 404) setApi(ApiState::Unavailable);
    return finish(code);
  }
  void worker() {
    WiFi.persistent(false);
    uint32_t connectAt = 0, healthAt = millis() - 15000, eventsAt = millis() - 15000, retryMs = 1000;
    String eventCursor;
    bool connecting = false, scanning = false, pendingStore = false, clockLogged = false;
    auto storeConfig = [&]() {
      Preferences prefs; bool stored = false;
      if (prefs.begin("pulselink-net", false)) {
        StoredNetworkConfig base{};
        strlcpy(base.ssid, config.ssid, sizeof(base.ssid));
        strlcpy(base.password, config.password, sizeof(base.password));
        strlcpy(base.api, config.api, sizeof(base.api));
        strlcpy(base.deviceId, config.deviceId, sizeof(base.deviceId));
        strlcpy(base.token, config.token, sizeof(base.token));
        stored = prefs.putBytes("config", &base, sizeof(base)) == sizeof(base);
        prefs.putString("eap_id", config.identity);
        prefs.end();
      }
      if (stored) { savedConfig = config; hasSavedConfig = config.ssid[0] != 0; }
      portENTER_CRITICAL(&lock); state.settingsStored = stored; state.saveFailed = !stored; portEXIT_CRITICAL(&lock);
      pendingStore = false;
    };
    auto connect = [&]() {
      WiFi.disconnect(false, false);
      if (!config.ssid[0]) { WiFi.mode(WIFI_OFF); setWifi(WifiState::NotConfigured); return; }
      WiFi.mode(WIFI_STA); WiFi.setAutoReconnect(false);
      const char *sep = strchr(config.password, '|');
      if (pl::isEnterpriseSsid(config.ssid, config.identity) || sep != nullptr) {
        char eapUser[65] = {}, eapPass[65] = {};
        if (config.identity[0]) {
          strlcpy(eapUser, config.identity, sizeof(eapUser));
          strlcpy(eapPass, config.password, sizeof(eapPass));
        } else if (sep != nullptr) {
          const size_t ulen = min<size_t>(static_cast<size_t>(sep - config.password), sizeof(eapUser) - 1);
          memcpy(eapUser, config.password, ulen);
          eapUser[ulen] = 0;
          strlcpy(eapPass, sep + 1, sizeof(eapPass));
        }
        WiFi.begin(config.ssid, WPA2_AUTH_PEAP, eapUser, eapUser, eapPass);
      } else {
        WiFi.begin(config.ssid, config.password);
      }
      setWifi(WifiState::Connecting);
      connecting = true; connectAt = millis();
    };
    connect();
    for (;;) {
      Command cmd{};
      if (xQueueReceive(commands, &cmd, pdMS_TO_TICKS(50)) == pdTRUE) {
        if (cmd.action == NetAction::Configure) {
          config = cmd.config; pendingStore = true;
          portENTER_CRITICAL(&lock); state.settingsStored = false; state.saveFailed = false; state.issue = WifiIssue::None;
          disconnectReason = 0; portEXIT_CRITICAL(&lock);
          retryMs = 1000; healthAt = millis() - 15000; eventsAt = millis() - 15000; eventCursor = ""; connecting = false; connect();
          // A failed password attempt must not overwrite the last working saved network.
          if (!config.ssid[0]) storeConfig();
          portENTER_CRITICAL(&lock); state.applying = false; portEXIT_CRITICAL(&lock);
        } else if (cmd.action == NetAction::Scan && !scanning) {
          if (WiFi.getMode() == WIFI_OFF) WiFi.mode(WIFI_STA);
          scanning = WiFi.scanNetworks(true) == WIFI_SCAN_RUNNING;
          portENTER_CRITICAL(&lock); state.scanning = scanning; portEXIT_CRITICAL(&lock);
        } else if (cmd.action == NetAction::Request && cmd.request) {
          auto result = new (std::nothrow) HttpResult{};
          if (!result) { delete cmd.request; setApi(ApiState::Unavailable); continue; }
          result->id = cmd.request->id;
          const auto owner = static_cast<unsigned>(cmd.request->owner);
          result->status = request(cmd.request->path, cmd.request->post, cmd.request->body.c_str(), nullptr, 6144, &result->body, cmd.request->remove, false, cmd.request->owner == NetOwner::Upload);
          delete cmd.request;
          if (xQueueSend(results[owner], &result, 0) != pdTRUE) delete result;
        }
      }
      if (scanning) {
        const int count = WiFi.scanComplete();
        if (count != WIFI_SCAN_RUNNING) {
          NetworkView found{}; found.networkCount = min(max(count, 0), 6);
          for (int i = 0; i < found.networkCount; ++i) {
            strlcpy(found.networks[i], WiFi.SSID(i).c_str(), 33);
            found.secured[i] = WiFi.encryptionType(i) != WIFI_AUTH_OPEN;
          }
          portENTER_CRITICAL(&lock); state.networkCount = found.networkCount; state.scanning = false;
          memcpy(state.networks, found.networks, sizeof(state.networks));
          memcpy(state.secured, found.secured, sizeof(state.secured));
          if (count < 0) state.issue = WifiIssue::Scan;
          portEXIT_CRITICAL(&lock);
          WiFi.scanDelete(); scanning = false;
        }
      }
      portENTER_CRITICAL(&lock); const uint16_t reason = disconnectReason; disconnectReason = 0; portEXIT_CRITICAL(&lock);
      if (reason) setIssue(issueFor(reason));
      if (WiFi.status() == WL_CONNECTED) {
        if (connecting) {
          connecting = false; retryMs = 1000; clockLogged = false;
          configTime(0, 0, "time.cloudflare.com", "pool.ntp.org");
          healthAt = millis() - 15000; eventsAt = millis() - 15000;
          if (config.token[0] && view().api == ApiState::WaitingForWifi) setApi(ApiState::WaitingForClock);
        }
        const time_t epoch = time(nullptr);
        if (!clockLogged && epoch >= 1735689600) {
          clockLogged = true;
          char ntpLog[64]; snprintf(ntpLog, sizeof(ntpLog), "PL_NTP synced=1 epoch=%lu\n", static_cast<unsigned long>(epoch));
          pl::debug(ntpLog);
        }
        setWifi(WifiState::Connected);
        setIssue(WifiIssue::None);
        if (pendingStore) storeConfig();
        portENTER_CRITICAL(&lock); const bool held = memoryGate.holds(3); portEXIT_CRITICAL(&lock);
        if (!held && secureClient) { httpClient.reset(); secureClient.reset(); }
        const auto apiState = view().api;
        const uint32_t healthInterval = apiState == ApiState::Ready ? 15000u : apiState == ApiState::WaitingForClock ? 2000u : 5000u;
        if (!config.token[0]) setApi(ApiState::NotConfigured);
        else if (!held && millis() - healthAt >= healthInterval) {
          healthAt = millis(); char reply[512] = {};
          const int code = request("/api/device-transport/health", false, "", reply, sizeof(reply), nullptr, false, true);
          JsonDocument json;
          if (code == 200 && !deserializeJson(json, reply) && json["deviceId"] == config.deviceId && json["status"] == "ready")
            setApi(ApiState::Ready);
          else if (code != -109 && code != -2 && code != 401 && code != 403) setApi(ApiState::Unavailable);
        }
        if (!held && view().api == ApiState::Ready && millis() - eventsAt >= 3000) {
          eventsAt = millis(); String reply;
          {
            String path = "/api/device-transport/events";
            if (eventCursor.length()) path += "?after=" + eventCursor;
            const int code = request(path.c_str(), false, "", nullptr, 6144, &reply, false, true);
            JsonDocument json;
            if (code == 200 && !deserializeJson(json, reply)) {
              const uint8_t count = json["items"].as<JsonArray>().size();
              if (count > 0 || json["nextCursor"].isNull()) eventCursor = String();
              else eventCursor = json["nextCursor"].as<String>();
              portENTER_CRITICAL(&lock); state.pendingEvents = count; portEXIT_CRITICAL(&lock);
            }
          }
        }
      } else {
        setApi(config.token[0] ? ApiState::WaitingForWifi : ApiState::NotConfigured);
        if (connecting && millis() - connectAt > 15000) {
          connecting = false; setWifi(WifiState::Disconnected); connectAt = millis();
          if (view().issue == WifiIssue::None) setIssue(WifiIssue::Timeout);
        }
        const auto currentIssue = view().issue;
        const bool authFailed = currentIssue == WifiIssue::Credentials || currentIssue == WifiIssue::Handshake || currentIssue == WifiIssue::Security;
        if (!connecting && pl::shouldFallbackToSavedNetwork(pendingStore, hasSavedConfig && savedConfig.ssid[0] != 0, authFailed)) {
          config = savedConfig; pendingStore = false; retryMs = 1000;
          portENTER_CRITICAL(&lock); state.settingsStored = true; portEXIT_CRITICAL(&lock);
        }
        if (!connecting && config.ssid[0] && millis() - connectAt >= retryMs) { connect(); retryMs = min<uint32_t>(retryMs * 2, 30000); }
      }
    }
  }
public:
  static const char *configError(const NetworkConfig &value) {
    const String url(value.api);
    if (!url.startsWith("https://") || url.length() <= 8 || url.indexOf('/', 8) >= 0 || url.indexOf('@') >= 0 ||
        url.indexOf('?') >= 0 || url.indexOf('#') >= 0 || url.indexOf('\\') >= 0) return "Use an HTTPS address without a path.";
    for (char c : url) if (static_cast<unsigned char>(c) <= 32 || static_cast<unsigned char>(c) > 126) return "Remove spaces from the API address.";
    const char *sep = strchr(value.password, '|');
    if (pl::isEnterpriseSsid(value.ssid, value.identity) || sep != nullptr) {
      char eapUser[65] = {}, eapPass[65] = {};
      if (value.identity[0]) {
        strlcpy(eapUser, value.identity, sizeof(eapUser));
        strlcpy(eapPass, value.password, sizeof(eapPass));
      } else if (sep != nullptr) {
        const size_t ulen = min<size_t>(static_cast<size_t>(sep - value.password), sizeof(eapUser) - 1);
        memcpy(eapUser, value.password, ulen);
        eapUser[ulen] = 0;
        strlcpy(eapPass, sep + 1, sizeof(eapPass));
      }
      if (const char *error = pl::enterprisePasswordError(eapUser, eapPass)) return error;
    } else {
      if (const char *error = pl::personalPasswordError(value.password)) return error;
    }
    if (value.token[0]) for (const char *p = value.token; *p; ++p)
      if (!isalnum(static_cast<unsigned char>(*p)) && *p != '-' && *p != '_') return "Device key contains an invalid character.";
    return value.token[0] && strlen(value.token) != 43 ? "Device key must contain 43 characters." : nullptr;
  }
  static bool validConfig(const NetworkConfig &value) { return configError(value) == nullptr; }
  void begin(const char *deviceId) {
    Preferences prefs;
    bool restored = false;
    if (prefs.begin("pulselink-net", true)) {
      StoredNetworkConfig base{};
      if (prefs.getBytesLength("config") == sizeof(base)) {
        restored = prefs.getBytes("config", &base, sizeof(base)) == sizeof(base);
        if (restored) {
          strlcpy(config.ssid, base.ssid, sizeof(config.ssid)); strlcpy(config.password, base.password, sizeof(config.password));
          strlcpy(config.api, base.api, sizeof(config.api)); strlcpy(config.deviceId, base.deviceId, sizeof(config.deviceId));
          strlcpy(config.token, base.token, sizeof(config.token));
        }
      }
      const String eapId = prefs.getString("eap_id", ""); strlcpy(config.identity, eapId.c_str(), sizeof(config.identity));
      prefs.end();
    }
    config.ssid[32] = config.password[64] = config.api[191] = config.token[43] = config.deviceId[64] = config.identity[64] = 0;
    strlcpy(config.deviceId, deviceId, sizeof(config.deviceId));
    if (!validConfig(config)) { restored = false; config = NetworkConfig{}; strlcpy(config.deviceId, deviceId, sizeof(config.deviceId)); }
    desired = config; savedConfig = config; hasSavedConfig = restored && config.ssid[0] != 0; state.settingsStored = restored;
    WiFi.onEvent([this](arduino_event_id_t, arduino_event_info_t info) {
      const uint16_t reason = info.wifi_sta_disconnected.reason;
      // Ignore the intentional disconnect used when changing networks or retrying.
      if (reason == WIFI_REASON_ASSOC_LEAVE) return;
      portENTER_CRITICAL(&lock); disconnectReason = reason; portEXIT_CRITICAL(&lock);
    }, ARDUINO_EVENT_WIFI_STA_DISCONNECTED);
    commands = xQueueCreate(3, sizeof(Command));
    for (auto &queue : results) queue = xQueueCreate(2, sizeof(HttpResult *));
    if (!commands || !results[0] || !results[1]) abort();
    const BaseType_t created = xTaskCreatePinnedToCore([](void *p) { static_cast<PulseLinkNetwork *>(p)->worker(); },
      "PulseLink network", 8192, this, 1, nullptr, 0);
    if (created != pdPASS) abort(); running = true;
  }
  NetworkView view() { portENTER_CRITICAL(&lock); auto copy = state; portEXIT_CRITICAL(&lock); return copy; }
  NetworkConfig settings() const { return desired; }
  void holdBackground(bool held, NetOwner owner = NetOwner::Workflow) {
    portENTER_CRITICAL(&lock); memoryGate.hold(owner == NetOwner::Upload ? 2 : 1, held); portEXIT_CRITICAL(&lock);
  }
  bool reserveUploadMemory() {
    portENTER_CRITICAL(&lock); const bool ready = memoryGate.tryHold(2); portEXIT_CRITICAL(&lock); return ready;
  }
  bool uploadActive() {
    portENTER_CRITICAL(&lock); const bool held = memoryGate.holds(2); portEXIT_CRITICAL(&lock); return held;
  }
  void clearPendingEvents() {
    portENTER_CRITICAL(&lock); state.pendingEvents = 0; portEXIT_CRITICAL(&lock);
  }
  bool configure(const NetworkConfig &value) {
    if (!validConfig(value)) return false;
    Command cmd{}; cmd.action = NetAction::Configure; cmd.config = value;
    portENTER_CRITICAL(&lock); state.applying = true; portEXIT_CRITICAL(&lock);
    const bool queued = running && xQueueSend(commands, &cmd, 0) == pdTRUE;
    if (queued) desired = value;
    else { portENTER_CRITICAL(&lock); state.applying = false; portEXIT_CRITICAL(&lock); }
    return queued;
  }
  bool scan() { Command cmd{}; cmd.action = NetAction::Scan; return running && xQueueSend(commands, &cmd, 0) == pdTRUE; }
  uint32_t submitMoved(const char *path, bool post, String &&body, NetOwner owner = NetOwner::Upload, bool remove = false) {
    if (!running || strlen(path) >= sizeof(HttpWork::path) || body.length() >= 6144) return 0;
    auto request = new (std::nothrow) HttpWork{};
    if (!request) return 0;
    request->id = nextRequest++; request->post = post; request->owner = owner; request->remove = remove;
    strlcpy(request->path, path, sizeof(request->path));
    request->body = std::move(body);
    Command cmd{}; cmd.action = NetAction::Request; cmd.request = request;
    const uint32_t id = request->id;
    if (xQueueSend(commands, &cmd, 0) != pdTRUE) { delete request; return 0; }
    return id;
  }
  uint32_t submit(const char *path, bool post, const char *body = "", NetOwner owner = NetOwner::Upload, bool remove = false) {
    if (!running || strlen(path) >= sizeof(HttpWork::path) || strlen(body) >= 6144) return 0;
    String payload;
    const size_t len = strlen(body);
    if (len) {
      if (!payload.reserve(len) || !payload.concat(body, len)) return 0;
    }
    return submitMoved(path, post, std::move(payload), owner, remove);
  }
  HttpResult *takeResult(NetOwner owner = NetOwner::Upload) {
    HttpResult *result = nullptr; xQueueReceive(results[static_cast<unsigned>(owner)], &result, 0); return result;
  }
};
