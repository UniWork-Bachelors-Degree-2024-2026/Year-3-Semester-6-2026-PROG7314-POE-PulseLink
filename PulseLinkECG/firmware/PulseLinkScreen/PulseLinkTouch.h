#pragma once
#include <stdint.h>

namespace pl {
inline bool landscapeTouch(uint16_t rawX, uint16_t rawY, int &x, int &y) {
  if (rawX < 180 || rawX > 3900 || rawY < 180 || rawY > 3900) return false;
  // XPT2046 axes are swapped and reversed relative to the landscape panel.
  x = (3800 - static_cast<int>(rawY)) * 319 / 3600;
  y = (3800 - static_cast<int>(rawX)) * 239 / 3600;
  if (x < 0) x = 0; else if (x > 319) x = 319;
  if (y < 0) y = 0; else if (y > 239) y = 239;
  return true;
}

class TouchLatch {
  bool latched = false, releasing = false;
  uint32_t releaseAt = 0;
public:
  bool ready(bool pressed, uint32_t now) {
    if (!pressed) {
      if (!releasing) { releaseAt = now; releasing = true; }
      if (uint32_t(now - releaseAt) >= 80) latched = false;
      return false;
    }
    releasing = false;
    return !latched;
  }
  void accept() { latched = true; }
};
}
