#pragma once
#include <stdint.h>
#include <string.h>

namespace pl {
enum class BleState : uint8_t { Off, Idle, Pairing, Connecting, Secured };
struct PairingView {
  BleState state;
  uint32_t code;
  uint16_t remainingSeconds;
  bool transferUnlocked = false;
  char activeSessionId[44] = {};
  uint32_t nextSequence = 0;
};

// Caller serialises access because the BLE host and display run on separate tasks.
class PairingPolicy {
  bool enabled_ = false, window_ = false, connected_ = false, secured_ = false;
  bool transferUnlocked_ = false;
  uint32_t openedAt_ = 0, code_ = 0, lastActivityAt_ = 0, nextSequence_ = 0;
  char unlockedGrantId_[36] = {}, unlockedNurseUid_[129] = {}, activeSessionId_[44] = {};
  bool active(uint32_t now) const { return window_ && uint32_t(now - openedAt_) < WindowMs; }
public:
  static constexpr uint32_t WindowMs = 120000;
  static constexpr uint32_t IdleTimeoutMs = 300000;
  void enable() { enabled_ = true; }
  void disable() { enabled_ = false; disconnect(); }
  bool open(uint32_t now, uint32_t code) {
    if (!enabled_ || connected_ || code > 999999) return false;
    openedAt_ = now; code_ = code; window_ = true; return true;
  }
  bool connect(uint32_t now, bool allowBondedIdle = false) {
    if (!enabled_ || connected_ || (!active(now) && !allowBondedIdle)) return false;
    connected_ = true; lastActivityAt_ = now; return true;
  }
  bool authenticate(uint32_t now, bool encrypted, bool authenticated, bool bonded, uint8_t keyBytes, bool bondedReconnect = false) {
    if (!enabled_ || !connected_ || (!active(now) && !bondedReconnect) || !encrypted || !authenticated || !bonded || keyBytes != 16)
      return false;
    secured_ = true; window_ = false; code_ = 0; lastActivityAt_ = now; return true;
  }
  bool authoriseTransfer(uint32_t now, const char *activeGrantId, const char *activeNurseUid, bool shiftAllows,
                         const char *requestedGrantId, const char *requestedNurseUid) {
    if (!enabled_ || !secured_ || !shiftAllows || !activeGrantId || !activeGrantId[0] ||
        !requestedGrantId || !requestedGrantId[0] || strcmp(activeGrantId, requestedGrantId) != 0) {
      revokeTransfer(); return false;
    }
    if (activeNurseUid && activeNurseUid[0]) {
      if (!requestedNurseUid || !requestedNurseUid[0] || strcmp(activeNurseUid, requestedNurseUid) != 0) {
        revokeTransfer(); return false;
      }
    }
    strncpy(unlockedGrantId_, requestedGrantId, sizeof(unlockedGrantId_) - 1);
    unlockedGrantId_[sizeof(unlockedGrantId_) - 1] = 0;
    if (requestedNurseUid) {
      strncpy(unlockedNurseUid_, requestedNurseUid, sizeof(unlockedNurseUid_) - 1);
      unlockedNurseUid_[sizeof(unlockedNurseUid_) - 1] = 0;
    } else unlockedNurseUid_[0] = 0;
    transferUnlocked_ = true; lastActivityAt_ = now;
    return true;
  }
  bool transferAllowed(uint32_t now, const char *activeGrantId, bool shiftAllows) {
    if (!enabled_ || !secured_ || !transferUnlocked_ || !shiftAllows || !activeGrantId || !activeGrantId[0] ||
        strcmp(unlockedGrantId_, activeGrantId) != 0 || uint32_t(now - lastActivityAt_) >= IdleTimeoutMs) {
      revokeTransfer(); return false;
    }
    return true;
  }
  bool beginSessionTransfer(uint32_t now, const char *sessionId, uint32_t resumeSequence = 0) {
    if (!transferUnlocked_ || !sessionId || !sessionId[0] || strlen(sessionId) >= sizeof(activeSessionId_)) return false;
    strncpy(activeSessionId_, sessionId, sizeof(activeSessionId_) - 1);
    activeSessionId_[sizeof(activeSessionId_) - 1] = 0;
    nextSequence_ = resumeSequence; lastActivityAt_ = now;
    return true;
  }
  bool acknowledgeBatch(uint32_t now, const char *sessionId, uint32_t sequence) {
    if (!transferUnlocked_ || !sessionId || strcmp(activeSessionId_, sessionId) != 0) return false;
    if (sequence + 1 == nextSequence_) { lastActivityAt_ = now; return true; } // Idempotent duplicate ACK
    if (sequence != nextSequence_) return false;
    ++nextSequence_; lastActivityAt_ = now;
    return true;
  }
  bool completeSessionTransfer(uint32_t now, const char *sessionId, uint32_t expectedBatches) {
    if (!transferUnlocked_ || !sessionId || strcmp(activeSessionId_, sessionId) != 0 || nextSequence_ != expectedBatches)
      return false;
    activeSessionId_[0] = 0; nextSequence_ = 0; lastActivityAt_ = now;
    return true;
  }
  void touchActivity(uint32_t now) { if (connected_) lastActivityAt_ = now; }
  void revokeTransfer() {
    transferUnlocked_ = false; unlockedGrantId_[0] = 0; unlockedNurseUid_[0] = 0;
    activeSessionId_[0] = 0; nextSequence_ = 0;
  }
  void disconnect() {
    window_ = connected_ = secured_ = false; code_ = 0;
    revokeTransfer();
  }
  bool expire(uint32_t now) {
    if (window_ && !active(now)) {
      disconnect(); return true;
    }
    if (connected_ && uint32_t(now - lastActivityAt_) >= IdleTimeoutMs) {
      disconnect(); return true;
    }
    return false;
  }
  PairingView view(uint32_t now) const {
    PairingView v{};
    if (!enabled_) { v.state = BleState::Off; return v; }
    if (secured_) {
      v.state = BleState::Secured;
      v.transferUnlocked = transferUnlocked_;
      strncpy(v.activeSessionId, activeSessionId_, sizeof(v.activeSessionId) - 1);
      v.nextSequence = nextSequence_;
      return v;
    }
    if (!active(now)) { v.state = BleState::Idle; return v; }
    v.state = connected_ ? BleState::Connecting : BleState::Pairing;
    v.code = code_;
    v.remainingSeconds = static_cast<uint16_t>((WindowMs - uint32_t(now - openedAt_) + 999) / 1000);
    return v;
  }
};
}
