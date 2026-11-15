#pragma once
#include "PulseLinkDisplay.h"
class PulseLinkTextEntry {
  String value;
  const char *title = "";
  unsigned limit = 80;
  bool active_ = false, upper = false, symbols = false;
  const char *row(unsigned n) const {
    static const char *letters[] = {"1234567890", "qwertyuiop", "asdfghjkl;", "zxcvbnm,./"};
    static const char *marks[] = {"!@#$%^&*()", "-_=+[]{}<>", "\\|:;'\"?~`", ".,/0123456"};
    return symbols ? marks[n] : letters[n];
  }
public:
  bool active() const { return active_; }
  uint8_t viewKey() const { return (active_ ? 1u : 0u) | (upper ? 2u : 0u) | (symbols ? 4u : 0u); }
  void clear() { active_ = false; value = ""; title = ""; }
  void begin(const char *label, const char *initial, unsigned maximum) { title = label; value = initial; limit = maximum; active_ = true; upper = symbols = false; }
  const String &text() const { return value; }
  int tap(int x, int y) {
    if (y >= 208) { active_ = false; return x < 156 ? -1 : 1; }
    if (y >= 178 && y < 204) {
      if (x < 80) upper = !upper;
      else if (x < 160) symbols = !symbols;
      else if (x < 236) { if (value.length() < limit) value += ' '; }
      else if (value.length()) value.remove(value.length() - 1);
    } else if (y >= 78 && y < 174) {
      const unsigned r = (y - 78) / 24, c = x / 32;
      if (r < 4 && c < strlen(row(r)) && value.length() < limit) {
        char ch = row(r)[c]; value += upper ? static_cast<char>(toupper(ch)) : ch;
      }
    }
    return 0;
  }
  void draw(PulseLinkDisplay &d, bool fullRedraw = true) {
    d.setFont(&fonts::Font0); d.setTextSize(1);
    d.label(8, 38, 304, 12, title, pl::ui::MintGreen, false, false, pl::ui::NavyBg);
    d.fillRoundRect(8, 52, 304, 22, 3, pl::ui::CardBg);
    d.drawRoundRect(8, 52, 304, 22, 3, pl::ui::TealBorder);
    const String shown = value.substring(value.length() > 47 ? value.length() - 47 : 0);
    d.label(14, 58, 292, 10, shown.length() ? shown : "_", pl::ui::White, false, false, pl::ui::CardBg);
    if (!fullRedraw) return;
    for (unsigned r = 0; r < 4; ++r) for (unsigned c = 0; c < strlen(row(r)); ++c) {
      d.fillRoundRect(c * 32 + 1, 78 + r * 24, 30, 22, 2, pl::ui::CardBg);
      d.drawRoundRect(c * 32 + 1, 78 + r * 24, 30, 22, 2, pl::ui::GridSlate);
      d.setTextColor(pl::ui::White, pl::ui::CardBg); d.setCursor(c * 32 + 13, 85 + r * 24);
      char ch = row(r)[c]; d.print(upper ? static_cast<char>(toupper(ch)) : ch);
    }
    const char *labels[] = {upper ? "lower" : "SHIFT", symbols ? "ABC" : "Symbols", "Space", "Delete"};
    for (int i = 0; i < 4; ++i) {
      d.fillRoundRect(8 + i * 78, 179, 72, 24, 3, pl::ui::CardBg);
      d.drawRoundRect(8 + i * 78, 179, 72, 24, 3, pl::ui::TealBorder);
      d.label(10 + i * 78, 185, 68, 12, labels[i], pl::ui::White, false, true);
    }
    d.fillRoundRect(8, 208, 146, 26, 4, pl::ui::CardBg);
    d.drawRoundRect(8, 208, 146, 26, 4, pl::ui::GridSlate);
    d.label(12, 215, 138, 12, "Cancel", pl::ui::White, false, true);
    d.fillRoundRect(160, 208, 152, 26, 4, pl::ui::TealBtn);
    d.drawRoundRect(160, 208, 152, 26, 4, pl::ui::MintGreen);
    d.label(164, 215, 144, 12, "Save field", pl::ui::White, false, true);
  }
};
