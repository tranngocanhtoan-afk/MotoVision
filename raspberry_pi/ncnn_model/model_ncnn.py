import ncnn
import numpy as np
import cv2
import os

class YoloV8SegNCNN:
    def __init__(self, param_path, bin_path, input_size=640, num_threads=4, use_gpu=False):
        self.input_size = input_size
        self.net = ncnn.Net()
        
        # Tối ưu hóa
        self.net.opt.use_vulkan_compute = use_gpu
        self.net.opt.num_threads = num_threads

        # Load model
        self.net.load_param(param_path)
        self.net.load_model(bin_path)
        
        # --- TỰ ĐỘNG DÒ TÊN LAYER TỪ FILE PARAM ---
        self.input_name = "in0"      # Default fallback
        self.output_name = "out0"    # Detection box
        self.mask_name = "out1"      # Segmentation mask
        
        try:
            with open(param_path, 'r') as f:
                lines = f.readlines()
                # Dòng đầu là magic number, dòng 2 là count
                for line in lines[2:]:
                    parts = line.split()
                    if len(parts) < 3: continue
                    layer_type = parts[0]
                    
                    # Tìm Input Blob (Cấu trúc: Input [name] 0 1 [blob_name])
                    if layer_type == "Input":
                        self.input_name = parts[-1] # Lấy token cuối cùng
                        print(f"🔍 Auto-detected Input: {self.input_name}")
                        
                    # Tìm Output Blobs (Thường là output0/out0/output)
                    # PNNX thường đặt tên output blob khớp với tên layer
                    # Chúng ta sẽ thử các tên phổ biến trong hàm infer nếu auto-detect thất bại
        except Exception as e:
            print(f"⚠️ Warning parsing param: {e}")

        # Danh sách tên output dự phòng
        self.possible_outputs = ["out0", "output0", "output", "det0", "defs"]
        self.possible_masks = ["out1", "output1", "mask1", "seg0", "proto"]

    def infer(self, image, conf=0.25, iou=0.45):
        h_img, w_img = image.shape[:2]
        
        # 1. Preprocess
        scale = min(self.input_size / w_img, self.input_size / h_img)
        w_new, h_new = int(w_img * scale), int(h_img * scale)
        mat_in = ncnn.Mat.from_pixels_resize(
            image, ncnn.Mat.PixelType.PIXEL_BGR, w_img, h_img, w_new, h_new
        )
        
        w_pad = self.input_size - w_new
        h_pad = self.input_size - h_new
        mat_in_pad = ncnn.Mat()
        ncnn.copy_make_border(
            mat_in, mat_in_pad, 0, h_pad, 0, w_pad, ncnn.BorderType.BORDER_CONSTANT, 114.0
        )
        mat_in_pad.substract_mean_normalize([], [1/255.0, 1/255.0, 1/255.0])

        # 2. Inference
        ex = self.net.create_extractor()
        
        # Set Input (Dùng tên đã dò được)
        ex.input(self.input_name, mat_in_pad)
        
        # 3. Extract Output an toàn (Chống Segfault)
        ret0, out0 = -1, None
        ret1, out1 = -1, None
        
        # Thử lần lượt các tên output phổ biến
        for name in self.possible_outputs:
            ret0, out0 = ex.extract(name)
            if ret0 == 0: 
                # print(f"✅ Found Det: {name}") 
                break
                
        for name in self.possible_masks:
            ret1, out1 = ex.extract(name)
            if ret1 == 0: 
                # print(f"✅ Found Mask: {name}")
                break

        # Nếu không lấy được output -> Dừng ngay lập tức
        if ret0 != 0:
            # print("❌ Detection output not found!")
            return []
            
        if ret1 != 0:
            # print("❌ Mask output not found!")
            return [] # Segmentation bắt buộc phải có mask

        # Convert to Numpy
        # [CRITICAL] Kiểm tra object hợp lệ trước khi truy cập
        try:
            out0 = np.array(out0) 
            out1 = np.array(out1)
        except:
            return []

        # --- FIX: SAFE SQUEEZE ---
        # Chỉ ép chiều nếu thừa (1, 116, 8400) -> (116, 8400)
        if len(out0.shape) == 3: out0 = np.squeeze(out0, axis=0)
        if len(out1.shape) == 4: out1 = np.squeeze(out1, axis=0)
        
        # Transpose (8400, 116)
        out0 = out0.T 
        
        # 4. Post-process
        num_channels = out0.shape[1]
        num_mask_protos = 32
        num_classes = num_channels - 4 - num_mask_protos
        
        if num_classes <= 0: return []

        class_scores = out0[:, 4:4+num_classes]
        max_scores = np.max(class_scores, axis=1)
        class_ids = np.argmax(class_scores, axis=1)
        
        mask_conf = max_scores > conf
        out0_filtered = out0[mask_conf]
        scores_filtered = max_scores[mask_conf]
        classes_filtered = class_ids[mask_conf]
        
        if len(out0_filtered) == 0: return []

        box_data = out0_filtered[:, 0:4]
        mask_coeffs = out0_filtered[:, 4+num_classes:]

        boxes_xyxy = []
        for i in range(len(box_data)):
            cx, cy, w, h = box_data[i]
            x1 = max(0, (cx - w/2) / scale)
            y1 = max(0, (cy - h/2) / scale)
            x2 = min(w_img, (cx + w/2) / scale)
            y2 = min(h_img, (cy + h/2) / scale)
            boxes_xyxy.append([int(x1), int(y1), int(x2), int(y2)])

        indices = cv2.dnn.NMSBoxes(boxes_xyxy, scores_filtered.tolist(), conf, iou)
        
        final_dets = []
        if len(indices) > 0:
            indices = indices.flatten()
            for i in indices:
                # Decode Mask
                coeffs = mask_coeffs[i]
                # (1,32) @ (32, 160*160)
                mask_raw = np.matmul(coeffs, out1.reshape(32, -1)).reshape(160, 160)
                mask_sig = 1 / (1 + np.exp(-mask_raw))
                
                # Resize lên input size (640x640)
                mask_resized = cv2.resize(mask_sig, (self.input_size, self.input_size))
                # Crop bỏ padding
                mask_crop = mask_resized[:h_new, :w_new]
                # Binarize
                mask_bin = (mask_crop > 0.5).astype(np.uint8) * 255
                
                # Resize về ảnh gốc
                mask_final = cv2.resize(mask_bin, (w_img, h_img), interpolation=cv2.INTER_NEAREST)
                
                # Clean mask theo box
                x1, y1, x2, y2 = boxes_xyxy[i]
                mask_clean = np.zeros_like(mask_final)
                # Clip coordinates
                y1, y2 = max(0, y1), min(h_img, y2)
                x1, x2 = max(0, x1), min(w_img, x2)
                mask_clean[y1:y2, x1:x2] = mask_final[y1:y2, x1:x2]

                final_dets.append({
                    "box": boxes_xyxy[i],
                    "conf": scores_filtered[i],
                    "class": classes_filtered[i],
                    "mask": mask_clean
                })
                
        return final_dets