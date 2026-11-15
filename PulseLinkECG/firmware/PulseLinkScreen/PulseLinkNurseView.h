#pragma once
#include "PulseLinkNurseFlow.h"

inline bool PulseLinkNurseFlow::tap(int x, int y, bool recording, bool c3Fresh, uint8_t leadFlags, bool cardReady) {
  lastTouch = millis();
  if (entry.active()) {
    const int done = entry.tap(x, y);
    if (done == 1) {
      if (editField == Edit::Name) { String name = entry.text(); name.trim(); strlcpy(draftName, name.c_str(), sizeof(draftName)); }
      else if (editField == Edit::Dob) strlcpy(draftDob, entry.text().c_str(), sizeof(draftDob));
      else strlcpy(search, entry.text().c_str(), sizeof(search));
    }
    return false;
  }
  if (recording && page != Page::Updates) return false;
  if (page == Page::Welcome) {
    if (y >= 66 && y < 98) {
      if (busy()) { notice = "Saving or loading. Please wait."; return false; }
      if (net.view().api != ApiState::Ready) notice = "Connect Wi-Fi and authenticate the API first.";
      else { checkedServer = false; send(Job::Code, "/api/device-workflow/authorisation", true, "{}"); }
    } else if (y >= 204) {
      if (x >= 160) return true;
      benchRequested = true; page = Page::Monitor;
    }
    return false;
  }
  if (page == Page::Updates && recording) {
    if (y >= 166 && y < 200) {
      if (x >= 158) {
        if (!busy() && eventId[0] && !eventReceived) send(Job::Receipt, String("/api/device-transport/events/") + eventId + "/received", true, "{}");
        else if (!busy() && net.view().api == ApiState::Ready) { net.clearPendingEvents(); send(Job::Updates, "/api/device-transport/events", false); }
      } else {
        returnToMonitor = false; page = Page::Monitor;
      }
    } else if (y >= 204 && x < 160) {
      returnToMonitor = false; page = Page::Monitor;
    }
    return false;
  }
  if (page == Page::Home) {
    if (y >= 204) {
      if (x >= 212) return true;
      if (busy()) { notice = "Saving or loading. Please wait."; return false; }
      if (x < 116) {
        returnToMonitor = false; page = Page::Updates;
        if (net.view().api == ApiState::Ready) { net.clearPendingEvents(); send(Job::Updates, "/api/device-transport/events", false); }
      } else {
        persistLock(true);
        if (net.view().api == ApiState::Ready) send(Job::Logout, "/api/device-workflow/authorisation", false, "", true);
        lock(); notice = "Nurse signed out. Connect to approve again.";
        checkedServer = true;
      }
      return false;
    }
    if (busy()) { notice = "Saving or loading. Please wait."; return false; }
    if (!canWork()) { lock(); return false; }
    if (y >= 54 && y < 128) {
      if (x < 156) { patientAfter = doctorAfter = ""; page = Page::Patients; clinic(); }
      else { repairingAssignment = false; draftName[0] = draftDob[0] = 0; page = Page::Register; clinic(); }
    }
    return false;
  }
  if (page == Page::Patients) {
    if (y >= 204) {
      if (x >= 210) return true;
      if (busy()) { notice = "Saving or loading. Please wait."; return false; }
      if (x < 90) page = Page::Home;
      else { repairingAssignment = false; draftName[0] = draftDob[0] = 0; page = Page::Register; clinic(); }
      return false;
    }
    if (busy()) { notice = "Saving or loading. Please wait."; return false; }
    if (!canWork()) { lock(); return false; }
    if (y >= 52 && y < 80) { editField = Edit::Search; entry.begin("Search clinic names or patient IDs", search, 40); }
    else if (y >= 82 && y < 174) {
      const unsigned row = (y - 82) / 46;
      if ((y - 82) % 46 < 42) {
        String term(search); term.toLowerCase();
        unsigned visible = 0;
        for (unsigned index = 0; index < patientCount; ++index) {
          String name(patients[index].name), id(patients[index].id); name.toLowerCase(); id.toLowerCase();
          if (term.length() && name.indexOf(term) < 0 && id.indexOf(term) < 0) continue;
          if (visible++ != row) continue;
          selected = patients[index]; repairingAssignment = false; placementChecked = false; page = Page::Confirm;
          notice = "Confirm patient details and clinician.";
          break;
        }
      }
    } else if (y >= 176 && y < 202) {
      if (x < 156) { patientAfter = ""; clinic(); }
      else if (nextPatient.length()) { patientAfter = nextPatient; clinic(); }
    }
    return false;
  }
  if (y >= 204 && x >= 160) return true;
  if (busy()) { notice = "Saving or loading. Please wait."; return false; }
  if (!canWork() && page != Page::Complete) { lock(); return false; }
  if (y >= 204 && x < 160) {
    if (x >= 80) { returnToMonitor = false; page = Page::Home; return false; }
    switch (page) {
      case Page::Doctors: page = repairingAssignment ? Page::Confirm : Page::Register; break;
      case Page::AssignConfirm: page = Page::Doctors; break;
      case Page::Confirm: page = Page::Patients; break;
      case Page::Guide: page = Page::Confirm; break;
      case Page::Leads: page = Page::Guide; break;
      case Page::Updates: page = returnToMonitor ? Page::Monitor : Page::Home; returnToMonitor = false; break;
      default: page = Page::Home; break;
    }
    return false;
  }
  if (page == Page::Register) {
    if (y >= 54 && y < 86) { editField = Edit::Name; entry.begin("Patient full name", draftName, 80); }
    else if (y >= 90 && y < 122) { editField = Edit::Dob; entry.begin("Date of birth YYYY-MM-DD, or leave blank", draftDob, 10); }
    else if (y >= 166 && y < 200) {
      if (strlen(draftName) < 2 || !validDob(draftDob)) notice = "Enter a full name and valid optional birth date.";
      else { page = Page::Doctors; notice = "Select the attending clinician."; }
    }
  } else if (page == Page::Doctors) {
    if (y >= 54 && y < 146) {
      const unsigned index = (y - 54) / 46;
      if (index < doctorCount && (y - 54) % 46 < 42) {
        doctorSelected = index;
        if (repairingAssignment) page = Page::AssignConfirm;
        else saveLocalPatient();
      }
    } else if (y >= 150 && y < 178 && nextDoctor.length()) { doctorAfter = nextDoctor; clinic(); }
  } else if (page == Page::AssignConfirm) {
    if (y >= 164 && y < 198) assignDoctor();
  } else if (page == Page::Confirm) {
    if (y >= 164 && y < 198) {
      if (selected.requiresDoctorAssignment || !selected.doctor[0]) {
        if (net.view().api != ApiState::Ready) notice = "Reconnect to assign this patient's clinician.";
        else { repairingAssignment = true; doctorAfter = ""; page = Page::Doctors; clinic(); }
      } else { placementChecked = false; page = Page::Guide; notice = "Match R, L and COM pads to the guide."; }
    }
  } else if (page == Page::Guide) {
    if (y >= 168 && y < 200) { placementChecked = true; page = Page::Leads; notice = "Verify all 7 pre-recording checks."; }
  } else if (page == Page::Leads) {
    if (y >= 162 && y < 200) {
      const bool patientOk = selected.id[0] && selected.version;
      const bool doctorOk = selected.doctor[0] && !selected.requiresDoctorAssignment;
      const bool shiftOk = canWork(90000);
      const bool uartOk = c3Fresh;
      const bool sdOk = cardReady;
      const bool leadsOk = c3Fresh && !leadFlags;
      if (!patientOk) notice = "Select and confirm a registered patient first.";
      else if (!doctorOk) notice = "Assign an approved clinician before recording.";
      else if (!shiftOk) notice = "Approve nurse shift with at least 90s remaining.";
      else if (!placementChecked) notice = "Review the R, L and COM pad guide first.";
      else if (!uartOk) notice = "C3 UART unavailable. Check the wired link.";
      else if (!sdOk) notice = "Insert a working microSD card first.";
      else if (!leadsOk) notice = "Check R, L and COM contacts. Lead-off detected.";
      else prepare();
    }
  } else if (page == Page::Complete) {
    if (y >= 162 && y < 196) { page = Page::Home; selected = Patient{}; }
  } else if (page == Page::Updates && y >= 166 && y < 200) {
    if (x >= 158) {
      if (eventId[0] && !eventReceived) send(Job::Receipt, String("/api/device-transport/events/") + eventId + "/received", true, "{}");
      else if (net.view().api == ApiState::Ready) { net.clearPendingEvents(); send(Job::Updates, "/api/device-transport/events", false); }
    } else {
      page = returnToMonitor ? Page::Monitor : Page::Home;
      returnToMonitor = false;
    }
  }
  return false;
}

inline void PulseLinkNurseFlow::draw(PulseLinkDisplay &d, bool c3Fresh, uint8_t leadFlags, bool cardReady, const char *uploadStatus, bool fullRedraw) {
  using namespace pl::ui;
  d.setFont(&fonts::Font0); d.setTextSize(1); d.setTextColor(White, NavyBg);
  auto line = [&](int x, int y, int w, const String &value, uint16_t fg = White, int32_t bg = NavyBg, bool centred = false) {
    d.label(x, y, w, 10, value, fg, false, centred, bg);
  };
  auto btn = [&](int x, int y, int w, int h, const char *caption, bool primary = true) {
    const uint16_t fill = primary ? TealBtn : CardBg;
    const uint16_t border = primary ? MintGreen : TealBorder;
    d.fillRoundRect(x, y, w, h, 4, fill);
    d.drawRoundRect(x, y, w, h, 4, border);
    d.label(x + 4, y + 2, w - 8, h - 4, caption, White, false, true);
    d.setTextColor(White, NavyBg);
  };
  if (entry.active()) { entry.draw(d, fullRedraw); return; }

  if (page == Page::Welcome) {
    line(8, 38, 304, "NURSE ACCESS | Clinic touchscreen", MintGreen);
    line(8, 52, 304, String("Unit: ") + net.settings().deviceId, MutedText);
    btn(8, 68, 304, 28, busy() ? "Loading nurse access..." : code[0] ? "Request new approval code" : "Get nurse approval code", true);
    if (fullRedraw) {
      d.fillRoundRect(8, 102, 304, 30, 4, CardBg);
      d.drawRoundRect(8, 102, 304, 30, 4, GridSlate);
    }
    const bool validCode = code[0] && millis() - codeAt < 300000;
    d.fillRect(16, 107, 110, 20, CardBg);
    d.setFont(&fonts::Font0); d.setTextSize(2);
    d.setTextColor(validCode ? MintGreen : MutedText, CardBg);
    d.setCursor(18, 109); d.print(validCode ? code : "------");
    d.setTextSize(1);
    line(134, 112, 170, "Approve in nurse app", MutedText, CardBg);
    line(8, 138, 304, "One clinic site | 8-hour shift | Realtime DB sync", White);
    line(8, 153, 304, code[0] && millis() - codeAt >= 300000 ? "Code expired. Request a new code." : notice, WarnAmber);
    line(8, 168, 304, "Prototype ECG: uncalibrated 12-bit single-lead ADC", MutedText);
    line(8, 182, 304, net.view().api == ApiState::Ready ? "API status: HTTPS Ready" : "API status: Connect Wi-Fi & device key",
         net.view().api == ApiState::Ready ? MintGreen : WarnAmber);
    if (fullRedraw) {
      btn(8, 206, 146, 28, "Bench test only", false);
      btn(160, 206, 152, 28, "Connections", false);
    }
    return;
  }

  if (page == Page::Home) {
    const bool apiOk = net.view().api == ApiState::Ready;
    line(8, 38, 304, apiOk ? "ONLINE | Nurse shift authorised" : "OFFLINE | Cached shift permission active",
         apiOk ? MintGreen : WarnAmber);
    if (fullRedraw) {
      d.fillRoundRect(8, 54, 146, 72, 6, CardBg);
      d.drawRoundRect(8, 54, 146, 72, 6, TealBorder);
      d.drawCircle(81, 70, 5, MintGreen); d.drawRoundRect(73, 78, 16, 9, 3, MintGreen);
      d.label(16, 92, 130, 12, "Select Patient", White, false, true);
      d.label(16, 107, 130, 10, "From clinic queue", MutedText, false, true);

      d.fillRoundRect(160, 54, 152, 72, 6, TealBtn);
      d.drawRoundRect(160, 54, 152, 72, 6, MintGreen);
      d.drawCircle(232, 70, 5, White); d.drawRoundRect(224, 78, 16, 9, 3, White);
      d.drawFastHLine(244, 72, 8, White); d.drawFastVLine(248, 68, 8, White);
      d.label(168, 92, 136, 12, "Register Patient", White, false, true);
      d.label(168, 107, 136, 10, "New intake record", White, false, true);

      d.fillRoundRect(8, 132, 304, 26, 4, CardBg);
      d.drawRoundRect(8, 132, 304, 26, 4, GridSlate);
    }
    line(16, 140, 218, selected.id[0] ? String("Active: ") + selected.name : String(uploadStatus), White, CardBg);
    d.fillRoundRect(240, 137, 66, 16, 3, CardBg);
    d.drawRoundRect(240, 137, 66, 16, 3, cardReady ? MintGreen : WarnRed);
    line(243, 140, 60, cardReady ? "SD ready" : "SD check", cardReady ? MintGreen : WarnRed, CardBg, true);

    const bool uartOk = c3Fresh;
    const bool wifiOk = net.view().wifi == WifiState::Connected;
    d.fillCircle(16, 174, 3, uartOk ? MintGreen : WarnRed);
    line(24, 170, 36, "UART", White);
    d.fillCircle(68, 174, 3, wifiOk ? MintGreen : WarnRed);
    line(76, 170, 38, "Wi-Fi", White);
    d.fillCircle(122, 174, 3, apiOk ? MintGreen : WarnAmber);
    line(130, 170, 34, "API", White);
    d.fillRoundRect(178, 166, 134, 17, 3, CardBg);
    d.drawRoundRect(178, 166, 134, 17, 3, GridSlate);
    line(182, 170, 126, "90s Prototype Mode", MintGreen, CardBg, true);

    line(8, 188, 304, notice, MutedText);
    btn(8, 204, 104, 30, unreadDoctorMessage() ? "Comms (!)" : "Doctor comms", unreadDoctorMessage());
    btn(116, 204, 92, 30, "Sign out", false);
    btn(212, 204, 100, 30, "Connections", false);
    return;
  }

  if (page == Page::Patients) {
    line(8, 38, 196, "QUEUE: SELECT PATIENT", MintGreen);
    line(208, 38, 104, siteName[0] ? siteName : "Clinic Queue", MutedText, NavyBg, true);
    d.fillRoundRect(8, 52, 304, 26, 4, CardBg);
    d.drawRoundRect(8, 52, 304, 26, 4, GridSlate);
    d.drawCircle(20, 64, 4, MutedText); d.drawLine(23, 67, 27, 71, MutedText);
    line(34, 60, 270, search[0] ? String(search) : "Filter by ID or name", search[0] ? White : MutedText, CardBg);

    d.fillRect(8, 82, 304, 92, NavyBg);
    String term(search); term.toLowerCase();
    unsigned visible = 0;
    for (unsigned i = 0; i < patientCount; ++i) {
      String name(patients[i].name), id(patients[i].id); name.toLowerCase(); id.toLowerCase();
      if (term.length() && name.indexOf(term) < 0 && id.indexOf(term) < 0) continue;
      if (visible >= 2) break;
      const int top = 82 + visible * 46;
      ++visible;
      d.fillRoundRect(8, top, 304, 42, 4, CardBg);
      d.drawRoundRect(8, top, 304, 42, 4, visible == 1 ? MintGreen : GridSlate);
      line(15, top + 5, 148, patients[i].id, MintGreen, CardBg);
      line(166, top + 5, 74, patients[i].dob[0] ? patients[i].dob : "Clinic", MutedText, CardBg, true);
      line(15, top + 17, 224, patients[i].name, White, CardBg);
      const bool hasDoc = patients[i].doctorName[0] != 0 && !patients[i].requiresDoctorAssignment;
      line(15, top + 29, 224, String("Assigned: ") + (hasDoc ? String(patients[i].doctorName) : "Assign clinician"),
           hasDoc ? MutedText : WarnAmber, CardBg);
      btn(246, top + 7, 60, 28, "Select", true);
    }
    if (!visible) {
      line(14, 112, 292, busy() ? "Synchronising clinic patients..." : term.length() ? "No matching patients on this page." : "No eligible patients on this page.", MutedText);
    }
    btn(8, 177, 146, 22, "Refresh DB", false);
    btn(160, 177, 152, 22, nextPatient.length() ? "Next page >" : "Page synced", nextPatient.length() > 0);
    btn(8, 204, 76, 30, "< Back", false);
    btn(90, 204, 114, 30, "New Intake", true);
    btn(210, 204, 102, 30, "Connections", false);
    return;
  }

  if (page == Page::Register) {
    line(8, 38, 304, "INTAKE: STEP 1 OF 2 | Required Details", MintGreen);
    d.fillRoundRect(8, 54, 304, 30, 4, CardBg);
    d.drawRoundRect(8, 54, 304, 30, 4, GridSlate);
    line(14, 64, 292, String("Full Name: ") + (draftName[0] ? String(draftName) : "Tap to enter"), draftName[0] ? White : MutedText, CardBg);

    d.fillRoundRect(8, 90, 304, 30, 4, CardBg);
    d.drawRoundRect(8, 90, 304, 30, 4, GridSlate);
    line(14, 100, 292, String("Birth Date: ") + (draftDob[0] ? String(draftDob) : "Optional YYYY-MM-DD"), draftDob[0] ? White : MutedText, CardBg);

    d.fillRoundRect(8, 126, 304, 34, 4, CardBg);
    d.drawRoundRect(8, 126, 304, 34, 4, GridSlate);
    d.paragraph(14, 131, 292, 24, "Notice: Prototype clinic intake synchronised with Firebase Realtime Database.", MutedText, CardBg, 2, 11);

    btn(8, 166, 304, 28, "Next Step: Assign Clinician >", true);
    line(8, 195, 304, notice, WarnAmber);
  } else if (page == Page::Doctors) {
    line(8, 38, 304, repairingAssignment ? "ASSIGN ATTENDING CLINICIAN" : "INTAKE: STEP 2 OF 2 | Assign Clinician", MintGreen);
    d.fillRect(8, 54, 304, 92, NavyBg);
    for (unsigned i = 0; i < doctorCount && i < 2; ++i) {
      const int top = 54 + i * 46;
      d.fillRoundRect(8, top, 304, 42, 4, CardBg);
      d.drawRoundRect(8, top, 304, 42, 4, TealBorder);
      line(15, top + 8, 224, doctors[i].name, White, CardBg);
      line(15, top + 23, 224, repairingAssignment ? "Tap to assign primary clinician" : "Tap to register with clinician", MutedText, CardBg);
      btn(246, top + 7, 60, 28, "Assign", true);
    }
    if (!doctorCount) line(14, 84, 292, busy() ? "Loading approved clinicians..." : "No approved clinicians on this page.", MutedText);
    if (nextDoctor.length()) btn(8, 152, 304, 24, "More clinicians >", false);
    line(8, 184, 304, notice, MutedText);
  } else if (page == Page::AssignConfirm) {
    line(8, 38, 304, "CONFIRM CLINICIAN ASSIGNMENT", MintGreen);
    d.fillRoundRect(8, 54, 304, 104, 4, CardBg);
    d.drawRoundRect(8, 54, 304, 104, 4, GridSlate);
    line(16, 63, 288, String("Patient: ") + selected.name, White, CardBg);
    line(16, 79, 288, String("ID: ") + selected.id, MintGreen, CardBg);
    line(16, 95, 288, String("Clinician: ") + (doctorSelected < doctorCount ? String(doctors[doctorSelected].name) : "Select clinician"), White, CardBg);
    line(16, 113, 288, "Synchronises assignment to Realtime Database.", MutedText, CardBg);
    line(16, 131, 288, notice, WarnAmber, CardBg);
    btn(8, 166, 304, 28, "Confirm Clinician Assignment", true);
  } else if (page == Page::Confirm) {
    line(8, 38, 304, "STEP 1 OF 4: CONFIRM PATIENT DETAILS", MintGreen);
    d.fillRoundRect(8, 54, 304, 104, 4, CardBg);
    d.drawRoundRect(8, 54, 304, 104, 4, GridSlate);
    line(16, 61, 288, selected.name, White, CardBg);
    line(16, 76, 288, String("Patient ID: ") + selected.id, MintGreen, CardBg);
    line(16, 91, 288, String("Birth date: ") + (selected.dob[0] ? selected.dob : "Not recorded"), MutedText, CardBg);
    const bool hasDoc = selected.doctor[0] != 0 && !selected.requiresDoctorAssignment;
    line(16, 106, 288, String("Attending: ") + (hasDoc && selected.doctorName[0] ? String(selected.doctorName) : "Doctor assignment required"),
         hasDoc ? White : WarnAmber, CardBg);
    line(16, 121, 288, selected.local ? "Local intake | Syncing to Realtime DB" : "Synchronised clinic patient record", MutedText, CardBg);
    line(16, 138, 288, notice, MutedText, CardBg);
    btn(8, 166, 304, 28, !hasDoc ? "Assign Attending Clinician >" : "Confirm & Open Pad Guide >", true);
  } else if (page == Page::Guide) {
    line(8, 38, 214, "STEP 2 OF 4: PLACEMENT GUIDE", MintGreen);
    line(226, 38, 86, "1 ECG Lead", MutedText, NavyBg, true);
    if (fullRedraw) {
      d.fillRoundRect(8, 54, 84, 110, 5, CardBg);
      d.drawRoundRect(8, 54, 84, 110, 5, GridSlate);
      d.drawCircle(50, 68, 8, MutedText);
      d.drawRoundRect(20, 80, 60, 74, 8, MutedText);
      d.fillCircle(32, 96, 9, TealBtn); d.drawCircle(32, 96, 9, MintGreen);
      d.fillCircle(68, 96, 9, TealBtn); d.drawCircle(68, 96, 9, MintGreen);
      d.fillCircle(35, 134, 11, TealBtn); d.drawCircle(35, 134, 11, MintGreen);
      d.setTextColor(White, TealBtn);
      d.setCursor(30, 92); d.print("R");
      d.setCursor(66, 92); d.print("L");
      d.setCursor(26, 130); d.print("COM");
      d.setTextColor(White, NavyBg);

      d.fillRoundRect(98, 54, 214, 62, 4, CardBg);
      d.drawRoundRect(98, 54, 214, 62, 4, GridSlate);
      line(105, 60, 200, "Lead Contact Status:", MutedText, CardBg);
      line(105, 86, 200, "R: Right clavicle | L: Left clavicle", White, CardBg);
      line(105, 100, 200, "COM: Lower right rib reference", White, CardBg);

      d.fillRoundRect(98, 120, 214, 44, 4, AmberBg);
      d.drawRoundRect(98, 120, 214, 44, 4, AmberBorder);
      d.paragraph(104, 125, 202, 34, "Contact detection cannot verify anatomical placement. No impedance claimed.", WarnAmber, AmberBg, 3, 11);
    }
    const bool leadsClear = c3Fresh && !leadFlags;
    d.fillCircle(109, 77, 3, leadsClear ? MintGreen : WarnRed);
    line(117, 73, 188, !c3Fresh ? "Waiting for C3 UART" : leadsClear ? "Lead-off inputs: clear" : "Lead-off: check R, L, COM",
         leadsClear ? MintGreen : WarnRed, CardBg);
    if (fullRedraw) btn(8, 170, 304, 28, "Pads Placed: Pre-Recording Checklist >", true);
  } else if (page == Page::Leads) {
    line(8, 38, 304, "STEP 3 OF 4: PRE-RECORDING CHECKLIST", MintGreen);
    if (fullRedraw) {
      d.fillRoundRect(8, 51, 304, 106, 4, CardBg);
      d.drawRoundRect(8, 51, 304, 106, 4, GridSlate);
    }
    const bool patientOk = selected.id[0] && selected.version;
    const bool doctorOk = selected.doctor[0] && !selected.requiresDoctorAssignment;
    const bool shiftOk = canWork(90000);
    const bool uartOk = c3Fresh;
    const bool sdOk = cardReady;
    const bool leadsOk = c3Fresh && !leadFlags;
    const bool apiReady = net.view().api == ApiState::Ready;
    const bool allOk = patientOk && doctorOk && shiftOk && placementChecked && uartOk && sdOk && leadsOk;

    auto checkRow = [&](int y, const char *title, const char *detail, bool ok, bool advisory = false) {
      const uint16_t dot = ok ? MintGreen : advisory ? WarnAmber : WarnRed;
      d.fillCircle(17, y + 4, 3, dot);
      d.label(25, y, 88, 10, title, White, false, false, CardBg);
      d.label(116, y, 188, 10, detail, ok ? MintGreen : advisory ? WarnAmber : WarnRed, false, false, CardBg);
    };

    checkRow(56, "1. Patient", selected.id[0] ? selected.name : "Select patient", patientOk);
    checkRow(70, "2. Clinician", doctorOk && selected.doctorName[0] ? selected.doctorName : "Assign clinician", doctorOk);
    checkRow(84, "3. Shift", shiftOk ? "Authorised (>=90s)" : "Approve nurse shift", shiftOk);
    checkRow(98, "4. C3 UART", uartOk ? "Connected (115200)" : "Link unavailable", uartOk);
    checkRow(112, "5. microSD", sdOk ? "Ready for journal" : "Storage check needed", sdOk);
    checkRow(126, "6. R, L, COM", !c3Fresh ? "Waiting C3..." : leadsOk ? "Contacts clear" : "Lead-off asserted", leadsOk);
    checkRow(140, "7. Uplink", apiReady ? "API ready (HTTPS)" : "Offline SD queue", apiReady, true);

    if (allOk) {
      btn(8, 164, 304, 32, "Start 90-Second ECG Recording >", true);
    } else {
      d.fillRoundRect(8, 164, 304, 32, 4, CardBg);
      d.drawRoundRect(8, 164, 304, 32, 4, WarnRed);
      const char *gateMsg = !leadsOk ? "Checklist incomplete: attach R, L, COM pads" :
                            !uartOk ? "Checklist incomplete: check C3 UART link" :
                            !sdOk ? "Checklist incomplete: check microSD card" :
                            "Checklist incomplete: recording blocked";
      d.label(14, 174, 292, 12, gateMsg, WarnRed, false, true, CardBg);
    }
  } else if (page == Page::Complete) {
    line(8, 38, 304, saved ? "STEP 4 OF 4: RECORDING SAVED ON microSD" : "RECORDING RETAINED FOR REVIEW", saved ? MintGreen : WarnAmber);
    d.fillRoundRect(8, 54, 304, 100, 4, CardBg);
    d.drawRoundRect(8, 54, 304, 100, 4, saved ? MintGreen : WarnAmber);
    line(16, 63, 288, selected.name[0] ? selected.name : "Bench session", White, CardBg);
    line(16, 80, 288, selected.id[0] ? String("Patient ID: ") + selected.id : "Unbound bench capture", MutedText, CardBg);
    line(16, 98, 288, notice, White, CardBg);
    line(16, 116, 288, uploadStatus, MintGreen, CardBg);
    line(16, 134, 288, "Server generates Extended A3 PDF in Blob Storage.", MutedText, CardBg);
    btn(8, 162, 304, 30, "Finish | Return to Nurse Home", true);
  } else if (page == Page::Updates) {
    line(8, 38, 188, "DOCTOR COORDINATION", White);
    line(200, 38, 112, eventReceived ? "Acknowledged" : eventId[0] ? "Device received" : "Realtime API",
         eventId[0] && !eventReceived ? MintGreen : MutedText, NavyBg, true);
    if (fullRedraw) {
      d.fillRoundRect(8, 52, 304, 108, 4, CardBg);
      d.drawRoundRect(8, 52, 304, 108, 4, TealBorder);
      d.fillRoundRect(14, 137, 292, 18, 3, AmberBg);
      d.drawRoundRect(14, 137, 292, 18, 3, AmberBorder);
      d.label(18, 141, 284, 10, "Coordination channel only. Not an emergency channel.", WarnAmber, false, true);
    }
    d.fillCircle(18, 62, 3, unreadDoctorMessage() ? MintGreen : MutedText);
    line(26, 58, 186, "Realtime clinician message", MintGreen, CardBg);
    line(216, 58, 88, eventSession[0] ? String(eventSession).substring(0, 14) : "Clinic station", MutedText, CardBg, true);
    const String authorLabel = eventAuthor[0] ?
      (String(eventAuthor).startsWith("Dr") ? String(eventAuthor) + ":" : String("Dr ") + eventAuthor + ":") :
      String("Attending clinician:");
    line(16, 73, 288, authorLabel, White, CardBg);
    d.paragraph(16, 87, 288, 46, eventMessage[0] ? String(eventMessage) : String("No active doctor coordination messages for this unit."),
                eventMessage[0] ? White : MutedText, CardBg, 4, 11);

    btn(8, 168, 146, 28, returnToMonitor ? "Back to ECG" : "Dismiss", false);
    btn(160, 168, 152, 28, eventId[0] && !eventReceived ? "Acknowledge" : "Refresh", true);
  }

  if (fullRedraw) {
    btn(8, 206, 68, 28, "Back", false);
    btn(82, 206, 72, 28, "Home", false);
    btn(160, 206, 152, 28, "Connections", false);
  }
}
