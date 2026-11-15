#pragma once
#define LGFX_USE_V1
#include <LovyanGFX.hpp>

namespace pl {
namespace ui {
constexpr uint16_t NavyBg = 0x0863;
constexpr uint16_t CardBg = 0x1127;
constexpr uint16_t GridSlate = 0x1967;
constexpr uint16_t MintGreen = 0x074E;
constexpr uint16_t TealBtn = 0x0473;
constexpr uint16_t TealBorder = 0x0CB1;
constexpr uint16_t MutedText = 0x94B2;
constexpr uint16_t WarnRed = 0xEA28;
constexpr uint16_t WarnAmber = 0xFFE0;
constexpr uint16_t AmberBg = 0x20E0;
constexpr uint16_t AmberBorder = 0x7A80;
constexpr uint16_t White = 0xFFFF;
}
}

// The ILI9341 display uses a separate SPI bus from the microSD card.
class PulseLinkDisplay : public lgfx::LGFX_Device {
  lgfx::Panel_ILI9341 panel;
  lgfx::Bus_SPI bus;
public:
  // Measure and clip each label within its own region using uniform Font0 typography.
  void label(int x, int y, int width, int height, String value, uint16_t colour,
             bool heading = false, bool centred = false, int32_t bg = -1) {
    (void)heading;
    if (bg >= 0) fillRect(x, y, width, height, static_cast<uint16_t>(bg));
    setFont(&fonts::Font0); setTextWrap(false, false); setTextSize(1);
    if (textWidth(value) > width) {
      const int suffix = textWidth("...");
      while (value.length() && textWidth(value) + suffix > width) {
        unsigned end = value.length() - 1;
        while (end && (static_cast<uint8_t>(value[end]) & 0xc0) == 0x80) --end;
        value.remove(end);
      }
      value += "...";
    }
    setClipRect(x, y, width, height);
    setTextColor(colour);
    const int textY = height > 8 ? y + (height - 8) / 2 : y;
    setCursor(centred ? x + (width - textWidth(value)) / 2 : x, textY);
    print(value);
    clearClipRect(); setFont(&fonts::Font0); setTextSize(1);
  }

  void paragraph(int x, int y, int width, int height, const String &value, uint16_t colour,
                 int32_t bg = -1, unsigned maxLines = 3, int lineStep = 11) {
    if (bg >= 0) fillRect(x, y, width, height, static_cast<uint16_t>(bg));
    setFont(&fonts::Font0); setTextWrap(false, false); setTextSize(1);
    setClipRect(x, y, width, height);
    setTextColor(colour);
    unsigned pos = 0;
    const unsigned len = value.length();
    for (unsigned line = 0; line < maxLines && pos < len; ++line) {
      while (pos < len && (value[pos] == ' ' || value[pos] == '\r' || value[pos] == '\n')) ++pos;
      if (pos >= len) break;
      String current;
      unsigned scan = pos, lastBreak = pos;
      bool brokeAtWord = false;
      while (scan < len && value[scan] != '\n') {
        if (value[scan] == '\r') { ++scan; continue; }
        String candidate = value.substring(pos, scan + 1);
        if (textWidth(candidate) > width) break;
        current = candidate;
        if (value[scan] == ' ') { lastBreak = scan; brokeAtWord = true; }
        ++scan;
      }
      if (scan < len && value[scan] != '\n' && brokeAtWord && lastBreak > pos) {
        current = value.substring(pos, lastBreak);
        pos = lastBreak + 1;
      } else {
        if (!current.length()) { current = value.substring(pos, pos + 1); pos += 1; }
        else pos = scan;
        if (pos < len && value[pos] == '\n') ++pos;
      }
      while (pos < len && value[pos] == ' ') ++pos;
      if (line + 1 == maxLines && pos < len) {
        const int suffix = textWidth("...");
        while (current.length() && textWidth(current) + suffix > width) current.remove(current.length() - 1);
        current += "...";
      }
      setCursor(x, y + static_cast<int>(line) * lineStep);
      print(current);
    }
    clearClipRect(); setFont(&fonts::Font0); setTextSize(1);
  }

  PulseLinkDisplay() {
    auto b = bus.config();
    b.spi_host = SPI2_HOST; b.spi_mode = 0; b.freq_write = 27000000; b.freq_read = 10000000;
    b.spi_3wire = false; b.use_lock = true; b.dma_channel = SPI_DMA_CH_AUTO;
    b.pin_sclk = 14; b.pin_mosi = 13; b.pin_miso = 12; b.pin_dc = 2;
    bus.config(b); panel.setBus(&bus);
    auto p = panel.config();
    p.pin_cs = 15; p.pin_rst = -1; p.pin_busy = -1;
    p.panel_width = 240; p.panel_height = 320;
    p.readable = true; p.invert = false; p.rgb_order = false; p.bus_shared = false;
    panel.config(p); setPanel(&panel);
  }
};
