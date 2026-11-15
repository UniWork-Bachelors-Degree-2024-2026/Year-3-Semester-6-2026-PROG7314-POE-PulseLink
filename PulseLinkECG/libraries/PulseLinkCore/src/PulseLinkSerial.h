#pragma once
#include <Arduino.h>
#include "PulseLinkCore.h"
namespace pl {
inline bool send(HardwareSerial &port, Frame &f) {
  uint8_t bytes[MaxFrame]; const size_t n = encode(f, bytes);
  if (port.availableForWrite() < static_cast<int>(n)) return false;
  return port.write(bytes, n) == n;
}
inline uint64_t bootId() { return (static_cast<uint64_t>(esp_random()) << 32) | esp_random(); }
inline void debug(const char *text) {
  const size_t n = strlen(text);
  if (Serial && Serial.availableForWrite() >= static_cast<int>(n)) Serial.write(reinterpret_cast<const uint8_t *>(text), n);
}
}
