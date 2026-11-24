#pragma once
#include "PulseLinkNetwork.h"
#include "PulseLinkDisplay.h"

class PulseLinkWifiView {
  enum class Field { None, Ssid, Identity, Password, Api, Token };
  NetworkConfig draft;
  Field field = Field::None;
  String input;
  bool loaded = false, upper = false, symbols = false, apiPage = false, networks = false;
  bool networkKnown = false, selectedSecured = false;
  bool showSecret = false;
  const char *notice = "";
  constexpr static uint16_t Navy = pl::ui::NavyBg, Teal = pl::ui::TealBtn, White = pl::ui::White;
  static void button(PulseLinkDisplay &d, int x, int y, int w, const char *label, bool primary = true) {
    d.fillRoundRect(x, y, w, 24, 3, primary ? pl::ui::TealBtn : pl::ui::CardBg);
    d.drawRoundRect(x, y, w, 24, 3, primary ? pl::ui::MintGreen : pl::ui::TealBorder);
    d.label(x + 4, y + 6, w - 8, 12, label, pl::ui::White, false, true);
    d.setTextColor(pl::ui::White, pl::ui::NavyBg);
  }
  const char *row(unsigned y) const {
    static const char *normal[] = {"1234567890", "qwertyuiop", "asdfghjkl;", "zxcvbnm,./"};
    static const char *special[] = {"!@#$%^&*()", "-_=+[]{}<>", "\\|:;'\"?~`", ".,/0123456"};
    return symbols ? special[y] : normal[y];
  }
  void edit(Field target) {
    field = target; upper = symbols = showSecret = false;
    input = target == Field::Ssid ? draft.ssid : target == Field::Identity ? draft.identity :
      target == Field::Password ? draft.password : target == Field::Api ? draft.api : draft.token;
  }
public:
  bool editing() const { return field != Field::None; }
  uint8_t viewKey() const {
    return static_cast<uint8_t>(field) | (upper ? 8u : 0u) | (symbols ? 16u : 0u) |
           (apiPage ? 32u : 0u) | (networks ? 64u : 0u) | (showSecret ? 128u : 0u);
  }
  // True returns to the monitor; network and password values never enter debug logs.
  bool tap(int x, int y, PulseLinkNetwork &net, bool recording) {
    if (!loaded) { draft = net.settings(); loaded = true; }
    if (editing()) {
      if ((field == Field::Password || field == Field::Token) && x >= 236 && y >= 38 && y < 58) {
        showSecret = !showSecret; return false;
      }
      if (y >= 211) {
        if (x >= 160) {
          if (field == Field::Ssid) { strlcpy(draft.ssid, input.c_str(), sizeof(draft.ssid)); networkKnown = false; }
          else if (field == Field::Identity) strlcpy(draft.identity, input.c_str(), sizeof(draft.identity));
          else if (field == Field::Password) strlcpy(draft.password, input.c_str(), sizeof(draft.password));
          else if (field == Field::Api) {
            if (input != draft.api) draft.token[0] = 0;
            strlcpy(draft.api, input.c_str(), sizeof(draft.api));
          }
          else strlcpy(draft.token, input.c_str(), sizeof(draft.token));
          notice = "Field updated. Tap Connect or Save API.";
        }
        input = ""; showSecret = false; field = Field::None; return false;
      }
      if (y >= 180 && y < 204) {
        if (x < 80) upper = !upper;
        else if (x < 160) symbols = !symbols;
        else if (x < 236) {
          const size_t limit = field == Field::Ssid ? 32 : (field == Field::Password || field == Field::Identity) ? 64 : field == Field::Token ? 43 : 191;
          if (input.length() < limit) input += ' ';
        }
        else if (input.length()) input.remove(input.length() - 1);
      } else if (y >= 78 && y < 174) {
        const unsigned r = (y - 78) / 24, c = x / 32;
        if (r < 4 && c < strlen(row(r))) {
          const size_t limit = field == Field::Ssid ? 32 : (field == Field::Password || field == Field::Identity) ? 64 : field == Field::Token ? 43 : 191;
          if (input.length() < limit) { char value = row(r)[c]; input += upper ? static_cast<char>(toupper(value)) : value; }
        }
      }
      return false;
    }
    if (y >= 211) { if (x < 156) return true; apiPage = !apiPage; networks = false; return false; }
    if (recording) { notice = "Change settings after recording stops."; return false; }
    if (networks) {
      const auto state = net.view();
      if (y >= 68 && y < 200) {
        const int index = (y - 68) / 22;
        if (index < state.networkCount) {
          if (strcmp(draft.ssid, state.networks[index])) {
            draft.password[0] = 0;
            if (!pl::isEnterpriseSsid(state.networks[index], nullptr)) draft.identity[0] = 0;
          }
          strlcpy(draft.ssid, state.networks[index], 33); networkKnown = true; selectedSecured = state.secured[index];
          networks = false; notice = "";
          if (pl::isEnterpriseSsid(draft.ssid, nullptr) && !draft.identity[0]) edit(Field::Identity);
          else edit(Field::Password);
        }
      }
      return false;
    }
    if (apiPage) {
      if (y >= 67 && y < 91) edit(Field::Api);
      else if (y >= 95 && y < 119) edit(Field::Token);
      else if (y >= 126 && y < 152) {
        if (const char *error = net.configError(draft)) notice = error;
        else notice = net.configure(draft) ? "" : "Device is busy. Please try again.";
      }
      return false;
    }
    if (y >= 62 && y < 83) edit(Field::Ssid);
    else if (y >= 85 && y < 106) edit(Field::Identity);
    else if (y >= 108 && y < 129) edit(Field::Password);
    else if (y >= 132 && y < 156) {
      if (!apiPage && x < 106) {
        if (net.scan()) { networks = true; notice = ""; }
        else notice = "Scan unavailable. Please try again.";
      }
      else if (!apiPage && x >= 212) {
        draft.ssid[0] = draft.password[0] = draft.identity[0] = 0;
        networkKnown = false;
        notice = net.configure(draft) ? "" : "Could not forget network. Try again.";
      } else {
        if (!apiPage && !draft.ssid[0]) notice = "Select or enter a Wi-Fi network first.";
        else if (!apiPage && networkKnown && selectedSecured && !draft.password[0]) notice = "This network needs a Wi-Fi password.";
        else if (const char *error = net.configError(draft)) notice = error;
        else notice = net.configure(draft) ? "" : "Device is busy. Please try again.";
      }
    }
    return false;
  }
  void draw(PulseLinkDisplay &d, PulseLinkNetwork &net, bool fullRedraw = true) {
    if (!loaded) { draft = net.settings(); loaded = true; }
    d.setFont(&fonts::Font0); d.setTextSize(1); d.setTextColor(White, Navy);
    if (editing()) {
      d.label(8, 38, 168, 12, field == Field::Ssid ? "Network name" : field == Field::Identity ? "Username / Identity" :
        field == Field::Password ? "Wi-Fi password" : field == Field::Token ? "Device key" : "HTTPS API address",
        pl::ui::MintGreen, false, false, Navy);
      const bool secret = field == Field::Password || field == Field::Token;
      if (secret) {
        char counter[16];
        snprintf(counter, sizeof(counter), "%u/%u", static_cast<unsigned>(input.length()), field == Field::Token ? 43u : 64u);
        d.label(178, 38, 54, 12, counter, pl::ui::MutedText, false, true, Navy);
        if (fullRedraw) {
          d.fillRoundRect(236, 36, 76, 18, 3, pl::ui::CardBg);
          d.drawRoundRect(236, 36, 76, 18, 3, pl::ui::TealBorder);
          d.label(240, 40, 68, 10, showSecret ? "Hide" : "Show", White, false, true);
        }
      }
      d.fillRoundRect(8, 54, 304, 20, 3, pl::ui::CardBg);
      d.drawRoundRect(8, 54, 304, 20, 3, pl::ui::TealBorder);
      String shown;
      if (secret && !showSecret) { for (unsigned i = 0; i < min(input.length(), 46u); ++i) shown += '*'; }
      else shown = input.substring(input.length() > 47 ? input.length() - 47 : 0);
      d.label(14, 59, 292, 10, shown.length() ? shown : "_", White, false, false, pl::ui::CardBg);
      if (!fullRedraw) return;
      for (int r = 0; r < 4; ++r) for (unsigned c = 0; c < strlen(row(r)); ++c) {
        d.fillRoundRect(c * 32 + 1, 78 + r * 24, 30, 22, 2, pl::ui::CardBg);
        d.drawRoundRect(c * 32 + 1, 78 + r * 24, 30, 22, 2, pl::ui::GridSlate);
        d.setTextColor(White, pl::ui::CardBg); d.setCursor(c * 32 + 13, 85 + r * 24);
        char value = row(r)[c]; d.print(upper ? static_cast<char>(toupper(value)) : value);
      }
      button(d, 8, 180, 68, upper ? "lower" : "SHIFT", false); button(d, 84, 180, 68, symbols ? "ABC" : "Symbols", false);
      button(d, 160, 180, 68, "Space", false); button(d, 236, 180, 76, "Delete", false);
      button(d, 8, 211, 144, "Cancel", false); button(d, 160, 211, 152, "Save field", true); return;
    }
    const auto state = net.view();
    if (networks) {
      d.label(8, 63, 304, 12, state.scanning ? "Scanning 2.4 GHz networks..." : state.networkCount ? "Select a Wi-Fi network:" : "None found. Check 2.4 GHz Wi-Fi; tap Back.", pl::ui::MintGreen, false, false, Navy);
      for (int i = 0; i < 6; ++i) {
        const String item = i < state.networkCount ? String(state.networks[i]) : String("");
        d.label(8, 78 + i * 21, 304, 16, item, White, false, false, Navy);
      }
    } else if (apiPage) {
      if (fullRedraw) {
        d.fillRoundRect(8, 67, 304, 24, 3, pl::ui::CardBg);
        d.drawRoundRect(8, 67, 304, 24, 3, pl::ui::GridSlate);
        d.fillRoundRect(8, 95, 304, 24, 3, pl::ui::CardBg);
        d.drawRoundRect(8, 95, 304, 24, 3, pl::ui::GridSlate);
        button(d, 8, 126, 304, "Save API settings", true);
      }
      String visible = String("API: ") + String(draft.api).substring(0, 42);
      d.label(14, 74, 292, 10, visible, White, false, false, pl::ui::CardBg);
      String secretLine = String("Device key: ") + (draft.token[0] ? "********" : "not set");
      d.label(14, 102, 292, 10, secretLine, pl::ui::MutedText, false, false, pl::ui::CardBg);
      const char *message = notice[0] ? notice : draft.deviceId;
      d.label(8, 158, 304, 10, message, White, false, false, Navy);
      d.label(8, 169, 304, 10, "", pl::ui::MutedText, false, false, Navy);
    } else {
      if (fullRedraw) {
        d.fillRoundRect(8, 62, 304, 20, 3, pl::ui::CardBg);
        d.drawRoundRect(8, 62, 304, 20, 3, pl::ui::GridSlate);
        d.fillRoundRect(8, 85, 304, 20, 3, pl::ui::CardBg);
        d.drawRoundRect(8, 85, 304, 20, 3, pl::ui::GridSlate);
        d.fillRoundRect(8, 108, 304, 20, 3, pl::ui::CardBg);
        d.drawRoundRect(8, 108, 304, 20, 3, pl::ui::GridSlate);
        button(d, 8, 132, 96, "Scan", false); button(d, 112, 132, 96, "Connect", true); button(d, 216, 132, 96, "Forget", false);
      }
      d.label(14, 67, 292, 10, String("SSID: ") + String(draft.ssid).substring(0, 32), White, false, false, pl::ui::CardBg);
      String idLine = String("User/ID: ") + (draft.identity[0] ? String(draft.identity).substring(0, 34) : String("optional (required for eduroam)"));
      d.label(14, 90, 292, 10, idLine, draft.identity[0] ? White : pl::ui::MutedText, false, false, pl::ui::CardBg);
      String secretLine = String("Password: ") + (draft.password[0] ? "********" : "not set");
      d.label(14, 113, 292, 10, secretLine, pl::ui::MutedText, false, false, pl::ui::CardBg);
      const char *message = notice, *help = "";
      if (!notice[0]) {
        if (state.applying) message = "Applying settings...";
        else if (state.saveFailed) { message = "Could not save settings on this device."; help = "Try again before switching off."; }
        else if (state.wifi == WifiState::Connected) {
          message = state.settingsStored ? "Connected. Wi-Fi settings saved." : "Connected, but settings were not saved.";
          help = state.settingsStored ? "Reconnects automatically after power on." : "Try Connect again before switching off.";
        } else if (state.issue != WifiIssue::None) {
          switch (state.issue) {
            case WifiIssue::Credentials: message = "Wi-Fi login rejected. Check credentials."; help = "Edit User/ID or Password, Save field, then Connect."; break;
            case WifiIssue::Handshake: message = "Could not join. Check password and signal."; help = "Move closer or correct the password."; break;
            case WifiIssue::NotFound: message = "Saved network not found. Retrying."; help = "Turn on Wi-Fi/hotspot or move closer."; break;
            case WifiIssue::Security: message = "Network security mode rejected."; help = "For eduroam set User/ID + Password."; break;
            case WifiIssue::Signal: message = "Wi-Fi signal is too weak. Retrying."; help = "Move closer to the router or hotspot."; break;
            case WifiIssue::Scan: message = "Wi-Fi scan failed. Please scan again."; break;
            case WifiIssue::Timeout: message = "Connection timed out. Retrying."; help = "Check network, User/ID, password and signal."; break;
            default: message = "Wi-Fi connection lost. Reconnecting."; help = "Recording stays on the microSD card."; break;
          }
        } else message = state.wifi == WifiState::Connecting ? "Connecting to your saved network..." : "Select a network and enter its credentials.";
      }
      d.label(8, 158, 304, 10, message, White, false, false, Navy);
      d.label(8, 169, 304, 10, help, pl::ui::MutedText, false, false, Navy);
    }
    if (!networks) {
      const String wifiLine = String("Wi-Fi: ") + (state.wifi == WifiState::Connected ? "CONNECTED" : state.wifi == WifiState::Connecting ? "CONNECTING" :
        state.wifi == WifiState::Disconnected ? "DISCONNECTED" : "NOT CONFIGURED");
      d.label(8, 181, 304, 10, wifiLine, state.wifi == WifiState::Connected ? pl::ui::MintGreen : pl::ui::WarnAmber, false, false, Navy);
      const String apiLine = String("API: ") + (state.api == ApiState::Ready ? "AUTHENTICATED (HTTPS 200)" : state.api == ApiState::Unauthorised ? "KEY REJECTED - CHECK API SETTINGS" :
        state.api == ApiState::WaitingForClock ? "WAITING FOR NETWORK TIME" : state.api == ApiState::Unavailable ? "UNREACHABLE - WILL RETRY" :
        state.api == ApiState::WaitingForWifi ? "WAITING FOR WI-FI" : "NOT SET UP - DEVICE KEY REQUIRED");
      d.label(8, 194, 304, 10, apiLine, state.api == ApiState::Ready ? pl::ui::MintGreen : state.api == ApiState::Unauthorised ? pl::ui::WarnRed : pl::ui::WarnAmber, false, false, Navy);
    }
    if (fullRedraw) {
      button(d, 8, 211, 144, "Back", false); button(d, 160, 211, 152, apiPage ? "Wi-Fi settings" : "API settings", true);
    }
  }
};
