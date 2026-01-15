\# 🏍️ MotoVision: Edge-based Pothole Detection \& Reporting System



\[!\[Platform](https://img.shields.io/badge/Platform-Raspberry%20Pi%204-red)](https://www.raspberrypi.org/)

\[!\[AI Model](https://img.shields.io/badge/AI-YOLOv8--Seg%20(NCNN)-blue)](https://github.com/Tencent/ncnn)

\[!\[Mobile](https://img.shields.io/badge/App-Android-green)](https://developer.android.com/)

\[!\[Cloud](https://img.shields.io/badge/Backend-Firebase-orange)](https://firebase.google.com/)



\*\*MotoVision\*\* is an end-to-end AIoT solution designed to detect potholes in real-time, log their geospatial location, and facilitate community-driven road maintenance reporting.



Using a lightweight \*\*Instance Segmentation\*\* model running on the edge (Raspberry Pi), the system identifies road damages and syncs verified data to the cloud via a companion Android Application.



---



\## 📖 Table of Contents

\- \[Overview](#-overview)

\- \[System Architecture](#-system-architecture)

\- \[Key Features](#-key-features)

\- \[Hardware Requirements](#-hardware-requirements)

\- \[Tech Stack](#-tech-stack)

\- \[Directory Structure](#-directory-structure)

\- \[Installation \& Setup](#-installation--setup)

\- \[Results \& Performance](#-results--performance)

\- \[Team \& Acknowledgments](#-team--acknowledgments)



---



\## 🔭 Overview



Road maintenance in developing countries often suffers from delayed reporting and high manual inspection costs. MotoVision solves this by automating the detection process.



The system captures road imagery, processes it locally using \*\*YOLOv8n-seg\*\* optimized with \*\*NCNN\*\*, coordinates with \*\*GNSS\*\* for precise location, and allows users to verify and upload incidents via a mobile app.



---



\## 🏗 System Architecture



The project follows a decoupled \*\*Edge-Mobile-Cloud\*\* architecture:



```mermaid

graph TD

&nbsp;   subgraph "Layer 1: Edge Node (Raspberry Pi)"

&nbsp;       Cam\[Pi Camera v3] -->|Frames| AI\[NCNN Inference Engine]

&nbsp;       GPS\[GNSS ATGM336H] -->|NMEA Data| Kalman\[Kalman Filter]

&nbsp;       AI -->|Best Shot Selection| Buffer\[Event Buffer]

&nbsp;       Kalman -->|Sync Location| Buffer

&nbsp;       Buffer -->|MJPEG Stream| Wifi\[WebSocket/HTTP]

&nbsp;   end



&nbsp;   subgraph "Layer 2: Mobile App (Android)"

&nbsp;       Wifi <-->|Stream \& Alerts| App\[MotoVision App]

&nbsp;       App -->|Verify \& Upload| Cloud\[Firebase]

&nbsp;       Map\[Google Maps SDK] -.->|Overlay| App

&nbsp;   end



&nbsp;   subgraph "Layer 3: Cloud (Firebase)"

&nbsp;       Cloud -->|Metadata| Firestore

&nbsp;       Cloud -->|Images| Storage

&nbsp;   end

