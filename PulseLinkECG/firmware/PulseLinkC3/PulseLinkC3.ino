// Acquires ECG for the wired touchscreen, which authorises and journals clinical recordings.
#include <Arduino.h>
#include <atomic>
#include <esp_timer.h>
#include <PulseLinkSerial.h>

HardwareSerial screen(1);
pl::Parser screenParser;
QueueHandle_t records, commands;
uint64_t boot;
std::atomic<uint32_t> lastScreenAt{0};
std::atomic<bool> abortRequested{false};
uint32_t lastHealthAt = 0;
uint64_t screenBoot = 0;
bool recovering = false;
pl::Frame pending;
bool waiting = false;
uint32_t sentAt = 0;
portMUX_TYPE stateLock = portMUX_INITIALIZER_UNLOCKED;
pl::HealthInfo sharedHealth{};
uint8_t activeSession[16] = {};
bool busy = false;

void acquire(void *) {
  const esp_timer_create_args_t args = {
    .callback = [](void *task) { xTaskNotifyGive(static_cast<TaskHandle_t>(task)); },
    .arg = xTaskGetCurrentTaskHandle(), .dispatch_method = ESP_TIMER_TASK,
    .name = "ecg250", .skip_unhandled_events = true
  };
  esp_timer_handle_t timer;
  ESP_ERROR_CHECK(esp_timer_create(&args, &timer));
  ESP_ERROR_CHECK(esp_timer_start_periodic(timer, pl::PeriodUs));
  pl::BeatEstimator beats; beats.reset();
  pl::Frame batch;
  uint32_t sequence = 1, slots = 0, captured = 0;
  uint64_t due = 0, until = 0;
  uint32_t missedSlots = 0, maxLatenessUs = 0;
  bool recording = false, ending = false;
  uint8_t endReason = pl::Duration;
  for (;;) {
    ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
    uint64_t now = esp_timer_get_time();
    if (abortRequested) {
      recording = ending = false;
      xQueueReset(records); xQueueReset(commands);
      batch = pl::Frame{}; beats.reset(now); abortRequested = false;
    }
    pl::Frame command;
    while (xQueueReceive(commands, &command, 0) == pdTRUE) {
      if (command.h.type == pl::Start && !recording && !ending) {
        pl::StartInfo info; pl::get(command, info);
        due = now; until = now + static_cast<uint64_t>(info.seconds) * 1000000;
        missedSlots = maxLatenessUs = 0;
        captured = slots = 0; sequence = 1; recording = true; beats.reset(now);
        batch = pl::frame(pl::Samples, boot, sequence++, command.h.session);
      } else if (command.h.type == pl::Stop && recording) {
        recording = false; ending = true; endReason = pl::Operator;
      }
    }
    if (recording && now >= until) { recording = false; ending = true; endReason = pl::Duration; }
    if (recording && millis() - lastScreenAt > 3000) {
      recording = false; ending = true; endReason = pl::LinkLost;
    }
    const uint8_t leads = (digitalRead(5) ? pl::LeadPlus : 0) | (digitalRead(4) ? pl::LeadMinus : 0);
    if (recording && now >= due) {
      const uint32_t missed = (now - due) / pl::PeriodUs;
      slots += missed;
      missedSlots += missed;
      const uint64_t target = due + static_cast<uint64_t>(missed) * pl::PeriodUs;
      maxLatenessUs = max(maxLatenessUs, static_cast<uint32_t>(now - target));
      pl::Sample sample{slots++, now, static_cast<uint16_t>(analogRead(0)), leads};
      if (sample.raw <= 8 || sample.raw >= 4087) sample.flags |= pl::Clipped;
      if (now - target > 1000 || missed) sample.flags |= pl::TimingLate;
      due = target + pl::PeriodUs; beats.add(sample);
      memcpy(batch.payload + batch.h.length, &sample, sizeof(sample)); batch.h.length += sizeof(sample);
      ++captured;
      if (batch.h.length == pl::MaxPayload) {
        if (xQueueSend(records, &batch, 0) != pdTRUE) {
          // Retain this full batch and stop rather than silently overwrite samples.
          recording = false; ending = true; endReason = pl::Overflow;
        } else {
          batch = pl::frame(pl::Samples, boot, sequence++, batch.h.session);
          if (captured % 250 == 0) {
            const uint16_t bpm = leads ? 0 : beats.bpm(now);
            auto vital = pl::frame(pl::Vitals, boot, batch.h.sequence, batch.h.session);
            pl::put(vital, pl::VitalReading{slots, now, bpm, static_cast<uint8_t>(bpm != 0)});
            if (xQueueSend(records, &vital, 0) == pdTRUE) batch = pl::frame(pl::Samples, boot, sequence++, batch.h.session);
          }
        }
      }
    }
    if (ending) {
      if (batch.h.length && xQueueSend(records, &batch, 0) == pdTRUE)
        batch = pl::frame(pl::Samples, boot, sequence++, batch.h.session);
      if (!batch.h.length) {
        pl::Frame end = pl::frame(pl::End, boot, batch.h.sequence, batch.h.session);
        pl::put(end, pl::Summary{captured, slots, endReason});
        if (xQueueSend(records, &end, 0) == pdTRUE) ending = false;
      }
    }
    portENTER_CRITICAL(&stateLock);
    sharedHealth.slots = slots; sharedHealth.captured = captured;
    sharedHealth.missedSlots = missedSlots; sharedHealth.maxLatenessUs = maxLatenessUs;
    sharedHealth.queueDepth = uxQueueMessagesWaiting(records);
    sharedHealth.leadFlags = leads; sharedHealth.recording = recording;
    sharedHealth.bpm = recording && !leads ? beats.bpm(now) : 0;
    sharedHealth.bpmValid = sharedHealth.bpm != 0;
    portEXIT_CRITICAL(&stateLock);
  }
}

void fromScreen(pl::Frame &f) {
  lastScreenAt = millis();
  if (f.h.type == pl::Health && !f.h.length) {
    if (screenBoot && f.h.boot != screenBoot && busy) {
      abortRequested = true; recovering = true;
    }
    screenBoot = f.h.boot;
  }
  if (recovering) return;
  if (f.h.type == pl::Ack && !f.h.length && waiting && f.h.boot == boot &&
      pl::sameSession(f.h, pending.h) && f.h.sequence == pending.h.sequence) {
    const uint8_t type = pending.h.type; waiting = false;
    if (type == pl::Begin) {
      pl::Frame start = pending; start.h.type = pl::Start; xQueueSend(commands, &start, 0);
    } else if (type == pl::End) busy = false;
  } else if (f.h.type == pl::Start && !busy) {
    pl::StartInfo info;
    if (!pl::get(f, info) || info.benchOnly > 1 || !info.seconds || info.seconds > 90 || !pl::nonzeroSession(f.h)) return;
    pl::Frame begin = pl::frame(pl::Begin, boot, 0, f.h.session); pl::put(begin, info);
    if (xQueueSend(records, &begin, 0) == pdTRUE) { busy = true; memcpy(activeSession, f.h.session, 16); }
  } else if (f.h.type == pl::Stop && !f.h.length && busy && memcmp(activeSession, f.h.session, 16) == 0) {
    xQueueSend(commands, &f, 0);
  }
}

void setup() {
  Serial.begin(115200); boot = pl::bootId();
  pinMode(3, OUTPUT); digitalWrite(3, HIGH); pinMode(5, INPUT); pinMode(4, INPUT);
  analogReadResolution(12); analogSetPinAttenuation(0, ADC_11db);
  screen.setRxBufferSize(4096); screen.setTxBufferSize(2048);
  screen.begin(115200, SERIAL_8N1, 6, 7);
  records = xQueueCreate(128, sizeof(pl::Frame)); commands = xQueueCreate(8, sizeof(pl::Frame));
  configASSERT(records && commands);
  const BaseType_t taskCreated = xTaskCreate(acquire, "ECG acquisition", 4096, nullptr, 4, nullptr);
  if (taskCreated != pdPASS) abort();
}

void loop() {
  pl::Frame f;
  for (int budget = 0; budget < 1024 && screen.available(); ++budget)
    if (screenParser.feed(screen.read(), f)) fromScreen(f);
  if (recovering && !abortRequested) { recovering = waiting = busy = false; }
  if (!recovering && !waiting && xQueueReceive(records, &pending, 0) == pdTRUE) { waiting = true; sentAt = millis() - 300; }
  if (!recovering && waiting && millis() - sentAt >= 300 && pl::send(screen, pending)) sentAt = millis();
  if (millis() - lastHealthAt >= 1000) {
    lastHealthAt = millis(); pl::HealthInfo info;
    portENTER_CRITICAL(&stateLock); info = sharedHealth; portEXIT_CRITICAL(&stateLock);
    f = pl::frame(pl::Health, boot); pl::put(f, info); pl::send(screen, f);
    char log[160];
    snprintf(log, sizeof(log), "PL6_C3 recording=%u captured=%lu slots=%lu missed=%lu late_us=%lu queue=%u\n",
      info.recording, (unsigned long)info.captured, (unsigned long)info.slots,
      (unsigned long)info.missedSlots, (unsigned long)info.maxLatenessUs, info.queueDepth);
    pl::debug(log);
  }
  delay(1);
}
