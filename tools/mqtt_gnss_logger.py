
"""
EVK GNSS MQTT 即時接收與資料儲存

功能：
1. 手動設定 MQTT Host、Port、Username、Password
2. 記住前三個連線參數，不儲存密碼
3. 訂閱 gnss/evk01/position
4. 顯示 MQTT 連線與訂閱確認結果
5. 啟動時選擇是否略過無效定位
6. 持續追加 gnss_positions.jsonl
7. 持續更新 latest_position.json
8. MQTT 斷線時自動嘗試重新連線

安裝：
    python -m pip install paho-mqtt

執行：
    python mqtt_gnss_logger.py
"""

import json
import math
import os
import sys

from datetime import datetime, timezone
from getpass import getpass
from pathlib import Path

import paho.mqtt.client as mqtt


# ============================================================
# 1. MQTT 預設設定
# ============================================================

DEFAULT_BROKER_HOST = "127.0.0.1"
DEFAULT_BROKER_PORT = 1883
DEFAULT_USERNAME = "gnssuser"

# 必須與 Android MqttPublisher.kt 的 Topic 相同
MQTT_TOPIC = "gnss/evk01/position"


# ============================================================
# 2. 儲存檔案位置
# ============================================================

BASE_DIR = Path(__file__).resolve().parent

SETTINGS_PATH = BASE_DIR / "mqtt_connection_settings.json"

OUTPUT_DIR = BASE_DIR / "gnss_data"

HISTORY_PATH = OUTPUT_DIR / "gnss_positions.jsonl"

LATEST_PATH = OUTPUT_DIR / "latest_position.json"


# ============================================================
# 3. 統計變數
# ============================================================

# MQTT 總共收到多少筆訊息
received_count = 0

# 通過定位有效性檢查的資料
valid_count = 0

# 沒有有效定位而略過的資料
skipped_count = 0

# JSON 格式不正確的資料
invalid_json_count = 0

# 實際成功儲存的資料
saved_count = 0

# 實際 MQTT 連線資訊
broker_host = DEFAULT_BROKER_HOST
broker_port = DEFAULT_BROKER_PORT

# MQTT Topic 訂閱狀態
subscription_confirmed = False


# ============================================================
# 4. 讀取上次的連線設定
# ============================================================

def load_settings():

    settings = {
        "host": DEFAULT_BROKER_HOST,
        "port": DEFAULT_BROKER_PORT,
        "username": DEFAULT_USERNAME,
    }

    if not SETTINGS_PATH.exists():
        return settings

    try:
        with SETTINGS_PATH.open(
            "r",
            encoding="utf-8"
        ) as file:

            saved = json.load(file)

        if isinstance(saved, dict):

            for key in ("host", "port", "username"):

                if key in saved:
                    settings[key] = saved[key]

    except (OSError, ValueError, TypeError) as error:

        print(f"[設定] 讀取失敗：{error}")

    return settings


# ============================================================
# 5. 儲存連線設定（不包含密碼）
# ============================================================

def save_settings(host, port, username):

    settings = {
        "host": host,
        "port": port,
        "username": username,
    }

    temp_path = SETTINGS_PATH.with_suffix(".json.tmp")

    try:

        with temp_path.open(
            "w",
            encoding="utf-8"
        ) as file:

            json.dump(
                settings,
                file,
                ensure_ascii=False,
                indent=2
            )

            file.write("\n")

        os.replace(temp_path, SETTINGS_PATH)

    except OSError as error:

        print(f"[設定] 儲存失敗：{error}")


# ============================================================
# 6. 手動輸入 MQTT 連線資訊
# ============================================================

def get_mqtt_settings():

    settings = load_settings()

    print("=" * 55)
    print("         EVK GNSS MQTT 連線設定")
    print("=" * 55)

    print("\n直接按 Enter 可使用中括號內的預設值。\n")

    # ---------- Broker Host ----------

    default_host = str(
        settings.get("host") or DEFAULT_BROKER_HOST
    ).strip()

    host_input = input(
        f"MQTT Broker IP / Host [{default_host}]："
    ).strip()

    host = host_input or default_host

    # ---------- Port ----------

    try:
        default_port = int(
            settings.get("port", DEFAULT_BROKER_PORT)
        )

        if not 1 <= default_port <= 65535:
            default_port = DEFAULT_BROKER_PORT

    except (TypeError, ValueError):
        default_port = DEFAULT_BROKER_PORT

    while True:

        port_input = input(
            f"MQTT Port [{default_port}]："
        ).strip()

        if not port_input:
            port = default_port
            break

        try:
            port = int(port_input)

            if 1 <= port <= 65535:
                break

            print("[錯誤] Port 必須介於 1～65535")

        except ValueError:
            print("[錯誤] Port 必須是整數")

    # ---------- Username ----------

    default_username = str(
        settings.get("username") or DEFAULT_USERNAME
    ).strip()

    username_input = input(
        f"MQTT Username [{default_username}]："
    ).strip()

    username = username_input or default_username

    # ---------- Password ----------

    password = os.environ.get("GNSS_MQTT_PASSWORD")

    if not password:
        password = getpass("MQTT Password：")

    if not password:
        print("[錯誤] MQTT Password 不可為空")
        return None

    # 記住 Host、Port、Username
    save_settings(host, port, username)

    print("\n" + "=" * 55)

    print("MQTT 連線資訊")
    print(f"Broker   : {host}:{port}")
    print(f"Username : {username}")
    print(f"Topic    : {MQTT_TOPIC}")

    print("=" * 55 + "\n")

    return host, port, username, password


# ============================================================
# 7. 檢查 GNSS 是否具有有效定位
# ============================================================

def is_valid_gnss_position(data):
    """
    判斷這筆 GNSS JSON 是否值得儲存。

    必須同時符合：
    1. fix_ok 必須是布林值 True
    2. latitude_deg 不能是 null
    3. longitude_deg 不能是 null
    4. 經緯度必須是合理的有限數字

    注意：
    經緯度等於 0 不一定代表無效，
    因此不能直接用 if latitude 判斷。
    """

    # 條件 1：EVK 已取得有效 GNSS Fix
    if data.get("fix_ok") is not True:
        return False

    latitude = data.get("latitude_deg")
    longitude = data.get("longitude_deg")

    # 條件 2：經緯度不能是 JSON null
    if latitude is None or longitude is None:
        return False

    # 條件 3：經緯度必須是數字
    # 排除字串、布林值等不合理格式
    if type(latitude) not in (int, float):
        return False

    if type(longitude) not in (int, float):
        return False

    # 排除 NaN、Infinity 等非有限數字
    if not math.isfinite(latitude):
        return False

    if not math.isfinite(longitude):
        return False

    # 條件 4：經緯度必須在合法範圍
    if not -90 <= latitude <= 90:
        return False

    if not -180 <= longitude <= 180:
        return False

    return True


# ============================================================
# 8. MQTT 連線成功
# ============================================================

def on_connect(
    client,
    userdata,
    flags,
    reason_code,
    properties
):

    global subscription_confirmed

    subscription_confirmed = False

    if reason_code.is_failure:

        print(
            f"[MQTT] Broker 拒絕連線：{reason_code}",
            flush=True
        )

        return

    print(
        f"[MQTT] 已連線：{broker_host}:{broker_port}",
        flush=True
    )

    # 連線成功後訂閱 GNSS Topic
    result, mid = client.subscribe(
        MQTT_TOPIC,
        qos=1
    )

    if result == mqtt.MQTT_ERR_SUCCESS:

        print(
            f"[MQTT] 已送出訂閱請求：{MQTT_TOPIC} "
            f"(MID={mid})",
            flush=True
        )

    else:

        print(
            f"[MQTT] 訂閱請求失敗：{result}",
            flush=True
        )


# ============================================================
# 9. MQTT 訂閱確認
# ============================================================

def on_subscribe(
    client,
    userdata,
    mid,
    reason_code_list,
    properties
):

    global subscription_confirmed

    subscription_confirmed = False

    for reason_code in reason_code_list:

        if reason_code.is_failure:

            print(
                f"[MQTT] 訂閱被拒絕：{reason_code}",
                flush=True
            )

        else:

            subscription_confirmed = True

            print(
                f"[MQTT] 訂閱成功！授權 QoS：{reason_code}",
                flush=True
            )

    if subscription_confirmed:

        print(
            "[MQTT] 等待 GNSS JSON...",
            flush=True
        )


# ============================================================
# 10. MQTT 斷線處理
# ============================================================

def on_disconnect(
    client,
    userdata,
    disconnect_flags,
    reason_code,
    properties
):

    global subscription_confirmed

    subscription_confirmed = False

    print(
        f"[MQTT] 已斷線：{reason_code}",
        flush=True
    )


# ============================================================
# 11. 接收 MQTT JSON
# ============================================================

def on_message(
    client,
    userdata,
    message
):
    """
    每收到一筆 MQTT 訊息：

    1. 解析 JSON
    2. 檢查 fix_ok 與經緯度
    3. 依啟動時的設定決定是否略過無效定位
    4. 將保留的訊息寫入 JSONL
    5. 更新 latest_position.json
    """

    global received_count
    global valid_count
    global skipped_count
    global invalid_json_count
    global saved_count

    received_count += 1

    # --------------------------------------------------------
    # Step 1：解析 MQTT JSON
    # --------------------------------------------------------

    try:

        json_text = message.payload.decode("utf-8")

        data = json.loads(json_text)

        if not isinstance(data, dict):
            raise ValueError("JSON 最外層不是物件")

    except (
        UnicodeDecodeError,
        json.JSONDecodeError,
        ValueError
    ) as error:

        invalid_json_count += 1

        print(
            f"[跳過] JSON 格式錯誤：{error}",
            flush=True
        )

        return

    # --------------------------------------------------------
    # Step 2：檢查 GNSS 定位是否有效
    # --------------------------------------------------------

    if is_valid_gnss_position(data):
        valid_count += 1
    elif userdata:

        # 沒有有效 Fix，不儲存這筆資料
        skipped_count += 1

        # 避免 10 Hz 訊息洗版
        # 第一筆及每累積 100 筆跳過資料時顯示
        if skipped_count == 1 or skipped_count % 100 == 0:

            print(
                f"[GNSS] 尚未取得有效定位，略過儲存 | "
                f"跳過={skipped_count} | "
                f"fix_ok={data.get('fix_ok')} | "
                f"lat={data.get('latitude_deg')} | "
                f"lon={data.get('longitude_deg')}",
                flush=True
            )

        return

    # --------------------------------------------------------
    # Step 3：儲存 GNSS JSON
    # --------------------------------------------------------

    try:

        # 每筆保留的訊息追加一行 JSON
        with HISTORY_PATH.open(
            "a",
            encoding="utf-8",
            newline="\n"
        ) as file:

            file.write(
                json.dumps(
                    data,
                    ensure_ascii=False,
                    separators=(",", ":")
                ) + "\n"
            )

            file.flush()

        # ----------------------------------------------------
        # Step 4：更新最新一筆已儲存的訊息
        # ----------------------------------------------------

        temp_path = LATEST_PATH.with_suffix(".json.tmp")

        with temp_path.open(
            "w",
            encoding="utf-8"
        ) as file:

            json.dump(
                data,
                file,
                ensure_ascii=False,
                indent=2
            )

            file.write("\n")

        # 用完整的新檔取代舊檔
        os.replace(temp_path, LATEST_PATH)

        # 成功完成兩個檔案的更新
        saved_count += 1

        # ----------------------------------------------------
        # Step 5：顯示成功儲存的資料
        # ----------------------------------------------------

        if saved_count == 1 or saved_count % 10 == 0:

            timestamp = datetime.now(
                timezone.utc
            ).isoformat(timespec="seconds")

            print(
                f"[{timestamp}] "
                f"已儲存 {saved_count} 筆資料 | "
                f"iTOW={data.get('gps_itow_ms')} | "
                f"fix_ok={data.get('fix_ok')} | "
                f"lat={data.get('latitude_deg')} | "
                f"lon={data.get('longitude_deg')}",
                flush=True
            )

    except OSError as error:

        print(
            f"[儲存失敗] {error}",
            flush=True
        )


# ============================================================
# 12. 主程式
# ============================================================

def ask_skip_invalid():
    while True:
        answer = input("是否略過無效 GNSS 定位？[Y/n] ").strip().lower()
        if answer in ("", "y", "yes"):
            return True
        if answer in ("n", "no"):
            return False
        print("請輸入 Y 或 N。")


def main():

    global broker_host
    global broker_port

    OUTPUT_DIR.mkdir(
        parents=True,
        exist_ok=True
    )

    print("\n=== EVK GNSS MQTT 資料儲存 ===\n")

    settings = get_mqtt_settings()

    if settings is None:
        return 1

    broker_host, broker_port, username, password = settings
    skip_invalid = ask_skip_invalid()

    print(f"歷史檔：{HISTORY_PATH}")
    print(f"最新檔：{LATEST_PATH}")

    if skip_invalid:
        print("\n儲存模式：只儲存 fix_ok 為 True 且經緯度有效的訊息。")
    else:
        print("\n儲存模式：儲存所有 JSON 物件，包含無效定位及其 IMU 資料。")

    print("\n按 Ctrl+C 停止接收。\n")

    # 建立 MQTT Client
    client = mqtt.Client(
        callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        client_id="evk-pc-json-logger",
        userdata=skip_invalid,
        protocol=mqtt.MQTTv311
    )

    client.username_pw_set(
        username,
        password
    )

    # 註冊 MQTT 事件處理函式
    client.on_connect = on_connect
    client.on_subscribe = on_subscribe
    client.on_disconnect = on_disconnect
    client.on_message = on_message

    # 自動重連等待時間
    client.reconnect_delay_set(
        min_delay=1,
        max_delay=30
    )

    client.connect_async(
        broker_host,
        broker_port,
        keepalive=60
    )

    try:

        client.loop_forever(
            retry_first_connection=True
        )

    except KeyboardInterrupt:

        print("\n[系統] 停止接收。")

    finally:

        client.disconnect()

        # 最後顯示整體統計
        print("\n========== 接收統計 ==========")
        print(f"MQTT 收到：{received_count} 筆")
        print(f"有效定位：{valid_count} 筆")
        print(f"略過無效定位：{skipped_count} 筆")
        print(f"JSON 格式錯誤：{invalid_json_count} 筆")
        print(f"成功儲存：{saved_count} 筆")
        print("==============================")

    return 0


# ============================================================
# 13. 程式進入點
# ============================================================

if __name__ == "__main__":
    sys.exit(main())
