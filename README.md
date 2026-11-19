# EVK GNSS Receiver

EVK GNSS Receiver 是一個 Android App，用來透過 USB 讀取 u-blox EVK-X20P 的定位資料。它可在手機上顯示 RTK 狀態、速度與行進方向，也可接收 PointPerfect 修正資料並透過 MQTT 將觀測資料送到 PC 記錄。

## 建置與安裝

- **開發環境**需要 Android Studio 和 Android SDK API 37。專案內附 Gradle Wrapper。
- **實機**需要支援 USB Host／OTG、搭載 Android 8.0（API 26）以上的手機，以及 EVK-X20P、GNSS 天線與可傳輸資料的 USB 線。
- **RTK 修正**需準備 PointPerfect 的 NTRIP 憑證。若要在 **PC 記錄**，需準備 MQTT broker 和 Python。

用 Android Studio 開啟專案並同步 Gradle，即可執行 `app`。在 Windows PowerShell 的專案根目錄執行下列指令，可建置 Debug APK 並執行單元測試。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

建置完成後，APK 位於 `app/build/outputs/apk/debug/app-debug.apk`。

## 連接 EVK

1. 接好 EVK 的 GNSS 天線，將 EVK 接到手機的 USB／OTG 連接埠。
2. 在首頁按 **連接 EVK**，並允許 App 存取 USB 裝置。EVK 須透過 USB 輸出 `UBX-NAV-PVT` 訊息。
3. 查看首頁的定位狀態、衛星數、水平精度與修正資料年齡。展開 **即時速度與方位（GNSS + IMU）** 可查看速度、行進方向與羅盤。

**更新率**可選 1、2、5 或 10 Hz，預設 10 Hz。App 會顯示 EVK 的回覆和依 NAV-PVT 訊息計算的實測頻率。更新率指令只寫入 EVK 的 RAM，App 每次連接 EVK 時會套用已儲存的設定。

如已量到 USB 固定延遲，可在 **延遲補償** 輸入 0–1000 ms，於下次連接 EVK 時生效。不確定時可保留預設 0 ms。

## 啟用 PointPerfect NTRIP 修正

1. 在 Thingstream 找到對應裝置的 **Credentials**，將 NTRIP 主機、連接埠、帳號與密碼填入 App 的 **NTRIP 設定**。App 預設使用 TLS、連接埠 `2102` 和掛載點 `NEAR-SPARTN`，請依憑證內容調整。
2. 可按 **測試帳密並列出掛載點** 檢查設定。儲存後，先接上 EVK，再按 **NTRIP 連線**。
3. App 會等待 EVK 有定位資料，定期向 NTRIP 伺服器送出 GGA，並透過 USB 將收到的 SPARTN 修正資料轉送給 EVK。首頁會顯示修正連線狀態與 RTK FLOAT／FIXED 等定位狀態。

使用完畢可按 **NTRIP 中斷** 關閉修正連線。App 的使用時間是本機紀錄，正式額度與用量以 Thingstream 裝置的 **Activity** 頁面為準。

## 透過 MQTT 在 PC 記錄

1. 準備一個手機與 PC 都能連線的 MQTT broker。
2. 在 App 的 **MQTT 設定** 填入 broker 主機、連接埠、帳號與密碼。儲存後回首頁按 **連線**。若 broker 跑在 PC 上，手機端請填 PC 在區域網路上的 IP。
3. 在 PC 的專案根目錄啟動記錄工具，依提示輸入同一個 broker 的連線資料。若 broker 就在這台 PC 上，記錄工具可使用 `127.0.0.1`。

```powershell
python -m pip install "paho-mqtt>=2"
python tools/mqtt_gnss_logger.py
```

App 發布至 `gnss/evk01/position`。每筆 JSON 包含 EVK 的 GNSS 定位資料，以及 `imu`、`fusion` 和 `ntrip` 欄位。執行記錄工具時，可選擇是否略過無效定位。

| 輸出檔 | 內容 |
| --- | --- |
| `tools/gnss_data/gnss_positions.jsonl` | 逐筆附加保留的訊息，每行一筆 JSON。 |
| `tools/gnss_data/latest_position.json` | 最近一筆寫入的訊息。 |

## 專案結構

- [`app/src/main`](app/src/main) 包含 Android 介面、USB／UBX 解析、NTRIP、MQTT 和速度融合。
- [`app/src/test`](app/src/test) 包含 JVM 單元測試。
- [`app/src/androidTest`](app/src/androidTest) 包含 Android 儀器測試。
- [`tools/mqtt_gnss_logger.py`](tools/mqtt_gnss_logger.py) 是 PC 端 MQTT 記錄工具。
