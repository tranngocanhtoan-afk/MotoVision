# MotoVision: Edge-based Pothole Detection & Reporting System

MotoVision is an end-to-end AIoT solution designed to detect potholes in real-time, log their geospatial location, and facilitate community-driven road maintenance reporting.

Using a lightweight Instance Segmentation model running on the edge (Raspberry Pi), the system identifies road damages and syncs verified data to the cloud via a companion Android Application.

---

## Table of Contents

- [Overview](#-overview)
- [System Architecture](#-system-architecture)
- [Key Features](#-key-features)
- [Hardware Requirements](#-hardware-requirements)
- [Tech Stack](#-tech-stack)
- [Directory Structure](#-directory-structure)
- [Installation & Setup](#-installation--setup)
- [Results & Performance](#-results--performance)
- [Team & Acknowledgments](#-team--acknowledgments)

---

## Overview

Road maintenance in developing countries often suffers from delayed reporting and high manual inspection costs. **MotoVision** addresses this challenge by implementing an end-to-end AIoT solution that:

1.  **Real-time Edge Detection:** Leverages a lightweight **YOLOv8n-seg model optimized with NCNN** running directly on a Raspberry Pi to identify road damages and segment pothole boundaries instantly without cloud dependency.
2.  **Precise Geospatial Localization:** Integrates **GNSS data with Kalman Filter** smoothing to accurately synchronize detected incidents with their real-world coordinates, ensuring reliable mapping even at varying speeds.
3.  **Human-in-the-Loop Verification:** Connects to a companion **Android Application** that allows users to review, verify, or discard detections before uploading, significantly reducing false positive rates.
4.  **Community-Driven Reporting:** Facilitates a seamless data pipeline where verified road damage data is synced to the cloud, creating a crowdsourced database for efficient infrastructure maintenance.

The system workflow is divided into two primary stages:

* **Edge Processing (On-Device):** Captures video frames, performs instance segmentation, and filters "Best Shot" evidence locally.
* **Mobile Coordination (User Interaction):** Receives alerts via WebSocket, displays live tracking overlays, and handles the final data transmission to Firebase.

---

## System Architecture

The project follows a decoupled **Edge–Mobile–Cloud** architecture:

![system architecture](https://github.com/user-attachments/assets/2cde1def-b662-4e52-acdb-dd479d867d5c)

---
## Key Features

### Edge Intelligence (Raspberry Pi)

* **Instance Segmentation:** Uses **YOLOv8n-seg** to detect pothole boundaries and estimate severity based on pixel area.
* **NCNN Optimization:** Optimized for ARM CPUs, achieving functional framerates without a discrete GPU.
* **Smart Tracking (SORT):** Prevents duplicate logs for the same pothole using IoU-based tracking.
* **"Best Shot" Logic:** Automatically selects the clearest, centered frame of a pothole to save as evidence.
* **GNSS Smoothing:** Implements **Kalman Filter** + interpolation to sync 30fps video with 1Hz GPS data.

### Mobile Application (Android)

* **Split-Screen Interface:** Live MJPEG stream (top) + Google Maps navigation (bottom).
* **Human-in-the-loop Verification:** User confirms detections before upload to reduce false positives.
* **Real-time Alerts:** WebSocket notifications on pothole detection.

### Hardware Requirements

Based on the Bill of Materials:

| Component | Specification | Function |
| :--- | :--- | :--- |
| **SBC** | Raspberry Pi 4 Model B (4GB) | Main processing unit |
| **Camera** | Pi Camera Module v3 | Image acquisition (CSI) |
| **GNSS** | ATGM336H (GPS+BDS) | Geospatial localization (UART) |
| **Storage** | MicroSD Card (64GB Class 10) | OS and local dataset storage |
| **Power** | 5V/3A Power Supply | Stable power for Pi & peripherals |

## Tech Stack
### Edge (Raspberry Pi)
* **Python 3.9+**
* **NCNN (Tencent)**
* **OpenCV**
* **SQLite** (local logging)

### Mobile (Android)
* **Kotlin / Java**
* **Google Maps SDK**
* **MPAndroidChart** (optional)

### Cloud (Firebase)
* **Firebase Firestore** (metadata)
* **Firebase Storage** (evidence images)

## 📂 Directory Structure
```text
MotoVision-Project/
├── android_app/                # Android Studio Project
│   ├── app/
│   │   ├── src/                # Java/Kotlin source code
│   │   └── google-services.json # (Ignored) Firebase Configuration
│   ├── local.properties        # (Ignored) Maps API Key
│   └── build.gradle.kts        # Build configuration
├── raspberry_pi/               # Edge Device Source Code
│   ├── ncnn_model/             # Quantized YOLOv8 models
│   ├── main_app_updated.py     # Main execution script
│   ├── sort.py                 # Tracking algorithm
│   ├── serviceAccountKey.json  # (Ignored) Firebase Admin Key
│   └── requirements.txt        # Python dependencies
└── docs/                       # Documentation & Project Reports
```
## Installation & Setup

### Part 1: Raspberry Pi (Edge)

1. **Clone repository:**
   ```bash
   git clone [https://github.com/tranngocanhtoan-afk/MotoVision.git](https://github.com/tranngocanhtoan-afk/MotoVision.git)
   cd MotoVision/raspberry_pi
   ```


2. **Install dependencies:**
   ```bash
   pip install -r requirements.txt
   ```

**Requires:**
```bash
opencv-python, ncnn, pyserial, firebase-admin
```
**Setup secrets:**
```bash
Put serviceAccountKey.json inside raspberry_pi/ (git-ignored).
```
**Run:**
```bash
python main_app_updated.py
```

### Part 2: Android App

**Open**
```bash
android_app/ in Android Studio
```
**Create**
```bash
local.properties:
```
```bash
MAPS_API_KEY=AIzaSyDxxxxxxxxx_Your_Key
```
**Download** 
```bash
google-services.json from Firebase Console and place it in android_app/app/
```
```bash
Build & Run
```
## Results & Performance

* **Dataset:** Trained on **2,254 images** (augmented) collected from Vietnamese roads.
* **Accuracy:** mAP@50 (Mask) = **0.726**, outperforming bbox-only approaches.
* **Speed:** ~4 FPS on Raspberry Pi 4 CPU using **NCNN**. The buffering "Best Shot" logic helps avoid missing critical events at city speeds.

---

## Team & Acknowledgments

* **Project Course:** MotoVision - Vietnamese-German University (VGU)
* **Instructor:** Dr. Vo Bich Hien

### Student Team
* **Nguyen Gia Thong** (10422117)
* **Le Ba Thai Quan** (10421102)
* **Tran Ngoc Anh Toan** (10422118)

### References
* **YOLOv8** by Ultralytics
* **NCNN** by Tencent
