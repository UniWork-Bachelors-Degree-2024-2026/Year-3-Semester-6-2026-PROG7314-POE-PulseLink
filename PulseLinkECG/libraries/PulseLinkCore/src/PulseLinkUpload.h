#pragma once
#include "PulseLinkCore.h"

namespace pl {
enum class UploadReadState { Reading, Ready, Complete, Invalid, Bench };

class UploadJournal {
  Frame held;
  bool hasHeld = false;
  uint8_t expected[16];
  void consume(const Frame &f) {
    if (record.accept(f) != Accepted || memcmp(f.h.session, expected, 16)) { state = UploadReadState::Invalid; return; }
    Frame copy = f; uint8_t bytes[MaxFrame]; const size_t n = encode(copy, bytes);
    prefixCrc = crc32(bytes, n, prefixCrc);
    if (f.h.type == Begin) {
      StartInfo info; get(f, info);
      if (info.benchOnly) state = UploadReadState::Bench;
    } else if (f.h.type == Samples) {
      if (count + f.h.length / sizeof(Sample) > 250) { state = UploadReadState::Invalid; return; }
      for (unsigned offset = 0; offset < f.h.length; offset += sizeof(Sample))
        memcpy(&samples[count++], f.payload + offset, sizeof(Sample));
    } else if (f.h.type == Vitals) {
      VitalReading v; get(f, v); bpm = v.valid ? v.bpm : 0;
      if (count) state = UploadReadState::Ready;
    } else if (f.h.type == End) {
      if (!record.complete()) state = UploadReadState::Invalid;
      else state = count ? UploadReadState::Ready : UploadReadState::Complete;
    }
  }
public:
  Parser parser;
  RecordingValidator record{true};
  Sample samples[250];
  uint16_t count = 0, bpm = 0;
  uint32_t sequence = 0, prefixCrc = 0xffffffff;
  UploadReadState state = UploadReadState::Reading;
  explicit UploadJournal(const uint8_t *session) { memcpy(expected, session, 16); }
  void feed(uint8_t byte) {
    if (state != UploadReadState::Reading) return;
    Frame frame;
    if (parser.feed(byte, frame)) {
      if (count == 250 && frame.h.type == Samples) {
        held = frame; hasHeld = true; bpm = 0; state = UploadReadState::Ready;
      } else consume(frame);
    }
    if (parser.invalid) state = UploadReadState::Invalid;
  }
  void nextBatch() {
    if (state != UploadReadState::Ready) return;
    ++sequence; count = bpm = 0;
    state = record.ended ? UploadReadState::Complete : UploadReadState::Reading;
    if (hasHeld) { hasHeld = false; consume(held); }
  }
  void endOfFile() {
    if (parser.pending() || parser.invalid || !record.ended || !record.complete()) state = UploadReadState::Invalid;
  }
};
}
