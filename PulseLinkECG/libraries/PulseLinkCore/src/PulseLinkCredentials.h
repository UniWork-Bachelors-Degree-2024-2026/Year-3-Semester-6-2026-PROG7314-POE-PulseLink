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

// Restores the previously saved working network when a newly submitted credential or handshake attempt fails.
inline bool shouldFallbackToSavedNetwork(bool pendingStore, bool hasSavedSsid, bool candidateFailedAuth) {
  return pendingStore && hasSavedSsid && candidateFailedAuth;
}
}
