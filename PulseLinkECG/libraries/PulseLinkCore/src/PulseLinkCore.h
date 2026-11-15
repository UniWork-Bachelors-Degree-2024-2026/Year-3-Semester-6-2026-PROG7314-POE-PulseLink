#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <math.h>

namespace pl {
constexpr uint32_t PeriodUs = 4000;
constexpr uint16_t SamplesPerFrame = 10;
enum Type : uint8_t { Start = 1, Stop, Begin, Samples, End, Ack, Health, Vitals };
enum SampleFlags : uint8_t { LeadPlus = 1, LeadMinus = 2, Clipped = 4, TimingLate = 8 };
enum EndReason : uint8_t { Duration = 0, Operator = 1, Overflow = 2, LinkLost = 3 };
#pragma pack(push, 1)
struct Header {
  uint8_t magic[2] = {0x50, 0x4c};
  uint8_t version = 6, type = 0;
  uint16_t length = 0;
  uint32_t sequence = 0;
  uint64_t boot = 0;
  uint8_t session[16] = {};
};
struct Sample { uint32_t index; uint64_t us; uint16_t raw; uint8_t flags; };
struct StartInfo { uint16_t seconds; uint8_t benchOnly; };
struct Summary { uint32_t captured, slots; uint8_t reason; };
struct VitalReading { uint32_t nextSampleIndex; uint64_t timestampUs; uint16_t bpm; uint8_t valid; };
struct HealthInfo {
  uint32_t slots, captured;
  uint32_t missedSlots, maxLatenessUs;
  uint16_t queueDepth;
  uint16_t bpm;
  uint8_t leadFlags, bpmValid, recording;
  // Zero means not configured. Association alone must never imply API availability.
  uint8_t wifi = 0, api = 0;
};
#pragma pack(pop)
constexpr size_t MaxPayload = SamplesPerFrame * sizeof(Sample);
constexpr size_t MaxFrame = sizeof(Header) + MaxPayload + 4;
struct Frame {
  Header h;
  uint8_t payload[MaxPayload] = {};
  uint32_t checksum = 0;
};
inline uint32_t crc32(const uint8_t *p, size_t n, uint32_t crc = 0xffffffff) {
  while (n--) {
    crc ^= *p++;
    for (int b = 0; b < 8; ++b) crc = (crc >> 1) ^ (0xedb88320u & (0u - (crc & 1)));
  }
  return crc;
}
inline uint32_t checksum(const Frame &f) {
  return ~crc32(f.payload, f.h.length, crc32(reinterpret_cast<const uint8_t *>(&f.h), sizeof(Header)));
}
inline bool sameSession(const Header &a, const Header &b) { return memcmp(a.session, b.session, 16) == 0; }
inline bool nonzeroSession(const Header &h) {
  uint8_t v = 0; for (uint8_t b : h.session) v |= b; return v != 0;
}
inline Frame frame(Type type, uint64_t boot, uint32_t seq = 0, const uint8_t *session = nullptr) {
  Frame f; f.h.type = type; f.h.boot = boot; f.h.sequence = seq;
  if (session) memcpy(f.h.session, session, 16);
  return f;
}
template<class T> inline void put(Frame &f, const T &value) {
  static_assert(sizeof(T) <= MaxPayload, "Payload too large");
  f.h.length = sizeof(T); memcpy(f.payload, &value, sizeof(T));
}
template<class T> inline bool get(const Frame &f, T &value) {
  if (f.h.length != sizeof(T)) return false;
  memcpy(&value, f.payload, sizeof(T)); return true;
}
inline size_t encode(Frame &f, uint8_t *bytes) {
  f.checksum = checksum(f);
  memcpy(bytes, &f.h, sizeof(Header));
  memcpy(bytes + sizeof(Header), f.payload, f.h.length);
  memcpy(bytes + sizeof(Header) + f.h.length, &f.checksum, 4);
  return sizeof(Header) + f.h.length + 4;
}
// Length, version and CRC protect framing. CRC is not authentication or radio security.
class Parser {
  uint8_t buffer[MaxFrame] = {};
  size_t used = 0;
public:
  uint32_t invalid = 0;
  size_t pending() const { return used; }
  bool feed(uint8_t b, Frame &out) {
    if (used == sizeof(buffer)) { used = 0; ++invalid; }
    buffer[used++] = b;
    while (used >= 2) {
      if (buffer[0] != 0x50 || buffer[1] != 0x4c) { discard(); continue; }
      if (used < sizeof(Header)) return false;
      Header h; memcpy(&h, buffer, sizeof(h));
      if (h.version != 6 || h.length > MaxPayload) { ++invalid; discard(); continue; }
      const size_t n = sizeof(Header) + h.length + 4;
      if (used < n) return false;
      out.h = h; memcpy(out.payload, buffer + sizeof(Header), h.length);
      memcpy(&out.checksum, buffer + n - 4, 4);
      if (checksum(out) != out.checksum) { ++invalid; discard(); continue; }
      used -= n; memmove(buffer, buffer + n, used); return true;
    }
    return false;
  }
private:
  void discard() { --used; memmove(buffer, buffer + 1, used); }
};

enum Receipt { Rejected, Accepted, Duplicate };
class RecordingValidator {
  bool allowClinical;
public:
  explicit RecordingValidator(bool allowClinical = false) : allowClinical(allowClinical) { }
  Header identity;
  uint32_t nextSequence = 0, captured = 0, nextIndex = 0, lastCrc = 0;
  uint32_t maximumSlots = 0;
  uint64_t lastUs = 0;
  bool begun = false, ended = false, gaps = false;
  uint8_t reason = LinkLost;
  Receipt accept(const Frame &f) {
    if (f.h.length > MaxPayload || checksum(f) != f.checksum) return Rejected;
    if (begun && sameSession(identity, f.h) && identity.boot == f.h.boot &&
        f.h.sequence + 1 == nextSequence && lastCrc == f.checksum) return Duplicate;
    if (ended || f.h.sequence != nextSequence) return Rejected;
    if (!begun) {
      StartInfo info;
      if (f.h.type != Begin || !nonzeroSession(f.h) || !get(f, info) ||
          info.benchOnly > 1 || (!info.benchOnly && !allowClinical) || !info.seconds || info.seconds > 90) return Rejected;
      identity = f.h; begun = true;
      maximumSlots = info.seconds * 250;
    } else {
      if (!sameSession(identity, f.h) || identity.boot != f.h.boot) return Rejected;
      if (f.h.type == Samples) {
        if (!f.h.length || f.h.length % sizeof(Sample)) return Rejected;
        uint32_t index = nextIndex; uint64_t us = lastUs; bool missing = gaps;
        for (size_t offset = 0; offset < f.h.length; offset += sizeof(Sample)) {
          Sample s; memcpy(&s, f.payload + offset, sizeof(s));
          if (s.index < index || s.index >= maximumSlots || s.raw > 4095 || (s.flags & ~15) ||
              ((captured || offset) && s.us <= us)) return Rejected;
          missing |= s.index != index; index = s.index + 1; us = s.us;
        }
        nextIndex = index; lastUs = us; gaps = missing; captured += f.h.length / sizeof(Sample);
      } else if (f.h.type == Vitals) {
        VitalReading v;
        if (!get(f, v) || !captured || v.nextSampleIndex != nextIndex || v.timestampUs < lastUs ||
            v.valid > 1 || v.bpm > 250 || (v.valid ? v.bpm == 0 : v.bpm != 0)) return Rejected;
      } else if (f.h.type == End) {
        Summary summary;
        if (!get(f, summary) || summary.captured != captured || summary.slots < nextIndex || summary.slots > maximumSlots ||
            summary.reason > LinkLost) return Rejected;
        gaps |= summary.slots != nextIndex; reason = summary.reason; ended = true;
      } else return Rejected;
    }
    ++nextSequence; lastCrc = f.checksum; return Accepted;
  }
  bool complete() const { return ended && captured && !gaps && reason <= Operator; }
};

// Prototype positive R-peak estimator. Raw ECG is always stored without this filter.
// A validity flag describes this estimator's prerequisites, not diagnostic quality.
class BeatEstimator {
  float baseline = 2048, envelope = 0, previous = 0;
  bool above = false;
  uint64_t lastSample = 0, settlingUntil = 0, lastBeat = 0;
  uint64_t ends[24] = {};
  uint32_t intervals[24] = {};
  unsigned next = 0;
public:
  void reset(uint64_t now = 0) {
    baseline = 2048; envelope = previous = 0; above = false;
    lastSample = lastBeat = 0; settlingUntil = now + 2000000;
    memset(ends, 0, sizeof(ends)); memset(intervals, 0, sizeof(intervals)); next = 0;
  }
  void add(const Sample &s) {
    if (s.flags || (lastSample && s.us - lastSample > 8000)) { reset(s.us); lastSample = s.us; return; }
    lastSample = s.us;
    baseline += 0.015f * (s.raw - baseline);
    const float high = s.raw - baseline;
    const float filtered = previous + 0.35f * (high - previous);
    previous = filtered;
    envelope = fmaxf(fabsf(filtered), envelope * 0.995f);
    const float threshold = fmaxf(35, envelope * 0.55f);
    if (s.us >= settlingUntil && !above && filtered > threshold &&
        (!lastBeat || s.us - lastBeat >= 250000)) {
      if (lastBeat) {
        const uint64_t rr = s.us - lastBeat;
        if (rr >= 250000 && rr <= 2000000) {
          ends[next] = s.us; intervals[next] = static_cast<uint32_t>(rr); next = (next + 1) % 24;
        } else { memset(ends, 0, sizeof(ends)); }
      }
      lastBeat = s.us;
    }
    above = filtered > threshold;
  }
  uint16_t bpm(uint64_t now) const {
    if (!lastSample || now < lastSample || now - lastSample > 100000 ||
        !lastBeat || now < lastBeat || now - lastBeat > 2000000 || now < settlingUntil) return 0;
    uint64_t total = 0; unsigned count = 0;
    for (unsigned i = 0; i < 24; ++i) {
      // Include only complete RR intervals lying within the rolling five seconds.
      if (ends[i] && now >= ends[i] && now - ends[i] + intervals[i] <= 5000000) {
        total += intervals[i]; ++count;
      }
    }
    return count >= 2 ? static_cast<uint16_t>((60000000ull * count + total / 2) / total) : 0;
  }
};
}
