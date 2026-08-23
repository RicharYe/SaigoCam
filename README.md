# SaigoCam

SaigoCam turns your Android device into a high-quality, low-latency camera source for OBS Studio (or any RTSP client). It runs a local RTSP server directly on your phone, allowing you to stream your phone's camera and microphone to your PC over Wi-Fi or a wired USB connection.

## 📝 Features
* **Direct OBS Integration:** Easily connect using the VLC Video Source or Media Source in OBS.
* **Low Latency USB Mode:** Supports ultra-low latency streaming via USB using ADB port forwarding.
* **Wi-Fi Streaming:** Stream wirelessly over your local network.
* **Camera Controls:** Switch between front and back cameras and select standard streaming resolutions (e.g., 720p, 1080p).
* **Modern Architecture:** Built entirely with Kotlin, Jetpack Compose, and the RootEncoder library.

## 📥 Download and Install
You can download the latest compiled `.apk` file without building it yourself:
1. Go to the **Actions** tab in this repository.
2. Click on the latest successful **Build Android APK** workflow run.
3. Scroll down to the **Artifacts** section and download the `SaigoCam-app-debug` zip file.
4. Extract the zip and install the `.apk` on your Android device.

## 🚀 How to Use (OBS Setup)

### Wi-Fi (VLC Video Source - Recommended)
1. Ensure your PC and phone are on the same Wi-Fi network.
2. Start streaming in the SaigoCam app.
3. Open OBS Studio -> Sources -> `+` -> **VLC Video Source**.
4. Check **Loop Playlist** (Required for auto-connect).
5. Set **Network Caching** to 100ms.
6. In the Playlist box, click `+` -> **Add Path/URL**.
7. Enter the URL shown in the app (e.g., `rtsp://192.168.x.x:1935`).

### USB (Lowest Latency)
1. Enable **USB Debugging** on your Android device and connect it to your PC.
2. Open a terminal/command prompt on your PC and run:
   ```bash
   adb forward tcp:1935 tcp:1935
   ```
3. Start streaming in the SaigoCam app.
4. In OBS, add a VLC Video Source or Media Source and use the URL: `rtsp://127.0.0.1:1935`

## ⚖️ License
This project is licensed under the GNU GPLv3 License - see the [LICENSE](LICENSE) file for details.
