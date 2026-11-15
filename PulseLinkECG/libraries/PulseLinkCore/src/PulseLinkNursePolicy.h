#pragma once
#include <stdint.h>
#include <string.h>
namespace pl {
inline bool validPatientBirthDate(const char *text) {
  if (!text || !text[0]) return true;
  if (strlen(text) != 10 || text[4] != '-' || text[7] != '-') return false;
  for (unsigned i = 0; i < 10; ++i)
    if (i != 4 && i != 7 && (text[i] < '0' || text[i] > '9')) return false;
  const unsigned year = (text[0]-'0')*1000 + (text[1]-'0')*100 + (text[2]-'0')*10 + text[3]-'0';
  const unsigned month = (text[5]-'0')*10 + text[6]-'0';
  const unsigned day = (text[8]-'0')*10 + text[9]-'0';
  if (year < 1900 || year > 2100 || month < 1 || month > 12 || !day) return false;
  const unsigned days[] = {31, unsigned(year%4 == 0 && (year%100 != 0 || year%400 == 0) ? 29 : 28),31,30,31,30,31,31,30,31,30,31};
  return day <= days[month-1];
}

// No cached permission is active after reboot until server time and nurse access are checked.
class NursePermission {
  bool trusted = false;
  uint64_t epochMs = 0, expiresMs = 0;
  uint32_t at = 0;
public:
  void clear() { trusted = false; }
  bool confirm(uint64_t serverMs, uint64_t expiryMs, uint32_t now) {
    trusted = serverMs >= 1735689600000ull && expiryMs > serverMs && expiryMs - serverMs <= 8ull * 3600000;
    epochMs = serverMs; expiresMs = expiryMs; at = now; return trusted;
  }
  uint64_t current(uint32_t now) const { return epochMs + uint32_t(now - at); }
  bool allows(uint32_t now, uint32_t requiredMs = 0) const {
    return trusted && uint32_t(now - at) <= 8ul * 3600000 && current(now) + requiredMs < expiresMs;
  }
};
}
