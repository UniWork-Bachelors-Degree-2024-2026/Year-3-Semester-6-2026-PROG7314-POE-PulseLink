// UART, display, raw touch and microSD bench checks only.
// No invented waveform, BPM, patient registration, Wi-Fi or cloud operations.
// Build: Arduino ESP32 core 3.3.12, LovyanGFX 1.2.36, ESP32 Dev Module (4MB, DIO).
#include <Arduino.h>
#include <SPI.h>
#include <SD.h>
#include <esp_system.h>
#define LGFX_USE_V1
#include <LovyanGFX.hpp>

// ESP32-2432S028R reference schematic. Check the physical board revision before upload.
// https://www.ardboard.com/downloads/ESP32_2432S028R_Schematics.pdf
class PulseLinkDisplay : public lgfx::LGFX_Device {
  lgfx::Panel_ILI9341 panel;
  lgfx::Bus_SPI displayBus;
public:
  PulseLinkDisplay() {
    auto bus = displayBus.config();
    bus.spi_host = SPI2_HOST;
    bus.spi_mode = 0;
    bus.freq_write = 20000000;
    bus.freq_read = 10000000;
    bus.spi_3wire = false;
    bus.use_lock = true;
    bus.dma_channel = SPI_DMA_CH_AUTO;
    bus.pin_sclk = 14;
    bus.pin_mosi = 13;
    bus.pin_miso = 12;
    bus.pin_dc = 2;
    displayBus.config(bus);
    panel.setBus(&displayBus);
    auto cfg = panel.config();
    cfg.pin_cs = 15;
    cfg.pin_rst = -1;
    cfg.pin_busy = -1;
    cfg.panel_width = 240;
    cfg.panel_height = 320;
    cfg.readable = true;
    cfg.invert = false;
    cfg.rgb_order = false;
    cfg.bus_shared = false;
    panel.config(cfg);
    setPanel(&panel);
  }
};

PulseLinkDisplay display;
HardwareSerial c3Link(2);
SPIClass cardBus(VSPI);
constexpr int LINK_RX = 35, LINK_TX = 22, BACKLIGHT = 21;
constexpr int TOUCH_CLK = 25, TOUCH_MOSI = 32, TOUCH_MISO = 39;
constexpr int TOUCH_CS = 33, TOUCH_IRQ = 36;
constexpr uint16_t NAVY = 0x08A5, SURFACE = 0x1947, WHITE = 0xFFFF;
constexpr uint16_t MUTED = 0x94B2, TEAL = 0x0CB1, GREEN = 0x15D3;
char lineBuffer[96];
size_t lineLength = 0;
bool droppingLine = false;
uint32_t statusAt = 0, ackAt = 0, lastPingAt = 0, lastUiAt = 0;
uint32_t pingSequence = 0, pendingPing = 0, received = 0, invalid = 0, gaps = 0;
uint32_t lastSequence = 0, touchCount = 0, lastTouchAt = 0;
int adcValue = -1, leadPlus = -1, leadMinus = -1, touchX = 0, touchY = 0;
bool pending = false, timedOut = false;
const char *cardResult = "NOT CHECKED";

// Software SPI for XPT2046 avoids reassigning the hardware SPI bus used by microSD.
// XPT2046 command layout: https://github.com/PaulStoffregen/XPT2046_Touchscreen/blob/master/XPT2046_Touchscreen.cpp
uint16_t readTouchAxis(uint8_t command) {
  digitalWrite(TOUCH_CS, LOW);
  for (int bit = 7; bit >= 0; --bit) {
    digitalWrite(TOUCH_MOSI, (command >> bit) & 1);
    digitalWrite(TOUCH_CLK, HIGH);
    delayMicroseconds(2);
    digitalWrite(TOUCH_CLK, LOW);
  }
  delayMicroseconds(5);
  uint16_t value = 0;
  for (int bit = 0; bit < 16; ++bit) {
    digitalWrite(TOUCH_CLK, HIGH);
    delayMicroseconds(2);
    value = (value << 1) | digitalRead(TOUCH_MISO);
    digitalWrite(TOUCH_CLK, LOW);
    delayMicroseconds(2);
  }
  digitalWrite(TOUCH_CS, HIGH);
  return (value >> 3) & 0x0FFF;
}

void checkCard() {
  cardBus.begin(18, 19, 23, 5);
  if (!SD.begin(5, cardBus, 10000000) || SD.cardType() == CARD_NONE) {
    cardResult = "MISSING / FAILED";
    return;
  }
  // Never overwrite an existing file or format the card. Remove only this probe file.
  char path[48];
  snprintf(path, sizeof(path), "/pulselink-check-%08lx.tmp",
           static_cast<unsigned long>(esp_random()));
  if (SD.exists(path)) { cardResult = "PROBE COLLISION"; return; }
  const char marker[] = "PulseLink card verification\n";
  File file = SD.open(path, FILE_WRITE);
  if (!file) { cardResult = "WRITE FAILED"; return; }
  const size_t written = file.write(reinterpret_cast<const uint8_t *>(marker), sizeof(marker) - 1);
  file.close();
  file = SD.open(path, FILE_READ);
  char actual[sizeof(marker)] = {};
  const size_t count = file ? file.readBytes(actual, sizeof(marker) - 1) : 0;
  if (file) file.close();
  const bool correct = written == sizeof(marker) - 1 && count == written &&
                       memcmp(marker, actual, written) == 0;
  const bool removed = SD.remove(path);
  cardResult = correct && removed ? "READ/WRITE PASS" : "CHECK FAILED";
}

void sendPing() {
  if (pending) return;
  pendingPing = ++pingSequence;
  pending = true;
  timedOut = false;
  lastPingAt = millis();
  c3Link.printf("PL3,CMD,%lu\n", static_cast<unsigned long>(pendingPing));
  Serial.printf("TX command=%lu\n", static_cast<unsigned long>(pendingPing));
}

void parseLine() {
  unsigned long sequence = 0;
  int raw = 0, plus = 0, minus = 0;
  char extra = 0;
  if (sscanf(lineBuffer, "PL3,STATUS,%lu,%d,%d,%d%c", &sequence, &raw, &plus, &minus, &extra) == 4 &&
      raw >= 0 && raw <= 4095 && plus >= 0 && plus <= 1 && minus >= 0 && minus <= 1) {
    if (received && sequence != lastSequence + 1) ++gaps;
    lastSequence = sequence;
    adcValue = raw; leadPlus = plus; leadMinus = minus;
    statusAt = millis(); ++received;
    Serial.printf("RX status=%lu ADC=%d LO+=%d LO-=%d\n", sequence, raw, plus, minus);
  } else if (sscanf(lineBuffer, "PL3,ACK,%lu%c", &sequence, &extra) == 1 &&
             pending && sequence == pendingPing) {
    ackAt = millis(); pending = false;
    Serial.printf("RX matching ACK=%lu\n", sequence);
  } else ++invalid;
}

void receiveLines() {
  for (int budget = 0; budget < 256 && c3Link.available(); ++budget) {
    const char ch = static_cast<char>(c3Link.read());
    if (ch == '\r') continue;
    if (ch == '\n') {
      if (!droppingLine) { lineBuffer[lineLength] = '\0'; parseLine(); }
      else ++invalid;
      lineLength = 0; droppingLine = false;
    } else if (!droppingLine) {
      if (lineLength < sizeof(lineBuffer) - 1) lineBuffer[lineLength++] = ch;
      else droppingLine = true;
    }
  }
}

void drawHeader() {
  display.fillScreen(NAVY);
  // Recognisable heart/pulse motif and colours from the Android PulseLink emblem.
  display.fillCircle(15, 15, 5, WHITE);
  display.fillCircle(23, 15, 5, WHITE);
  display.fillTriangle(10, 17, 28, 17, 19, 28, WHITE);
  display.drawLine(12, 19, 16, 19, TEAL);
  display.drawLine(16, 19, 18, 15, TEAL);
  display.drawLine(18, 15, 21, 23, TEAL);
  display.drawLine(21, 23, 23, 19, TEAL);
  display.drawLine(23, 19, 27, 19, TEAL);
  display.setTextColor(WHITE, NAVY);
  display.setTextSize(2); display.setCursor(36, 10); display.print("PulseLink");
  display.setTextSize(1); display.setCursor(207, 16); display.print("HARDWARE CHECK");
  display.drawFastHLine(8, 35, 304, TEAL);
  display.drawRoundRect(8, 43, 210, 82, 4, MUTED);
  display.setCursor(17, 51); display.print("ECG");
  display.setCursor(21, 77); display.print("Not recording");
  display.setCursor(21, 95); display.print("Wi-Fi: not configured");
  display.drawRoundRect(225, 43, 87, 82, 4, TEAL);
  display.setCursor(238, 53); display.print("BPM");
  display.setTextSize(3); display.setCursor(246, 76); display.print("--");
  display.setTextSize(1);
  display.fillRoundRect(8, 211, 304, 23, 4, TEAL);
  display.setTextColor(WHITE, TEAL); display.setCursor(38, 219);
  display.print("Tap anywhere to check serial link");
}

void drawStatus() {
  const uint32_t now = millis();
  const bool freshStatus = received && now - statusAt < 3000;
  display.fillRect(8, 133, 304, 72, NAVY);
  display.setTextColor(WHITE, NAVY); display.setTextSize(1);
  display.setCursor(8, 134);
  display.printf("C3: %s  Return: %s", freshStatus ? "ONLINE" : "WAITING",
                 pending ? "WAIT" : timedOut ? "TIMEOUT" : ackAt ? "ACK OK" : "WAITING");
  display.setCursor(8, 148); display.printf("SD: %s", cardResult);
  display.setCursor(8, 162);
  display.printf("Leads connected: %s", !freshStatus ? "UNKNOWN" :
                 (leadPlus == 0 && leadMinus == 0) ? "YES" : "NO");
  display.setCursor(8, 176); display.printf("RX:%lu Gaps:%lu Bad:%lu", (unsigned long)received,
                 (unsigned long)gaps, (unsigned long)invalid);
  display.setCursor(8, 190); display.printf("Touches:%lu Raw X:%d Y:%d", (unsigned long)touchCount, touchX, touchY);
}

void setup() {
  Serial.begin(115200);
  // GPIO35 has no internal pull resistor. The powered C3 TX drives the idle level.
  c3Link.enableRxInternalPull(false);
  c3Link.begin(115200, SERIAL_8N1, LINK_RX, LINK_TX);
  pinMode(TOUCH_CS, OUTPUT); digitalWrite(TOUCH_CS, HIGH);
  pinMode(TOUCH_CLK, OUTPUT); digitalWrite(TOUCH_CLK, LOW);
  pinMode(TOUCH_MOSI, OUTPUT); pinMode(TOUCH_MISO, INPUT); pinMode(TOUCH_IRQ, INPUT);
  pinMode(5, OUTPUT); digitalWrite(5, HIGH);
  display.init(); display.setRotation(1);
  pinMode(BACKLIGHT, OUTPUT); digitalWrite(BACKLIGHT, HIGH);
  checkCard(); drawHeader(); drawStatus();
  Serial.printf("PulseLink screen; SD=%s; no acquisition or cloud writes.\n", cardResult);
}

void loop() {
  receiveLines();
  const uint32_t now = millis();
  if (pending && now - lastPingAt >= 2000) { pending = false; timedOut = true; }
  if (now - lastPingAt >= 3000) sendPing();
  if (digitalRead(TOUCH_IRQ) == LOW && now - lastTouchAt >= 400) {
    const int x = readTouchAxis(0xD0), y = readTouchAxis(0x90);
    if (x > 0 && x < 4095 && y > 0 && y < 4095) {
      touchX = x; touchY = y; ++touchCount; lastTouchAt = now;
      Serial.printf("TOUCH raw X=%d Y=%d\n", x, y); sendPing();
    }
  }
  if (now - lastUiAt >= 500) { lastUiAt = now; drawStatus(); }
  delay(1);
}
