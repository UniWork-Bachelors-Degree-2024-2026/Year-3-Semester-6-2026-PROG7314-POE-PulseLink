#pragma once
#include <SD.h>
#include <Preferences.h>
#include <WiFi.h>
#include <ArduinoJson.h>
#include <esp_random.h>
#include <mbedtls/gcm.h>
#include <memory>

// Authenticated metadata is separate from the raw, pseudonymous ECG journal.
class PulseLinkClinicalStore {
  struct __attribute__((packed)) Header { char magic[4]; uint8_t version; uint8_t nonce[12], tag[16]; uint32_t bytes; };
  uint8_t key[32] = {};
  bool haveKey = false;
  static uint64_t sequence(const Header &h) {
    uint64_t value = 0; for (unsigned i = 0; i < 8; ++i) value |= uint64_t(h.nonce[4 + i]) << (i * 8); return value;
  }
  bool header(const String &path, Header &h) {
    File f = SD.open(path, FILE_READ); if (!f) return false;
    const bool ok = f.read(reinterpret_cast<uint8_t *>(&h), sizeof(h)) == sizeof(h) && !memcmp(h.magic, "PLCP", 4) &&
      h.version == 1 && h.bytes && h.bytes <= 6144 && f.size() == sizeof(h) + h.bytes;
    f.close(); return ok;
  }
  bool decode(const String &base, unsigned slot, JsonDocument &doc) {
    const String path = base + "." + slot; Header h{};
    if (!haveKey || !header(path, h)) return false;
    std::unique_ptr<uint8_t[]> cipher(new (std::nothrow) uint8_t[h.bytes]);
    std::unique_ptr<uint8_t[]> plain(new (std::nothrow) uint8_t[h.bytes + 1]);
    if (!cipher || !plain) return false;
    File f = SD.open(path, FILE_READ); if (!f) return false; f.seek(sizeof(h));
    const bool read = f.read(cipher.get(), h.bytes) == h.bytes; f.close(); if (!read) return false;
    mbedtls_gcm_context ctx; mbedtls_gcm_init(&ctx);
    int result = mbedtls_gcm_setkey(&ctx, MBEDTLS_CIPHER_ID_AES, key, 256);
    if (!result) result = mbedtls_gcm_auth_decrypt(&ctx, h.bytes, h.nonce, sizeof(h.nonce),
      reinterpret_cast<const uint8_t *>(base.c_str()), base.length(), h.tag, sizeof(h.tag), cipher.get(), plain.get());
    mbedtls_gcm_free(&ctx);
    if (result) return false;
    plain[h.bytes] = 0;
    doc.clear(); const bool valid = !deserializeJson(doc, reinterpret_cast<const char *>(plain.get()), static_cast<size_t>(h.bytes));
    memset(plain.get(), 0, h.bytes); return valid;
  }
public:
  bool begin() {
    Preferences prefs; if (!prefs.begin("pl-clinic", false)) return false;
    if (prefs.getBytesLength("key") == sizeof(key)) haveKey = prefs.getBytes("key", key, sizeof(key)) == sizeof(key);
    else if (WiFi.status() == WL_CONNECTED) {
      esp_fill_random(key, sizeof(key)); haveKey = prefs.putBytes("key", key, sizeof(key)) == sizeof(key);
    }
    prefs.end();
    return haveKey && (SD.exists("/pulselink/private") || SD.mkdir("/pulselink/private"));
  }
  bool load(const String &base, JsonDocument &doc, int *selected = nullptr) {
    if (!base.startsWith("/pulselink/private/") || base.length() > 240) { doc.clear(); return false; }
    Header a{}, b{}; const bool first = header(base + ".0", a), second = header(base + ".1", b);
    const unsigned newest = second && (!first || sequence(b) > sequence(a)) ? 1 : 0;
    if (decode(base, newest, doc)) { if (selected) *selected = newest; return true; }
    if (decode(base, 1 - newest, doc)) { if (selected) *selected = 1 - newest; return true; }
    doc.clear(); return false;
  }
  bool save(const String &base, const JsonDocument &doc) {
    if (!base.startsWith("/pulselink/private/") || base.length() > 240) return false;
    if (!haveKey && !begin()) return false;
    if (doc.overflowed()) return false;
    const size_t bytes = measureJson(doc); if (!bytes || bytes > 6144) return false;
    int previous = -1;
    { JsonDocument old; load(base, old, &previous); }
    Header h{}; memcpy(h.magic, "PLCP", 4); h.version = 1; h.bytes = bytes;
    Preferences prefs; if (!prefs.begin("pl-clinic", false)) return false;
    const uint64_t counter = prefs.getULong64("nonce", 0);
    const bool reserved = counter != UINT64_MAX && prefs.putULong64("nonce", counter + 1) == sizeof(uint64_t); prefs.end();
    if (!reserved) return false;
    for (unsigned i = 0; i < 8; ++i) h.nonce[4 + i] = (counter + 1) >> (i * 8);
    std::unique_ptr<uint8_t[]> plain(new (std::nothrow) uint8_t[bytes + 1]), cipher(new (std::nothrow) uint8_t[bytes]);
    if (!plain || !cipher || serializeJson(doc, plain.get(), bytes + 1) != bytes) return false;
    mbedtls_gcm_context ctx; mbedtls_gcm_init(&ctx);
    int result = mbedtls_gcm_setkey(&ctx, MBEDTLS_CIPHER_ID_AES, key, 256);
    if (!result) result = mbedtls_gcm_crypt_and_tag(&ctx, MBEDTLS_GCM_ENCRYPT, bytes, h.nonce, sizeof(h.nonce),
      reinterpret_cast<const uint8_t *>(base.c_str()), base.length(), plain.get(), cipher.get(), sizeof(h.tag), h.tag);
    mbedtls_gcm_free(&ctx); memset(plain.get(), 0, bytes);
    if (result) return false;
    const unsigned slot = previous == 0 ? 1 : 0; const String path = base + "." + slot;
    File f = SD.open(path, FILE_WRITE); if (!f) return false;
    const bool written = f.write(reinterpret_cast<const uint8_t *>(&h), sizeof(h)) == sizeof(h) && f.write(cipher.get(), bytes) == bytes;
    f.flush(); f.close(); if (!written) return false;
    JsonDocument check; return decode(base, slot, check);
  }
  void remove(const String &base) { SD.remove(base + ".0"); SD.remove(base + ".1"); }
  bool selfTest() {
    if (!begin()) return false;
    const String base = "/pulselink/private/.probe"; remove(base);
    JsonDocument original; original["probe"] = "PulseLink";
    bool ok = save(base, original);
    { JsonDocument restored; ok = ok && load(base, restored) && restored["probe"] == "PulseLink"; }
    if (ok) {
      File f = SD.open(base + ".0", "r+");
      if (!f || !f.seek(sizeof(Header))) ok = false;
      else {
        const int byte = f.read();
        if (byte < 0 || !f.seek(sizeof(Header)) || f.write(static_cast<uint8_t>(byte ^ 1)) != 1) ok = false;
        f.flush();
      }
      if (f) f.close();
      JsonDocument tampered; ok = ok && !load(base, tampered);
    }
    remove(base); return ok;
  }
};
