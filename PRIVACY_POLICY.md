# Privacy Policy for Tefillin Mirror (מראת תפילין)

**Last updated:** October 26, 2026

**Tefillin Mirror** ("we", "our", or "the app") is committed to protecting your privacy. This Privacy Policy explains how information is handled when you use the Tefillin Mirror Android application.

---

### 1. Overview & Core Privacy Promise
**Tefillin Mirror does not collect, record, track, or share your personal data, images, or camera streams.**

All computer vision algorithms, facial landmark tracking, and Tefillin alignment verifications are performed **100% locally on your device (Edge AI)**. Your live camera feed is processed in real time in volatile device memory (RAM) and is never transmitted over the internet or saved to permanent storage during normal use.

---

### 2. Device Permissions Used

The application requests the following permissions strictly to provide its core religious/halachic alignment functionality:

| Permission | Purpose | Data Transmitted? |
| :--- | :--- | :--- |
| `android.permission.CAMERA` | Accesses the front camera to provide the real-time mirror feed and calculate horizontal & vertical Tefillin alignment. | **No.** Video frames are analyzed in real time on-device and instantly discarded. No video is recorded. |
| `android.permission.VIBRATE` | Provides brief haptic feedback (a gentle vibration) when the Tefillin is perfectly aligned. | **No.** |
| `android.permission.INTERNET` | Used **solely** if you voluntarily submit a diagnostic bug report through the in-app "Report Bug" dialog. | **Only if voluntarily initiated by the user.** |
| `android.permission.ACCESS_NETWORK_STATE` | Checks network availability before attempting to submit a diagnostic bug report. | **No.** |

---

### 3. On-Device Processing (Edge AI)
- **Face Landmark Detection:** Powered by Google MediaPipe Tasks Vision running on your device's GPU/CPU. No biometric profiles or facial identifiers are ever generated, saved, or uploaded.
- **Tefillin Detection:** Powered by an on-device neural network running through Microsoft ONNX Runtime Android with NNAPI hardware acceleration.
- **Offline Capability:** The core mirror and alignment functionality works completely offline without requiring an active internet connection.

---

### 4. Voluntary Bug Reporting & Diagnostic Data
If you encounter a technical issue or misdetection, you may optionally open the **Report Bug** screen:
- **Snapshot Attachment:** The app may present a preview frame so you can show the detection issue. **You have full control** to uncheck "Attach camera snapshot" before sending.
- **Diagnostic Information:** Technical logs include device model, Android OS version, active inference model, camera resolution, and halachic alignment calculation outputs.
- **Transmission:** Bug reports are submitted directly via secure HTTPS or shared using your chosen Android sharing app (such as email or WhatsApp). This information is used strictly to debug and improve the algorithm.

---

### 5. Third-Party Libraries & Tracking
- **No Advertising:** The app contains zero advertisements.
- **No Analytics / Trackers:** We do not include Google Analytics, Firebase Analytics, Facebook SDK, or any third-party marketing/tracking tools.
- **No Account Required:** You do not need to register, create an account, or provide your name, phone number, or email to use the app.

---

### 6. Children's Privacy
The app does not collect any personally identifiable information from anyone, including children under the age of 13.

---

### 7. Changes to This Privacy Policy
We may update our Privacy Policy from time to time. Any changes will be published in this document and reflected with an updated "Last updated" date.

---

### 8. Contact Us
If you have any questions or suggestions regarding this Privacy Policy, please contact the developer via:
- **GitHub Repository:** [Issues & Support](https://github.com/drozanes/tefillin_android_native/issues)
