from pyexpat import model
import cv2
import time
import os
import csv
import serial
import pynmea2
import threading
import queue
import numpy as np
import asyncio
import websockets
import socket
import json
import base64 
import firebase_admin
from firebase_admin import credentials, firestore, storage
import sqlite3
import requests
from ncnn_model.model_ncnn import YoloV8SegNCNN
from sort import Sort
import psutil 
from picamera2 import Picamera2, controls
from sort import Sort
from datetime import datetime
from flask import Flask, send_from_directory, jsonify, send_file, Response, request
import logging

# --- CẤU HÌNH ---
MODEL_PATH = "/home/thaiquan/Desktop/pothole_project/ncnn_model"
BASE_DIR = "/home/thaiquan/Desktop/pothole_project"
IMG_DIR = os.path.join(BASE_DIR, "Pothole_Images")
LOG_FILE = os.path.join(BASE_DIR, "data_log.csv")
WS_PORT = 8765
DB_FILE = os.path.join(BASE_DIR, "potholes.db")
HTTP_PORT = 8000
CONF_THRESHOLD = 0.4
COOLDOWN_TIME = 2.0
RES_W, RES_H = 640, 480
SERIAL_PORT = "/dev/serial0"
GOOGLE_SCRIPT_URL = "https://script.google.com/macros/s/AKfycbzUwklck20636jlLPAu4dnReirvQgR4bvHl1GkQZnZbijJ3zNs7WLkbeHYvFuKwK88/exec"
UPLOAD_HISTORY_FILE = os.path.join(BASE_DIR, "uploaded_history.txt")
FIREBASE_CERT_PATH = os.path.join(BASE_DIR, "serviceAccountKey.json")
FIREBASE_BUCKET = "motosense-db.firebasestorage.app" # Thay bằng ID bucket của bạn

FRAME_AREA = RES_W * RES_H
AREA_THRES_HIGH = FRAME_AREA * 0.03   # 3%
AREA_THRES_MED  = FRAME_AREA * 0.01   # 1%
MIN_SAVE_AREA   = FRAME_AREA * 0.005  # 0.5%



try:
    cred = credentials.Certificate(FIREBASE_CERT_PATH)
    firebase_admin.initialize_app(cred, {
        'storageBucket': FIREBASE_BUCKET
    })
    db = firestore.client()
    bucket = storage.bucket()
    print("🔥 Firebase initialized successfully")
except Exception as e:
    print(f"❌ Firebase Error: {e}")



def get_ip_address():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # Mẹo: Kết nối thử đến một IP ảo để hệ thống tự trả về IP nội bộ đang dùng
        s.connect(('10.255.255.255', 1))
        IP = s.getsockname()[0]
    except Exception:
        IP = '127.0.0.1'
    finally:
        s.close()
    return IP
# --- BỘ QUẢN LÝ VIDEO STREAM (FIX LỖI LOADING) ---


CURRENT_IP = get_ip_address()
PI_HOST_ADDRESS = f"http://{CURRENT_IP}:{HTTP_PORT}"

print(f"🌍 Pi IP Address detected: {PI_HOST_ADDRESS}")


class VideoStreamHandler:
    def __init__(self):
        self.frame = None
        self.lock = threading.Lock()

    def update(self, new_frame):
        # Cập nhật khung hình mới an toàn
        with self.lock:
            self.frame = new_frame.copy()

    def get(self):
        # Lấy khung hình ra an toàn
        with self.lock:
            if self.frame is None:
                return None
            return self.frame.copy()

# Khởi tạo đối tượng chia sẻ ảnh toàn cục
video_stream = VideoStreamHandler()

# --- BIẾN CHIA SẺ KHÁC ---
shared_state = {
    "speed": 0.0,
    "fps": 0,
    "cpu_temp": 0.0,
    "storage_free": "Checking...",
    "running": True
}
ws_alert_queue = queue.Queue(maxsize=200)
save_queue = queue.Queue(maxsize=30)  # giới hạn 30 event



# --- MODULE 1: KALMAN FILTER ---
class GPSKalmanFilter:
    def __init__(self):
        # Khởi tạo bộ lọc Kalman: 4 biến trạng thái (lat, lng, d_lat, d_lng), 2 biến đo lường (lat, lng)
        self.kf = cv2.KalmanFilter(4, 2)
        
        # Ma trận đo lường (Measurement Matrix)
        self.kf.measurementMatrix = np.array([[1, 0, 0, 0], 
                                              [0, 1, 0, 0]], np.float32)
        
        # Ma trận chuyển đổi trạng thái (Transition Matrix)
        self.kf.transitionMatrix = np.array([[1, 0, 1, 0], 
                                             [0, 1, 0, 1], 
                                             [0, 0, 1, 0], 
                                             [0, 0, 0, 1]], np.float32)
        
        # Ma trận nhiễu quá trình (Process Noise) - Giảm số này để bộ lọc "tin" vào dự đoán hơn (mượt hơn)
        self.kf.processNoiseCov = np.eye(4, dtype=np.float32) * 1e-4
        
        # Ma trận nhiễu đo lường (Measurement Noise) - Tăng số này nếu GPS nhảy lung tung
        self.kf.measurementNoiseCov = np.eye(2, dtype=np.float32) * 1e-2
        
        self.is_initialized = False

    def update(self, lat, lng):
        # Chuyển đổi dữ liệu đầu vào sang numpy float32 để tính toán
        measurement = np.array([[np.float32(lat)], [np.float32(lng)]])
        
        if not self.is_initialized:
            # Khởi tạo giá trị ban đầu
            self.kf.statePre = np.array([[lat], [lng], [0], [0]], np.float32)
            self.kf.statePost = np.array([[lat], [lng], [0], [0]], np.float32)
            self.is_initialized = True
            return lat, lng
        
        # Bước 1: Dự đoán (Predict)
        self.kf.predict()
        
        # Bước 2: Hiệu chỉnh (Correct) bằng dữ liệu GPS mới
        corrected = self.kf.correct(measurement)
        
        # [QUAN TRỌNG] Ép kiểu từ numpy.float32 về float chuẩn của Python
        # Nếu không có hàm float() bao ngoài, JSON và SQLite sẽ bị lỗi
        return float(corrected[0][0]), float(corrected[1][0])

# --- MODULE 2: GPS DRIVER (Giữ nguyên) ---

class GPSDriver:
    def __init__(self):
        self.lat = 0.0
        self.lng = 0.0
        self.speed = 0.0
        self.status = "Init..."
        self.running = True
        self.prev_fix = {'time': 0, 'lat': 0, 'lng': 0}
        self.curr_fix = {'time': 0, 'lat': 0, 'lng': 0}
        self.kalman = GPSKalmanFilter()
        self.thread = threading.Thread(target=self._run_loop, daemon=True)

    def start(self):
        self.thread.start()

    def get_location_at(self, query_timestamp):
        t1, t2 = self.prev_fix['time'], self.curr_fix['time']
        # Nếu dữ liệu quá cũ hoặc chưa có 2 điểm để nội suy -> trả về vị trí mới nhất
        if t2 == 0 or t1 == 0 or (t2 - t1) <= 0 or query_timestamp > t2 + 1.0:
            return self.lat, self.lng
        
        # Nội suy tuyến tính (Linear Interpolation)
        ratio = max(0.0, min(1.0, (query_timestamp - t1) / (t2 - t1)))
        i_lat = self.prev_fix['lat'] + (self.curr_fix['lat'] - self.prev_fix['lat']) * ratio
        i_lng = self.prev_fix['lng'] + (self.curr_fix['lng'] - self.prev_fix['lng']) * ratio
        return i_lat, i_lng

    def _run_loop(self):
        # 1. KẾT NỐI SERIAL
        # ATGM336H mặc định hoạt động rất tốt ở 9600, không cần ép xung lên cao làm gì cho nóng
        try:
            ser = serial.Serial(SERIAL_PORT, baudrate=9600, timeout=1)
            print("✅ ATGM336H GPS Connected at 9600 baud")
        except Exception as e:
            self.status = "No HW"
            print(f"❌ GPS Error: {e}")
            return

        # 2. VÒNG LẶP ĐỌC DỮ LIỆU
        while self.running:
            try:
                # Đọc từng dòng dữ liệu từ GPS
                # Dùng readline() ổn định hơn read_all() cho tốc độ 9600
                line = ser.readline().decode('utf-8', errors='replace').strip()
                
                # [QUAN TRỌNG] Module này trả về $GNRMC (GPS+BDS) hoặc $BDRMC
                # Ta tìm từ khóa "RMC" để bắt tất cả các trường hợp
                if "RMC" in line and len(line) > 10:
                    try:
                        msg = pynmea2.parse(line)
                        
                        # Kiểm tra trạng thái Fix ('A' = Valid/Có sóng, 'V' = Invalid/Mất sóng)
                        if msg.status == 'A': 
                            now = time.time()
                            
                            # Lọc nhiễu Kalman
                            s_lat, s_lng = self.kalman.update(float(msg.latitude), float(msg.longitude))
                            
                            # Cập nhật buffer để nội suy
                            self.prev_fix = self.curr_fix.copy()
                            self.curr_fix = {'time': now, 'lat': s_lat, 'lng': s_lng}
                            
                            self.lat, self.lng = s_lat, s_lng
                            
                            # Xử lý tốc độ (Knots -> km/h)
                            # Đôi khi module trả về chuỗi rỗng cho tốc độ, cần bắt lỗi
                            try:
                                if msg.spd_over_grnd:
                                    self.speed = float(msg.spd_over_grnd) * 1.852
                                else:
                                    self.speed = 0.0
                            except:
                                self.speed = 0.0
                            
                            self.status = "FIXED (Dual-Mode)"
                            shared_state["speed"] = round(self.speed, 1)
                        else:
                            self.status = "Searching..."
                    except pynmea2.ParseError:
                        pass # Bỏ qua dòng lỗi checksum
            except Exception as e:
                # Lỗi kết nối serial thì thử lại ở vòng lặp sau
                time.sleep(0.1)
                pass


# --- MODULE 3: WEBSOCKET SERVER (Giữ nguyên) ---
async def ws_handler(websocket,  path=None):
    global CONF_THRESHOLD
    print(f"📱 App Connected: {websocket.remote_address}")
    async def send_telemetry():
        try:
            while True:
                telemetry = {
                    "type": "telemetry",
                    "data": {
                        "speed": shared_state["speed"],
                        "cpu_temp": shared_state["cpu_temp"],
                        "fps": shared_state["fps"],
                        "storage_free": shared_state["storage_free"],
                        "current_threshold": CONF_THRESHOLD
                    }
                }
                await websocket.send(json.dumps(telemetry))
                while not ws_alert_queue.empty():
                    alert_msg = {"type": "alert", "data": ws_alert_queue.get()}
                    print("🚀 Sending Alert...")
                    await websocket.send(json.dumps(alert_msg))
                await asyncio.sleep(0.1)
        except websockets.ConnectionClosed:
            print(f"📴 App Disconnected: {websocket.remote_address}")
            pass
    
    async def receive_commands():
        global CONF_THRESHOLD
        try:
            async for message in websocket:
                data = json.loads(message)
                if data.get("command") == "set_threshold":
                    CONF_THRESHOLD = float(data.get("value"))
                    print(f"🎯 Độ nhạy AI đã đổi thành: {CONF_THRESHOLD}")
        except websockets.ConnectionClosed:
            pass

    await asyncio.gather(send_telemetry(), receive_commands())


async def start_ws_server():
    print(f"📡 WebSocket Server running on 0.0.0.0:{WS_PORT}")
    async with websockets.serve(ws_handler, "0.0.0.0", WS_PORT): await asyncio.Future()

def run_websocket_thread():
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    loop.run_until_complete(start_ws_server())

# --- MODULE 4: FLASK SERVER (ĐÃ SỬA LẠI LOGIC STREAM) ---
app = Flask(__name__)
log = logging.getLogger('werkzeug'); log.setLevel(logging.ERROR)

# 1. API Lấy lịch sử (Đã sửa để hiện ảnh trên App)
# --- TÌM VÀ THAY THẾ TOÀN BỘ HÀM get_logs NÀY ---
@app.route('/api/logs')
def get_logs():
    # 1. Kiểm tra file DB có thật sự tồn tại không (Tránh lỗi no such table)
    if not os.path.exists(DB_FILE):
        return jsonify([])

    conn = None
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row  # Để truy cập cột bằng tên key
        cursor = conn.cursor()
        
        # Lấy dữ liệu mới nhất lên đầu
        cursor.execute("SELECT * FROM detections ORDER BY timestamp DESC")
        rows = cursor.fetchall()
        
        data = []
        for row in rows:
            # --- LOGIC AN TOÀN (DEFENSIVE) ---
            
            # Xử lý ID: Nếu DB null thì fallback sang timestamp (giống code cũ)
            db_id = row["id"]
            if not db_id:
                ts_raw = row["timestamp"] or ''
                ts_clean = ts_raw.replace(' ','').replace('-','').replace(':','')
                db_id = f"ph_{ts_clean}"

            # Xử lý Filename
            fname = row["image_filename"] or ""
            
            # Map dữ liệu & Ép kiểu an toàn
            item = {
                "id": str(db_id), # Ép sang string cho chắc
                "timestamp": row["timestamp"] or "",
                
                # Ép kiểu float, nếu null thì về 0.0 (Android thích điều này)
                "lat": float(row["lat"]) if row["lat"] is not None else 0.0,
                "lng": float(row["lng"]) if row["lng"] is not None else 0.0,
                
                # Tốc độ cũng vậy
                "speed": float(row["speed"]) if row["speed"] is not None else 0.0,
                
                "severity": row["severity"] or "Low",
                
                "imageFilename": fname,
                "imageUrl": f"{PI_HOST_ADDRESS}/images/{fname}" if fname else "",
                
                # Logic sync: SQLite lưu trực tiếp 0 hoặc 1, ta chuyển về Bool
                "isSynced": bool(row["is_synced"]), 
                
                "isSelected": False
            }
            data.append(item)
            
        return jsonify(data)
        
    except Exception as e:
        print(f"Log SQLite Error: {e}")
        return jsonify([]) # Trả về mảng rỗng nếu lỗi, không để sập server
        
    finally:
        # Luôn đóng kết nối dù có lỗi hay không
        if conn: conn.close()
def init_db():
    """Khởi tạo SQLite Database nếu chưa có"""
    conn = sqlite3.connect(DB_FILE)
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA synchronous=NORMAL;")
    conn.execute("PRAGMA busy_timeout=3000;") 
    cursor = conn.cursor()
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS detections (
            id TEXT PRIMARY KEY,
            timestamp TEXT,
            lat REAL,
            lng REAL,
            speed REAL,
            severity TEXT,
            image_filename TEXT,
            is_synced INTEGER DEFAULT 0
        )
    ''')
    conn.commit()
    conn.close()

# 2. API Upload Ảnh Lên Google Apps Script
def update_sync_status(pothole_id):
    """Hàm phụ trợ để cập nhật trạng thái đồng bộ vào SQLite cục bộ trên Pi"""
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute("UPDATE detections SET is_synced = 1 WHERE id = ?", (pothole_id,))
        conn.commit()
        conn.close()
        print(f"✅ Đã cập nhật trạng thái is_synced cho {pothole_id} trong SQLite")
    except Exception as e:
        print(f"❌ Lỗi cập nhật SQLite: {e}")

@app.route('/api/upload', methods=['POST'])
def trigger_upload():
    try:
        req = request.json
        pothole_id = req.get('id')
        
        # Hỗ trợ tất cả các kiểu đặt tên key từ Android gửi lên
        filename = req.get('imageFilename') or req.get('image_filename') or req.get('filename')
        
        print(f"🔍 DEBUG RECEIVE: {req}")
        
        if not pothole_id or not filename:
            return jsonify({"status": "error", "message": "Missing ID or Filename"}), 400

        # Kiểm tra file vật lý trên đĩa
        file_path = os.path.join(IMG_DIR, filename)
        if not os.path.exists(file_path):
            return jsonify({"status": "error", "message": f"File {filename} not found on Pi"}), 404

        # --- 1. XỬ LÝ SEVERITY (QUAN TRỌNG: FIX LỖI CRASH ANDROID) ---
        # Chuyển đổi từ String (Android gửi) sang Int (Firestore lưu) để Android deserialization không bị lỗi
        severity_raw = req.get('severity', 'Low')
        severity_map = {"Low": 1, "Medium": 2, "High": 3}
        # Nếu app gửi số sẵn thì lấy, nếu gửi chữ thì map sang số, mặc định là 1
        severity_final = severity_raw if isinstance(severity_raw, int) else severity_map.get(severity_raw, 1)

        # --- 2. UPLOAD ẢNH LÊN FIREBASE STORAGE ---
        print(f"☁️ Uploading verified image {filename} to Firebase Storage...")
        blob = bucket.blob(f"potholes/{filename}") 
        blob.upload_from_filename(file_path)
        blob.make_public()
        image_url = blob.public_url

        # --- 3. LƯU DỮ LIỆU VÀO FIRESTORE (Collection 'potholes') ---
        print(f"📑 Creating map marker for {pothole_id} with severity {severity_final}...")
        
        doc_ref = db.collection('potholes').document(pothole_id)
        doc_ref.set({
            'id': pothole_id,
            'lat': float(req.get('lat', 0)),
            'lng': float(req.get('lng', 0)),
            'severity': severity_final, # Đã là kiểu INT, Android sẽ không còn crash
            'timestamp': int(time.time() * 1000), 
            'speed': float(req.get('speed', 0)),
            'images': image_url,
            'is_verified': True,
            'uploaded_at': firestore.SERVER_TIMESTAMP 
        })

        # --- 4. CẬP NHẬT DATABASE CỤC BỘ ---
        update_sync_status(pothole_id)
        
        return jsonify({
            "status": "success", 
            "message": "Upload successful, map updated",
            "image_url": image_url
        })

    except Exception as e:
        print(f"❌ Upload Error: {e}")
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route('/images/<path:filename>')
def serve_image(filename): return send_from_directory(IMG_DIR, filename)

# --- TÌM VÀ THAY THẾ TOÀN BỘ HÀM NÀY ---
def generate_frames():
    # Ảnh chờ "Loading" 
    blank_image = np.zeros((320, 320, 3), np.uint8) # Giảm size ảnh chờ xuống luôn cho nhẹ
    cv2.putText(blank_image, "LOADING...", (40, 160), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)
    _, encoded_blank = cv2.imencode(".jpg", blank_image)
    blank_bytes = bytearray(encoded_blank)

    while True:
        # Lấy frame từ VideoStreamHandler (Ảnh này đang là 640x640 có vẽ khung đỏ)
        frame = video_stream.get()
        
        if frame is None:
            # Chưa có ảnh thật thì gửi ảnh loading
            yield(b'--frame\r\n' b'Content-Type: image/jpeg\r\n\r\n' + blank_bytes + b'\r\n')
            time.sleep(0.2)
        else:
            # Có ảnh thật
            try:
                # 1. RESIZE NHỎ LẠI: Xuống 320x320 (Code cũ của bạn là 480x480)
                # Kích thước này đủ để xem trên điện thoại và rất nhẹ cho Wifi
                frame_resized = cv2.resize(frame, (320, 320))
                
                # 2. GIẢM CHẤT LƯỢNG NÉN JPEG: Xuống 50%
                # Mặc định opencv nén 95% (rất nặng), giảm xuống 60% mắt thường khó thấy khác biệt trên đth nhưng tốc độ tăng gấp đôi
                encode_param = [int(cv2.IMWRITE_JPEG_QUALITY), 60]
                
                (flag, encodedImage) = cv2.imencode(".jpg", frame_resized, encode_param)
                
                if flag:
                    yield(b'--frame\r\n' b'Content-Type: image/jpeg\r\n\r\n' + 
                          bytearray(encodedImage) + b'\r\n')
            except: pass
        
        time.sleep(0.04) # Giữ nguyên ~25 FPS

@app.route('/stream.mjpg')
def video_feed():
    return Response(generate_frames(), mimetype='multipart/x-mixed-replace; boundary=frame')

def run_flask_thread():
    app.run(host='0.0.0.0', port=HTTP_PORT, debug=False, use_reloader=False)

# --- MODULE 5: WORKER GHI ĐĨA  ---
# --- MODULE 5: WORKER GHI ĐĨA (ĐÃ FIX LỖI TRÙNG ID & DATA TYPE) ---
def storage_worker():
    # Tạo thư mục ảnh nếu chưa có
    if not os.path.exists(IMG_DIR): os.makedirs(IMG_DIR)
    
    # Kết nối DB riêng cho luồng này (SQLite yêu cầu mỗi thread 1 connection)
    conn = sqlite3.connect(DB_FILE)
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA synchronous=NORMAL;")
    conn.execute("PRAGMA busy_timeout=3000;")
    cursor = conn.cursor()
    
    print("💾 Storage Worker Started (SQLite Mode)")
    
    while True:
        item = save_queue.get()
        if item is None: break
        
        # Unpack dữ liệu từ queue
        frame, lat, lng, spd, ts_str, severity = item
        
        # 1. ÉP KIỂU AN TOÀN (Fix lỗi JSON serializable và SQLite blob)
        safe_lat = float(lat)
        safe_lng = float(lng)
        safe_spd = float(spd) if spd is not None else 0.0
        
        # 2. TẠO HẬU TỐ NGẪU NHIÊN (Fix lỗi UNIQUE constraint failed)
        # Thêm 4 ký tự ngẫu nhiên vào sau ID để đảm bảo duy nhất
        # dù có 2 ổ gà xuất hiện trong cùng 1 giây
        unique_suffix = os.urandom(2).hex() 
        
        # Tạo tên file và ID mới có chứa suffix
        filename = f"pothole_{ts_str}_{unique_suffix}.jpg"
        pothole_id = f"ph_{ts_str}_{unique_suffix}"
        
        # 3. LƯU ẢNH RA ĐĨA
        filepath = os.path.join(IMG_DIR, filename)
        try:
            cv2.imwrite(filepath, frame)
        except Exception as e:
            print(f"❌ Image Write Error: {e}")
        
        # 4. LƯU THÔNG TIN VÀO SQLITE
        timestamp_now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        
        try:
            cursor.execute('''
                INSERT INTO detections (id, timestamp, lat, lng, speed, severity, image_filename, is_synced)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            ''', (pothole_id, timestamp_now, safe_lat, safe_lng, safe_spd, severity, filename))
            conn.commit() # Lưu ngay lập tức
            
            print(f"💾 Saved to DB: {filename} [{severity}]")
        except Exception as e:
            print(f"❌ DB Write Error: {e}")

        # 5. GỬI THÔNG BÁO WEBSOCKET LÊN APP
        full_link = f"{PI_HOST_ADDRESS}/images/{filename}"

        # Chuyển đổi mức độ từ chữ sang số (để App Android hiển thị màu đúng)
        sev_val = 3 if severity == "High" else (2 if severity == "Medium" else 1)

        alert_data = {
            "type": "pothole_detected",
            "data": {
                "id": str(pothole_id),       # String (đã có suffix, không trùng)
                "lat": safe_lat,             # Float chuẩn
                "lng": safe_lng,             # Float chuẩn
                "severity": sev_val,         # Int
                "image_filename": str(filename),
                "image_url": str(full_link)
            }
        }

        # Đẩy vào hàng đợi gửi WebSocket
        try:
            ws_alert_queue.put_nowait(alert_data)
        except queue.Full:
            print("⚠️ WS queue full, dropping alert!")
        
        save_queue.task_done()
    
    conn.close()

    
# --- MODULE 6: MAIN CONTROL (ĐÃ SỬA CẬP NHẬT ẢNH) ---
def get_cpu_temp():
    try:
        with open("/sys/class/thermal/thermal_zone0/temp", "r") as f: return round(float(f.read()) / 1000, 1)
    except: return 0.0

def system_monitor():
    while True:
        try:
            st = os.statvfs(BASE_DIR)
            free_gb = (st.f_bavail * st.f_frsize) / (1024**3)
            shared_state["storage_free"] = f"{free_gb:.1f} GB"
        except:
            shared_state["storage_free"] = "N/A"
        time.sleep(2)

def compute_iou(a, b):
    x1 = max(a[0], b[0])
    y1 = max(a[1], b[1])
    x2 = min(a[2], b[2])
    y2 = min(a[3], b[3])
    inter = max(0, x2-x1) * max(0, y2-y1)
    areaA = max(0, a[2]-a[0]) * max(0, a[3]-a[1])
    areaB = max(0, b[2]-b[0]) * max(0, b[3]-b[1])
    union = areaA + areaB - inter
    return inter/union if union > 0 else 0

def match_det_to_track(track_box, dets, thres=0.1):
    best = None
    best_iou = 0
    for d in dets:
        iou = compute_iou(track_box, d["box"])
        if iou > best_iou:
            best_iou = iou
            best = d
    if best_iou < thres:
        return None
    return best


def build_tracker_input(dets):
    arr = []
    for d in dets:
        x1, y1, x2, y2 = d["box"]
        conf = float(np.clip(d["conf"], 0.0, 1.0))
        arr.append([x1, y1, x2, y2, conf])
    return np.array(arr, dtype=np.float32)




def main():
    init_db()
    threading.Thread(target=run_flask_thread, daemon=True).start()
    threading.Thread(target=run_websocket_thread, daemon=True).start()
    threading.Thread(target=storage_worker, daemon=True).start()

    threading.Thread(target=run_discovery_service, daemon=True).start()
    threading.Thread(target=system_monitor, daemon=True).start()

    print("🌐 Services Started...")

    gps = GPSDriver(); gps.start()
    
    print("🧠 Loading NCNN Seg Model...")
    try:
        model = YoloV8SegNCNN(
            param_path=os.path.join(MODEL_PATH, "model.ncnn.param"),
            bin_path=os.path.join(MODEL_PATH, "model.ncnn.bin"),
            input_size=640
        )
    except Exception as e:
            print(f"❌ Model Error: {e}")
            return

    print("📷 Starting Camera...")
    picam2 = Picamera2()
    # Nếu muốn Tracking mượt hơn, có thể giảm độ phân giải input xuống một chút
    config = picam2.create_video_configuration(main={"size": (RES_W, RES_H), "format": "BGR888"})
    picam2.configure(config)

    # Khóa focus ở vô cực (0.0) để nhìn đường rõ nhất và không bị thò thụt
    picam2.set_controls({"AfMode": 1, "LensPosition": 0.0})
    #picam2.set_controls({"AfMode": 2})
    

    picam2.start()

    print("✅ SYSTEM READY (SMART TRACKING MODE)!")
    # INIT 1 LẦN DUY NHẤT
    tracker = Sort(max_age=15, min_hits=1, iou_threshold=0.15)


    track_buffer = {}
    PATIENCE_LIMIT = 15

    frame_id = 0
    start_time = time.time()
    frame_count = 0

    last_annotated_frame = None

    recent_saved = []
    SAVE_COOLDOWN = 10
    GPS_RADIUS = 0.00002    # ~2m bán kính              


    # =========================================================
    # VÒNG LẶP CHÍNH (MAIN LOOP)
    # =========================================================
    while True:
        try:
            # 1. Capture ảnh gốc
            frame = picam2.capture_array()
            H, W = frame.shape[:2]
        except Exception as e: 
            print(f"Cam Err: {e}")
            break

        # Tính FPS
        frame_count += 1
        if time.time() - start_time > 1:
            shared_state["fps"] = frame_count
            shared_state["cpu_temp"] = get_cpu_temp()
            frame_count = 0
            start_time = time.time()

        frame_id += 1
        
        # ---------------------------------------------------------
        # A. FRAME CHẴN: CHẠY AI & TRACKING (HEAVY WORK)
        # ---------------------------------------------------------
        now = time.time()
        cap_time = now
        
        # Cập nhật GPS & Dọn dẹp bộ nhớ chống trùng lặp cũ
        curr_lat, curr_lng = gps.get_location_at(cap_time)
        curr_speed = gps.speed
        recent_saved = [(la, lo, t) for (la, lo, t) in recent_saved if now - t < SAVE_COOLDOWN]

        # --- BƯỚC 1: AI INFERENCE ---
        dets = model.infer(frame, conf=CONF_THRESHOLD)


        # --- BƯỚC 2: TRACKING UPDATE ---
        # Chỉ update tracker khi có AI mới. Không dùng lại kết quả cũ để tránh loạn Kalman Filter.
        if len(dets) == 0:
            tracks = np.empty((0,5))
        else:
            tracker_input = build_tracker_input(dets)
            tracks = tracker.update(tracker_input)

        # Frame để vẽ (Annotated)
        annotated_frame = frame.copy()
        current_frame_ids = []

        # --- BƯỚC 3: XỬ LÝ TỪNG TRACK ---
        for tx1, ty1, tx2, ty2, tid in tracks:
            tid = int(tid)
            current_frame_ids.append(tid)

            # Tìm lại Mask gốc khớp với ID
            best_det = match_det_to_track([tx1, ty1, tx2, ty2], dets)
            if best_det is None: continue

            mask = best_det["mask"]
            
            x1, y1, x2, y2 = best_det["box"]

            
            # Xử lý Mask
            if mask is None or mask.size == 0: continue
            if mask.dtype != np.uint8: mask = (mask > 0.5).astype(np.uint8) * 255
            
            # Resize mask về kích thước thật (dùng INTER_NEAREST cho nhanh)
            if mask.shape[:2] != (H, W):
                mask = cv2.resize(mask, (W, H), interpolation=cv2.INTER_NEAREST)

            mask_area = cv2.countNonZero(mask)

            # Kiểm tra chạm mép (Edge Rejection)
            is_touching_edge = (x1 < 5 or y1 < 5 or x2 > W-5 or y2 > H-5)


            # Phân loại mức độ
            severity = "Low"
            if mask_area > AREA_THRES_HIGH: severity = "High"
            elif mask_area > AREA_THRES_MED: severity = "Medium"

            # --- BƯỚC 4: BUFFER & BEST SHOT ---
            if tid not in track_buffer:
                # Tracker mới
                track_buffer[tid] = {
                    "best_frame": frame.copy(), 
                    "max_area": mask_area,
                    "lat": curr_lat, "lng": curr_lng, "speed": curr_speed,
                    "severity": severity, 
                    "timestamp": datetime.now().strftime("%Y%m%d_%H%M%S"),
                    "lost_frames": 0, 
                    "is_complete": not is_touching_edge
                }
            else:
                # Update tracker cũ
                track_buffer[tid]["lost_frames"] = 0
                prev = track_buffer[tid]
                
                should_update = False
                # Ưu tiên 1: Ảnh trọn vẹn (không chạm mép)
                if (not prev["is_complete"]) and (not is_touching_edge):
                    should_update = True
                # Ưu tiên 2: Cùng trọn vẹn thì lấy ảnh to hơn
                elif (prev["is_complete"] == (not is_touching_edge)) and (mask_area > prev["max_area"]):
                    should_update = True

                if should_update:
                    track_buffer[tid].update({
                        "best_frame": frame.copy(), 
                        "max_area": mask_area,
                        "severity": severity, 
                        "lat": curr_lat, "lng": curr_lng,
                        "is_complete": not is_touching_edge
                    })

            # --- BƯỚC 5: VẼ (TỐI ƯU HÓA) ---
            color = (0,0,255) if severity=="High" else ((0,165,255) if severity=="Medium" else (0,255,0))
            # Chỉ vẽ Contour (nhẹ gấp 10 lần tô màu pixel)
            contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            cv2.drawContours(annotated_frame, contours, -1, color, 2) 
            cv2.putText(annotated_frame, f"ID:{tid} {severity}", (x1, y1 - 5), 
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)

        # --- BƯỚC 6: DỌN DẸP & LƯU TRỮ ---
        ids_to_remove = []
        for track_id in list(track_buffer.keys()):
            # Nếu ID không còn xuất hiện trong frame hiện tại
            if track_id not in current_frame_ids:
                track_buffer[track_id]["lost_frames"] += 1
                
                # Quá hạn kiên nhẫn -> CHỐT ĐƠN
                if track_buffer[track_id]["lost_frames"] > PATIENCE_LIMIT:
                    data = track_buffer[track_id]
                    
                    # Chỉ lưu nếu diện tích đủ lớn (lọc nhiễu)
                    if data["max_area"] > MIN_SAVE_AREA:
                        # Check trùng lặp GPS
                        is_duplicate = False
                        for la, lo, t in recent_saved:
                            dist = ((data["lat"]-la)**2 + (data["lng"]-lo)**2)**0.5
                            if dist < GPS_RADIUS: 
                                is_duplicate = True
                                break
                        
                        if not is_duplicate:
                            try:
                                save_queue.put_nowait((
                                    data["best_frame"], 
                                    data["lat"], data["lng"], data["speed"], 
                                    data["timestamp"], data["severity"]
                                ))
                                recent_saved.append((data["lat"], data["lng"], now))
                                print(f"✅ Saved ID {track_id} - {data['severity']}")
                            except queue.Full: 
                                print("⚠️ Queue full")
                    
                    ids_to_remove.append(track_id)
        
        # Xóa track đã xử lý xong
        for d_id in ids_to_remove: 
            del track_buffer[d_id]
        
        # Lưu lại ảnh đã vẽ để dùng cho frame sau
        last_annotated_frame = annotated_frame
        video_stream.update(annotated_frame)
            
        # ---------------------------------------------------------
        # B. FRAME LẺ: NGHỈ NGƠI (SKIP AI)
        # ---------------------------------------------------------

    picam2.stop()



# --- MODULE: UDP DISCOVERY SERVICE ---
def run_discovery_service():
    """Lắng nghe tín hiệu tìm kiếm từ App Android"""
    udp_ip = "0.0.0.0"
    udp_port = 9999 # Cổng riêng để tìm kiếm
    
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((udp_ip, udp_port))
    
    print(f"📡 Discovery Service listening on UDP {udp_port}")
    
    while True:
        try:
            data, addr = sock.recvfrom(1024)
            message = data.decode('utf-8')
            
            if message == "MOTO_DISCOVER":
                # App đang hỏi "Pi ở đâu?", trả lời lại ngay
                reply = "MOTO_HERE"
                sock.sendto(reply.encode('utf-8'), addr)
                # print(f"👋 Said hello to {addr}")
        except Exception as e:
            print(f"Discovery Error: {e}")
if __name__ == "__main__":
    main()
