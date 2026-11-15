#pragma once
#include <stdint.h>

namespace pl {
// Call under the network critical section. Owners cannot release each other's reservation.
class NetworkMemoryGate {
  uint8_t held = 0;
  bool active = false;
public:
  void hold(uint8_t owner, bool value) { if (value) held |= owner; else held &= ~owner; }
  bool tryHold(uint8_t owner) { if (active) return false; held |= owner; return true; }
  bool holds(uint8_t owner) const { return (held & owner) != 0; }
  bool isActive() const { return active; }
  bool begin(bool background) {
    if (active || (background && held)) return false;
    active = true; return true;
  }
  void finish() { active = false; }
};

inline bool shouldReleaseIdleUpload(bool apiReady, uint32_t requestId, bool stageIdle) {
  return !apiReady && requestId == 0 && !stageIdle;
}
}
