// Raw C3 input and UART bench verification; does not record a patient session.
// Build: Arduino ESP32 core 3.3.12, ESP32C3 Dev Module, USB CDC On Boot enabled.
// UART pin routing: https://docs.espressif.com/projects/arduino-esp32/en/latest/api/serial.html
#include <Arduino.h>
#include <stdarg.h>

HardwareSerial screenLink(1);
constexpr int LINK_RX = 6;
constexpr int LINK_TX = 7;
constexpr int ECG_INPUT = 0;
constexpr int LEAD_PLUS = 5;
constexpr int LEAD_MINUS = 4;
constexpr int SENSOR_ENABLE = 3;
char lineBuffer[96];
size_t lineLength = 0;
bool droppingLine = false;
uint32_t statusSequence = 0;
uint32_t lastStatus = 0;
uint32_t commandsReceived = 0;

void debugLog(const char *format, ...) {
  // A closed USB monitor must never stall the board-to-board link.
  char text[112];
  va_list args;
  va_start(args, format);
  const int length = vsnprintf(text, sizeof(text), format, args);
  va_end(args);
  if (length > 0 && length < static_cast<int>(sizeof(text)) && Serial.availableForWrite() >= length) {
    Serial.write(reinterpret_cast<const uint8_t *>(text), length);
  }
}

void receiveCommands() {
  // Bound work so an incoming stream cannot monopolise the main loop.
  for (int budget = 0; budget < 192 && screenLink.available(); ++budget) {
    const char ch = static_cast<char>(screenLink.read());
    if (ch == '\r') continue;
    if (ch == '\n') {
      if (!droppingLine) {
        lineBuffer[lineLength] = '\0';
        unsigned long sequence = 0;
        char extra = 0;
        if (sscanf(lineBuffer, "PL3,CMD,%lu%c", &sequence, &extra) == 1) {
          ++commandsReceived;
          screenLink.printf("PL3,ACK,%lu\n", sequence);
          debugLog("ACK command=%lu total=%lu\n", sequence,
                        static_cast<unsigned long>(commandsReceived));
        }
      }
      lineLength = 0;
      droppingLine = false;
    } else if (!droppingLine) {
      if (lineLength < sizeof(lineBuffer) - 1) lineBuffer[lineLength++] = ch;
      else droppingLine = true;
    }
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(SENSOR_ENABLE, OUTPUT);
  digitalWrite(SENSOR_ENABLE, HIGH);
  // AD8232 lead-off outputs drive these inputs. Do not mask an open wire with a pull-down.
  pinMode(LEAD_PLUS, INPUT);
  pinMode(LEAD_MINUS, INPUT);
  analogReadResolution(12);
  analogSetPinAttenuation(ECG_INPUT, ADC_11db);
  screenLink.begin(115200, SERIAL_8N1, LINK_RX, LINK_TX);
  debugLog("PulseLink C3: bench input checks only; no ECG/BPM calculation.\n");
}

void loop() {
  receiveCommands();
  const uint32_t now = millis();
  if (now - lastStatus >= 1000) {
    lastStatus = now;
    const int raw = analogRead(ECG_INPUT);
    const int plus = digitalRead(LEAD_PLUS);
    const int minus = digitalRead(LEAD_MINUS);
    screenLink.printf("PL3,STATUS,%lu,%d,%d,%d\n",
                      static_cast<unsigned long>(++statusSequence), raw, plus, minus);
    debugLog("STATUS seq=%lu ADC=%d LO+=%d LO-=%d commands=%lu\n",
                  static_cast<unsigned long>(statusSequence), raw, plus, minus,
                  static_cast<unsigned long>(commandsReceived));
  }
  delay(1);
}
