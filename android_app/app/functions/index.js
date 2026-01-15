const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");

initializeApp();
const db = getFirestore();

// ==========================================
// HÀM HỖ TRỢ TÍNH TOÁN
// ==========================================

// Tính khoảng cách giữa 2 điểm (Haversine) - Trả về mét
function getDistanceFromLatLonInM(lat1, lon1, lat2, lon2) {
  const R = 6371000; // Bán kính trái đất (m)
  const dLat = deg2rad(lat2 - lat1);
  const dLon = deg2rad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(deg2rad(lat1)) * Math.cos(deg2rad(lat2)) *
    Math.sin(dLon / 2) * Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

function deg2rad(deg) {
  return deg * (Math.PI / 180);
}

// ==========================================
// CLOUD FUNCTION: UPLOAD & CLUSTER
// ==========================================

exports.uploadPothole = onCall(async (request) => {
  const data = request.data; // Dữ liệu từ Android gửi lên
  
  // 1. Validate dữ liệu
  if (!data.lat || !data.lng) {
    throw new HttpsError('invalid-argument', 'Thiếu toạ độ GPS');
  }

  const newLat = parseFloat(data.lat);
  const newLng = parseFloat(data.lng);
  const newSeverity = normalizeSeverity(data.severity);
  const imageUrl = data.imageUrl || "";
  const potholeId = data.id;

  // 2. Xác định phạm vi tìm kiếm (Bounding Box ~20m)
  // 0.0001 độ vĩ độ ~ 11.1 mét. Ta lấy range +/- 0.00015 để chắc chắn bao phủ
  const latRange = 0.00015;
  const minLat = newLat - latRange;
  const maxLat = newLat + latRange;

  const collectionRef = db.collection('potholes');

  // 3. Truy vấn Firestore (Chỉ lấy các điểm lân cận để tối ưu tốc độ)
  // Lưu ý: Firestore chỉ filter range trên 1 field, field còn lại ta lọc bằng code
  const snapshot = await collectionRef
    .where('lat', '>=', minLat)
    .where('lat', '<=', maxLat)
    .get();

  let closestDoc = null;
  let minDistance = 10.0; // Bán kính gộp nhóm (10 mét)

  // 4. Duyệt qua các điểm ứng viên để tìm điểm gần nhất thật sự
  snapshot.forEach(doc => {
    const p = doc.data();
    const dist = getDistanceFromLatLonInM(newLat, newLng, p.lat, p.lng);
    
    if (dist < minDistance) {
      minDistance = dist;
      closestDoc = doc;
    }
  });

  // 5. QUYẾT ĐỊNH: GỘP (UPDATE) HAY TẠO MỚI (CREATE)
  if (closestDoc) {
    // --- TRƯỜNG HỢP GỘP ---
    const docId = closestDoc.id;
    const oldData = closestDoc.data();

    // Logic nâng cấp:
    // - Nếu severity mới cao hơn thì cập nhật
    // - Tăng biến đếm số lần phát hiện (để biết ổ gà này uy tín)
    // - Thêm ảnh vào mảng images (thay vì nối chuỗi string)
    
    const updates = {
      last_updated: FieldValue.serverTimestamp(),
      detection_count: FieldValue.increment(1) // Tăng độ tin cậy
    };

    // Chỉ update severity nếu mức độ mới cao hơn
    if (newSeverity > (oldData.severity || 0)) {
      updates.severity = newSeverity;
    }

    // Thêm ảnh vào mảng (nếu có ảnh và chưa tồn tại)
    if (imageUrl) {
      updates.images_list = FieldValue.arrayUnion(imageUrl);
      // Giữ field 'images' cũ (string) để tương thích ngược với App cũ của bạn
      updates.images = oldData.images ? (oldData.images + ",\n" + imageUrl) : imageUrl;
    }

    await collectionRef.doc(docId).update(updates);

    return { status: "merged", id: docId, distance: minDistance };

  } else {
    // --- TRƯỜNG HỢP TẠO MỚI ---
    const newDoc = {
      id: potholeId,
      lat: newLat,
      lng: newLng,
      severity: newSeverity,
      timestamp: Date.now(),
      created_at: FieldValue.serverTimestamp(),
      last_updated: FieldValue.serverTimestamp(),
      images: imageUrl, // String (tương thích cũ)
      images_list: imageUrl ? [imageUrl] : [], // Array (cấu trúc mới tốt hơn)
      detection_count: 1,
      is_verified: true
    };

    await collectionRef.doc(potholeId).set(newDoc);
    return { status: "created", id: potholeId };
  }
});

function normalizeSeverity(input) {
    const s = String(input).toLowerCase();
    if (s.includes("3") || s.includes("high")) return 3;
    if (s.includes("2") || s.includes("med")) return 2;
    return 1;
}