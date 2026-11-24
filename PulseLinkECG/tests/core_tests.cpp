#include "PulseLinkCore.h"
#include "PulseLinkPairing.h"
#include "PulseLinkUpload.h"
#include "PulseLinkCredentials.h"
#include "PulseLinkNursePolicy.h"
#include "PulseLinkNetworkMemory.h"
#include "../firmware/PulseLinkScreen/PulseLinkTouch.h"
#include <stdio.h>
#include <stdlib.h>

unsigned checks = 0;
#define CHECK(condition) do { ++checks; if (!(condition)) { \
  fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #condition); exit(1); } } while (0)
uint8_t session[16] = {1, 2, 3};
pl::Frame sealed(pl::Frame f) { f.checksum = pl::checksum(f); return f; }
pl::Frame begin() {
  auto f = pl::frame(pl::Begin, 101, 0, session); pl::put(f, pl::StartInfo{90, 1}); return sealed(f);
}
pl::Frame samples(uint32_t sequence, uint32_t first, unsigned count = 10) {
  auto f = pl::frame(pl::Samples, 101, sequence, session);
  for (unsigned i = 0; i < count; ++i) {
    pl::Sample s{first + i, 1000000ull + (first + i) * 4000ull, 2048, 0};
    memcpy(f.payload + f.h.length, &s, sizeof(s)); f.h.length += sizeof(s);
  }
  return sealed(f);
}
pl::Frame end(uint32_t sequence, uint32_t count, uint32_t slots, uint8_t reason = pl::Duration) {
  auto f = pl::frame(pl::End, 101, sequence, session);
  pl::put(f, pl::Summary{count, slots, reason}); return sealed(f);
}

void framing() {
  uint8_t bytes[pl::MaxFrame]; auto input = samples(1, 0);
  const size_t size = pl::encode(input, bytes);
  pl::Parser parser; pl::Frame output;
  parser.feed(0x77, output); parser.feed(0x50, output); parser.feed(0x11, output);
  for (size_t i = 0; i < size; ++i) CHECK(parser.feed(bytes[i], output) == (i == size - 1));
  CHECK(output.checksum == input.checksum); CHECK(parser.pending() == 0);
  bytes[sizeof(pl::Header) + 5] ^= 1;
  bool accepted = false;
  for (size_t i = 0; i < size; ++i) accepted |= parser.feed(bytes[i], output);
  CHECK(!accepted); CHECK(parser.invalid > 0);
  pl::encode(input, bytes);
  accepted = false;
  for (size_t i = 0; i < size; ++i) accepted |= parser.feed(bytes[i], output);
  CHECK(accepted); CHECK(output.checksum == input.checksum);
  auto invalid = input; invalid.h.version = 99; pl::encode(invalid, bytes);
  accepted = false; pl::Parser version;
  for (size_t i = 0; i < size; ++i) accepted |= version.feed(bytes[i], output);
  CHECK(!accepted);
  pl::Header header; header.length = 65535;
  const auto *p = reinterpret_cast<const uint8_t *>(&header);
  pl::Parser huge;
  for (size_t i = 0; i < sizeof(header); ++i) CHECK(!huge.feed(p[i], output));
  CHECK(huge.invalid != 0);
}

void recording() {
  pl::RecordingValidator r;
  CHECK(r.accept(samples(1, 0)) == pl::Rejected);
  CHECK(r.accept(begin()) == pl::Accepted);
  CHECK(r.accept(begin()) == pl::Duplicate);
  CHECK(!r.complete());
  auto wrongBoot = samples(1, 0); wrongBoot.h.boot = 102; wrongBoot = sealed(wrongBoot);
  CHECK(r.accept(wrongBoot) == pl::Rejected);
  auto wrongSession = samples(1, 0); wrongSession.h.session[0] = 2; wrongSession = sealed(wrongSession);
  CHECK(r.accept(wrongSession) == pl::Rejected);
  CHECK(r.accept(samples(2, 10)) == pl::Rejected);
  CHECK(r.accept(samples(1, 0)) == pl::Accepted);
  CHECK(r.accept(samples(1, 0)) == pl::Duplicate); CHECK(r.captured == 10);
  auto changedRetry = samples(1, 0); changedRetry.payload[12] ^= 1; changedRetry = sealed(changedRetry);
  CHECK(r.accept(changedRetry) == pl::Rejected);
  auto backwardTime = samples(2, 10); memset(backwardTime.payload + 4, 0, 8); backwardTime = sealed(backwardTime);
  CHECK(r.accept(backwardTime) == pl::Rejected);
  CHECK(r.accept(samples(2, 9)) == pl::Rejected);
  CHECK(r.accept(end(2, 9, 10)) == pl::Rejected);
  CHECK(r.accept(end(2, 10, 10)) == pl::Accepted); CHECK(r.complete());
  CHECK(r.accept(end(2, 10, 10)) == pl::Duplicate);
  CHECK(r.accept(samples(3, 10)) == pl::Rejected);
  pl::RecordingValidator gap;
  CHECK(gap.accept(begin()) == pl::Accepted);
  CHECK(gap.accept(samples(1, 1)) == pl::Accepted);
  CHECK(gap.accept(end(2, 10, 11)) == pl::Accepted); CHECK(!gap.complete());
  pl::RecordingValidator stopped;
  CHECK(stopped.accept(begin()) == pl::Accepted);
  CHECK(stopped.accept(samples(1, 0)) == pl::Accepted);
  CHECK(stopped.accept(end(2, 10, 10, pl::Overflow)) == pl::Accepted); CHECK(!stopped.complete());
  auto clinical = begin(); clinical.payload[2] = 0; clinical = sealed(clinical);
  pl::RecordingValidator unauthorised;
  CHECK(unauthorised.accept(clinical) == pl::Rejected);
  auto overrun = samples(1, 22500);
  pl::RecordingValidator bounded; CHECK(bounded.accept(begin()) == pl::Accepted);
  CHECK(bounded.accept(overrun) == pl::Rejected);
  auto badFlags = samples(1, 0); badFlags.payload[14] = 128; badFlags = sealed(badFlags);
  CHECK(bounded.accept(badFlags) == pl::Rejected);
  CHECK(bounded.accept(end(1, 0, 22501)) == pl::Rejected);
}

void restartAndRetries() {
  // Emulate durable bytes, lost ACKs, partial writes and a rebooted reader.
  static uint8_t disk[1000000]; size_t used = 0;
  pl::RecordingValidator receiver;
  auto append = [&](pl::Frame f) {
    CHECK(receiver.accept(f) == pl::Accepted);
    used += pl::encode(f, disk + used);
    CHECK(receiver.accept(f) == pl::Duplicate);
  };
  append(begin());
  for (unsigned b = 0; b < 2250; ++b) append(samples(b + 1, b * 10));
  CHECK(receiver.captured == 22500); CHECK(!receiver.complete());
  const size_t withoutEnd = used;
  append(end(2251, 22500, 22500)); CHECK(receiver.complete());
  auto recover = [&](size_t length) {
    pl::Parser parser; pl::RecordingValidator recovered; pl::Frame f;
    bool valid = true;
    for (size_t i = 0; i < length; ++i)
      if (parser.feed(disk[i], f) && recovered.accept(f) != pl::Accepted) valid = false;
    return valid && parser.invalid == 0 && parser.pending() == 0 && recovered.complete();
  };
  CHECK(recover(used)); CHECK(!recover(withoutEnd)); CHECK(!recover(used - 1));
  for (size_t cut = withoutEnd; cut < used; ++cut) CHECK(!recover(cut));
  disk[8000] ^= 0x80; CHECK(!recover(used)); disk[8000] ^= 0x80;
  CHECK(recover(used));
}

void bpm() {
  pl::BeatEstimator beats; beats.reset();
  uint64_t now = 0;
  auto feed = [&](unsigned seconds, unsigned rate, uint8_t flags = 0) {
    for (unsigned i = 0; i < seconds * 250; ++i) {
      now += 4000;
      const double phase = fmod(static_cast<double>(now), 60000000.0 / rate);
      const double distance = fmin(phase, 60000000.0 / rate - phase);
      const uint16_t raw = static_cast<uint16_t>(2048 + 500 * exp(-distance * distance / (2 * 12000.0 * 12000.0)));
      beats.add(pl::Sample{static_cast<uint32_t>(now / 4000), now, raw, flags});
    }
  };
  feed(10, 60); CHECK(beats.bpm(now) >= 59 && beats.bpm(now) <= 61);
  feed(10, 120); CHECK(beats.bpm(now) >= 119 && beats.bpm(now) <= 121);
  feed(1, 120, pl::LeadPlus); CHECK(beats.bpm(now) == 0);
  feed(1, 120); CHECK(beats.bpm(now) == 0);
  feed(8, 120); CHECK(beats.bpm(now) >= 119 && beats.bpm(now) <= 121);
  CHECK(beats.bpm(now + 200001) == 0);
  feed(1, 120, pl::Clipped); CHECK(beats.bpm(now) == 0);
  feed(8, 120); feed(1, 120, pl::TimingLate); CHECK(beats.bpm(now) == 0);
  beats.reset(now);
  for (unsigned i = 0; i < 2500; ++i) { now += 4000; beats.add(pl::Sample{i, now, 2048, 0}); }
  CHECK(beats.bpm(now) == 0);
}

void pairing() {
  pl::PairingPolicy p;
  CHECK(p.view(0).state == pl::BleState::Off);
  CHECK(!p.open(0, 123456)); CHECK(!p.connect(0));
  p.enable(); CHECK(p.view(0).state == pl::BleState::Idle);
  CHECK(!p.connect(0)); CHECK(!p.open(0, 1000000));
  CHECK(p.open(0, 42)); CHECK(p.view(0).code == 42);
  CHECK(p.view(0).remainingSeconds == 120);
  CHECK(p.view(119999).code == 42); CHECK(p.view(119999).remainingSeconds == 1);
  CHECK(p.view(120000).code == 0); CHECK(!p.connect(120000));
  CHECK(p.expire(120000)); CHECK(!p.expire(120001));
  CHECK(p.open(200000, 0)); CHECK(p.connect(200001));
  CHECK(!p.connect(200002)); CHECK(!p.open(200003, 555555));
  CHECK(p.view(200003).state == pl::BleState::Connecting);
  CHECK(!p.authenticate(200003, false, true, true, 16));
  CHECK(!p.authenticate(200003, true, false, true, 16));
  CHECK(!p.authenticate(200003, true, true, false, 16));
  CHECK(!p.authenticate(200003, true, true, true, 7));
  CHECK(p.authenticate(200003, true, true, true, 16));
  CHECK(p.view(200003).state == pl::BleState::Secured); CHECK(p.view(200003).code == 0);
  CHECK(!p.expire(400000)); CHECK(!p.open(400000, 123456));
  p.disable(); CHECK(p.view(400001).state == pl::BleState::Off);
  CHECK(!p.authenticate(400002, true, true, true, 16));
  p.enable(); CHECK(p.open(500000, 999999)); CHECK(p.connect(500001));
  CHECK(!p.authenticate(620000, true, true, true, 16));
  CHECK(p.expire(620000)); CHECK(p.view(620000).code == 0);
  const uint32_t wrapStart = UINT32_MAX - 60000;
  CHECK(p.open(wrapStart, 123456));
  CHECK(p.view(uint32_t(wrapStart + 119999)).remainingSeconds == 1);
  CHECK(p.view(uint32_t(wrapStart + 120000)).code == 0);
  CHECK(p.expire(uint32_t(wrapStart + 120000)));
  CHECK(p.open(900000, 654321)); CHECK(p.connect(900001));
  p.disconnect(); CHECK(p.view(900002).code == 0); CHECK(!p.connect(900003));
  // Bonded reconnection while BLE is enabled and idle, plus authorised background transfer, resumption, duplicate ACK and idle timeout.
  CHECK(p.connect(910000, true));
  CHECK(p.authenticate(910001, true, true, true, 16, true));
  CHECK(p.view(910001).state == pl::BleState::Secured);
  CHECK(!p.view(910001).transferUnlocked);
  CHECK(!p.authoriseTransfer(910002, "grant-1", "nurse-1", false, "grant-1", "nurse-1"));
  CHECK(!p.authoriseTransfer(910002, "grant-1", "nurse-1", true, "grant-2", "nurse-1"));
  CHECK(!p.authoriseTransfer(910002, "grant-1", "nurse-1", true, "grant-1", "nurse-2"));
  CHECK(p.authoriseTransfer(910002, "grant-1", "nurse-1", true, "grant-1", "nurse-1"));
  CHECK(p.view(910002).transferUnlocked);
  CHECK(p.transferAllowed(910003, "grant-1", true));
  CHECK(p.beginSessionTransfer(910004, "SESSION-01020300000000000000000000000000", 2));
  CHECK(p.view(910004).nextSequence == 2);
  CHECK(p.acknowledgeBatch(910005, "SESSION-01020300000000000000000000000000", 1)); // duplicate ACK before resume point
  CHECK(!p.acknowledgeBatch(910005, "SESSION-01020300000000000000000000000000", 3)); // out of order rejected
  CHECK(p.acknowledgeBatch(910005, "SESSION-01020300000000000000000000000000", 2));
  CHECK(p.acknowledgeBatch(910005, "SESSION-01020300000000000000000000000000", 2)); // idempotent duplicate ACK
  CHECK(!p.completeSessionTransfer(910006, "SESSION-01020300000000000000000000000000", 4));
  CHECK(p.acknowledgeBatch(910006, "SESSION-01020300000000000000000000000000", 3));
  CHECK(p.completeSessionTransfer(910007, "SESSION-01020300000000000000000000000000", 4));
  CHECK(!p.transferAllowed(910008, "grant-revoked", true));
  CHECK(!p.view(910008).transferUnlocked);
  CHECK(p.authoriseTransfer(910009, "grant-1", "nurse-1", true, "grant-1", "nurse-1"));
  CHECK(p.expire(910009 + pl::PairingPolicy::IdleTimeoutMs));
  CHECK(p.view(910009 + pl::PairingPolicy::IdleTimeoutMs).state == pl::BleState::Idle);
}

void touchMapping() {
  int x = -1, y = -1;
  CHECK(pl::landscapeTouch(506, 1035, x, y)); CHECK(x == 245 && y == 218);
  CHECK(pl::landscapeTouch(3800, 3800, x, y)); CHECK(x == 0 && y == 0);
  CHECK(pl::landscapeTouch(200, 200, x, y)); CHECK(x == 319 && y == 239);
  CHECK(pl::landscapeTouch(3900, 3900, x, y)); CHECK(x == 0 && y == 0);
  CHECK(!pl::landscapeTouch(0, 2000, x, y)); CHECK(!pl::landscapeTouch(2000, 4095, x, y));
  pl::TouchLatch latch;
  CHECK(latch.ready(true, 0)); latch.accept(); CHECK(!latch.ready(true, 1));
  CHECK(!latch.ready(false, 5)); CHECK(!latch.ready(true, 10));
  CHECK(!latch.ready(false, 20)); CHECK(!latch.ready(false, 99));
  CHECK(!latch.ready(false, 100)); CHECK(latch.ready(true, 101));
  latch.accept(); const uint32_t wrap = UINT32_MAX - 40;
  CHECK(!latch.ready(false, wrap)); CHECK(!latch.ready(false, uint32_t(wrap + 80)));
  CHECK(latch.ready(true, uint32_t(wrap + 81)));
}

void uploadJournal() {
  static uint8_t journal[600000]; size_t length = 0;
  auto append = [&](pl::Frame f) { length += pl::encode(f, journal + length); };
  auto first = begin(); first.payload[2] = 0; append(first);
  unsigned frameSequence = 1;
  for (unsigned index = 0; index < 22500; index += 10) {
    append(samples(frameSequence++, index));
    if ((index + 10) % 250 == 0) {
      auto v = pl::frame(pl::Vitals, 101, frameSequence++, session);
      pl::put(v, pl::VitalReading{index + 10, 1000000ull + (index + 9) * 4000ull, 60, 1}); append(v);
    }
  }
  const size_t withoutEnd = length;
  append(end(frameSequence, 22500, 22500));
  uint32_t checkpoints[90] = {};
  auto read = [&](size_t size, bool corrupt = false) {
    pl::UploadJournal decoder(session); unsigned totals = 0;
    for (size_t i = 0; i < size; ++i) {
      decoder.feed(journal[i]);
      if (decoder.state == pl::UploadReadState::Ready) {
        CHECK(decoder.count == 250); CHECK(decoder.samples[0].index == decoder.sequence * 250);
        CHECK(decoder.bpm == 60);
        if (!corrupt) {
          if (!checkpoints[decoder.sequence]) checkpoints[decoder.sequence] = decoder.prefixCrc;
          else CHECK(checkpoints[decoder.sequence] == decoder.prefixCrc);
        }
        totals += decoder.count; decoder.nextBatch();
      }
      if (decoder.state == pl::UploadReadState::Invalid) break;
    }
    decoder.endOfFile();
    if (size == length && !corrupt) {
      CHECK(decoder.state == pl::UploadReadState::Complete); CHECK(decoder.sequence == 90);
      CHECK(totals == 22500); CHECK(decoder.record.captured == 22500);
    } else CHECK(decoder.state == pl::UploadReadState::Invalid);
  };
  read(length); read(length); read(withoutEnd); read(length - 1);
  journal[3000] ^= 0x20; read(length, true); journal[3000] ^= 0x20;
  pl::UploadJournal bench(session); uint8_t bytes[pl::MaxFrame]; auto b = begin(); size_t n = pl::encode(b, bytes);
  for (size_t i = 0; i < n; ++i) bench.feed(bytes[i]);
  CHECK(bench.state == pl::UploadReadState::Bench);
  uint8_t wrong[16] = {9}; pl::UploadJournal wrongIdentity(wrong); auto c = first; n = pl::encode(c, bytes);
  for (size_t i = 0; i < n; ++i) wrongIdentity.feed(bytes[i]);
  CHECK(wrongIdentity.state == pl::UploadReadState::Invalid);
  // Journals without BPM snapshots retain raw samples and report an unknown BPM.
  length = 0; append(first);
  for (unsigned index = 0; index < 510; index += 10) append(samples(index / 10 + 1, index));
  append(end(52, 510, 510));
  pl::UploadJournal noVitals(session); unsigned total = 0;
  for (size_t i = 0; i < length; ++i) {
    noVitals.feed(journal[i]);
    if (noVitals.state == pl::UploadReadState::Ready) {
      CHECK(noVitals.bpm == 0); CHECK(noVitals.count == (noVitals.sequence == 2 ? 10 : 250));
      CHECK(noVitals.samples[0].index == noVitals.sequence * 250);
      total += noVitals.count; noVitals.nextBatch();
    }
  }
  noVitals.endOfFile(); CHECK(noVitals.state == pl::UploadReadState::Complete);
  CHECK(total == 510); CHECK(noVitals.sequence == 3);
}

int main() {
  pl::NursePermission nurse;
  const uint64_t server = 1793950000000ull;
  CHECK(!nurse.allows(0));
  CHECK(!nurse.confirm(0, server, 0));
  CHECK(!nurse.confirm(server, server, 0));
  CHECK(!nurse.confirm(server, server + 8ull * 3600000 + 1, 0));
  CHECK(nurse.confirm(server, server + 8ull * 3600000, 100));
  CHECK(nurse.allows(100, 90000));
  CHECK(nurse.current(1100) == server + 1000);
  CHECK(!nurse.allows(100 + 8ul * 3600000 - 89999, 90000));
  CHECK(!nurse.allows(100 + 8ul * 3600000));
  nurse.clear(); CHECK(!nurse.allows(101));
  const uint32_t wrapAt = UINT32_MAX - 1000;
  CHECK(nurse.confirm(server, server + 8ull * 3600000, wrapAt));
  CHECK(nurse.allows(uint32_t(wrapAt + 2000), 90000));
  CHECK(nurse.current(uint32_t(wrapAt + 2000)) == server + 2000);
  CHECK(pl::personalPasswordError("") == nullptr);
  CHECK(pl::personalPasswordError("short") != nullptr);
  CHECK(pl::personalPasswordError("1234567") != nullptr);
  CHECK(pl::personalPasswordError("12345678") == nullptr);
  char password[66]; memset(password, 'a', 63); password[63] = 0;
  CHECK(pl::personalPasswordError(password) == nullptr);
  password[63] = 'f'; password[64] = 0;
  CHECK(pl::personalPasswordError(password) == nullptr);
  password[3] = 'z'; CHECK(pl::personalPasswordError(password) != nullptr);
  password[3] = 'a'; password[64] = 'a'; password[65] = 0;
  CHECK(pl::personalPasswordError(password) != nullptr);
  CHECK(pl::shouldFallbackToSavedNetwork(true, true, true));
  CHECK(!pl::shouldFallbackToSavedNetwork(false, true, true));
  CHECK(!pl::shouldFallbackToSavedNetwork(true, false, true));
  CHECK(!pl::shouldFallbackToSavedNetwork(true, true, false));
  CHECK(pl::isEnterpriseSsid("eduroam"));
  CHECK(pl::isEnterpriseSsid("EduRoam"));
  CHECK(!pl::isEnterpriseSsid("ClinicWiFi"));
  CHECK(pl::isEnterpriseSsid("CustomNet", "student@mandela.ac.za"));
  CHECK(pl::enterprisePasswordError("", "secret") != nullptr);
  CHECK(pl::enterprisePasswordError("student@mandela.ac.za", "") != nullptr);
  CHECK(pl::enterprisePasswordError("student@mandela.ac.za", "pw") == nullptr);
  CHECK(pl::validPatientBirthDate(""));
  CHECK(pl::validPatientBirthDate("2000-02-29"));
  CHECK(pl::validPatientBirthDate("2024-02-29"));
  CHECK(!pl::validPatientBirthDate("1900-02-29"));
  CHECK(!pl::validPatientBirthDate("2023-02-29"));
  CHECK(!pl::validPatientBirthDate("2026-04-31"));
  CHECK(!pl::validPatientBirthDate("2026- 1-01"));
  CHECK(!pl::validPatientBirthDate("2026-+1-01"));
  CHECK(!pl::validPatientBirthDate("2026-1-01"));
  CHECK(!pl::validPatientBirthDate("2026-00-01"));
  CHECK(!pl::validPatientBirthDate("2026-01-00"));
  pl::NetworkMemoryGate memory;
  CHECK(memory.begin(true));
  CHECK(!memory.tryHold(2));
  memory.finish();
  CHECK(memory.tryHold(2));
  CHECK(!memory.begin(true));
  memory.hold(1, true); memory.hold(1, false);
  CHECK(!memory.begin(true));
  CHECK(memory.begin(false));
  CHECK(memory.isActive());
  CHECK(!memory.begin(false));
  memory.finish(); memory.hold(2, false);
  CHECK(!memory.isActive());
  CHECK(memory.begin(true)); memory.finish();
  CHECK(pl::shouldReleaseIdleUpload(false, 0, false));
  CHECK(!pl::shouldReleaseIdleUpload(true, 0, false));
  CHECK(!pl::shouldReleaseIdleUpload(false, 7, false));
  CHECK(!pl::shouldReleaseIdleUpload(false, 0, true));
  framing(); recording(); restartAndRetries(); bpm(); pairing(); touchMapping(); uploadJournal();
  printf("PASS: %u checks (recording, recovery, BPM, pairing, touch and upload decoding).\n", checks);
}
