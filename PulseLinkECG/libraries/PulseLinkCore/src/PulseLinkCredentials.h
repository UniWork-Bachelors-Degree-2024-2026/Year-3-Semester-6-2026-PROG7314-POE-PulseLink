#pragma once
#include <string.h>
#include <ctype.h>

namespace pl {
inline const char *personalPasswordError(const char *password) {
  const size_t length = strlen(password);
  if (!length) return nullptr; // Open networks have no password.
  if (length < 8) return "Password needs at least 8 characters.";
  if (length > 64) return "Password is too long. Maximum: 64.";
  if (length == 64) {
    for (size_t i = 0; i < length; ++i)
      if (!isxdigit(static_cast<unsigned char>(password[i]))) return "Use 8-63 characters or 64 hex digits.";
  }
  return nullptr;
}

inline bool isEnterpriseSsid(const char *ssid, const char *identity = nullptr) {
  if (identity && identity[0] != 0) return true;
  if (!ssid) return false;
  const char *target = "eduroam";
  for (size_t i = 0; i < 7; ++i) {
    if (!ssid[i] || tolower(static_cast<unsigned char>(ssid[i])) != target[i]) return false;
  }
  return ssid[7] == 0;
}

inline const char *enterprisePasswordError(const char *identity, const char *password) {
  if (!identity || !identity[0]) return "Enter enterprise username or identity.";
  if (strlen(identity) > 64) return "Identity is too long. Maximum: 64.";
  const size_t pwLen = password ? strlen(password) : 0;
  if (!pwLen) return "Enter enterprise Wi-Fi password.";
  if (pwLen > 64) return "Password is too long. Maximum: 64.";
  return nullptr;
}

// Restores the previously saved working network when a newly submitted credential or handshake attempt fails.
inline bool shouldFallbackToSavedNetwork(bool pendingStore, bool hasSavedSsid, bool candidateFailedAuth) {
  return pendingStore && hasSavedSsid && candidateFailedAuth;
}
}
