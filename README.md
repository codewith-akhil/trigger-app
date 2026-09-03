# Trigger App ⚡

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Room](https://img.shields.io/badge/Database-Room%20Persistence-F58220?style=flat&logo=sqlite&logoColor=white)](https://developer.android.com/training/data-storage/room)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-24-blue.svg)](https://developer.android.com/)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36-green.svg)](https://developer.android.com/)
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](LICENSE)

**Trigger App** is a modern, responsive, feature-rich messaging and communication Android application built natively from the ground up with **Jetpack Compose (Material Design 3)**, **Kotlin Coroutines & Flow**, **Clean Architecture**, and an **Offline-First Room Database**.

Designed with authentic styling inspired by modern communication platforms like WhatsApp, Trigger App provides an end-to-end user journey: comprehensive authentication (Email + Password + 6-Digit OTP verification + Password Reset), rich real-time chat threads with voice notes and media sharing, interactive audio and video calling overlays, user profile customization with custom usernames, and contacts management.

---

## 📑 Table of Contents

- [Key Features](#-key-features)
  - [🔐 Authentication & Verification](#-authentication--verification)
  - [💬 Chats & Dashboard](#-chats--dashboard)
  - [🎙️ Interactive Chat Experience](#-interactive-chat-experience)
  - [📞 Audio & Video Calling](#-audio--video-calling)
  - [👤 User Profile Management](#-user-profile-management)
  - [👥 Contact Picker & New Chat](#-contact-picker--new-chat)
  - [💾 Offline-First Architecture](#-offline-first-architecture)
- [Architecture & Design Principles](#-architecture--design-principles)
- [Tech Stack & Libraries](#-tech-stack--libraries)
- [Project Directory Structure](#-project-directory-structure)
- [User Journey & Navigation Flow](#-user-journey--navigation-flow)
- [Getting Started](#-getting-started)
  - [Prerequisites](#prerequisites)
  - [Build and Run](#build-and-run)
  - [Running Unit & Screenshot Tests](#running-unit--screenshot-tests)
- [Security & Best Practices](#-security--best-practices)
- [Contributing](#-contributing)
- [License](#-license)

---

## ✨ Key Features

### 🔐 Authentication & Verification
- **Email & Password Authentication**: Clean login screen with field validation, password visibility toggle, error handling, and direct links to Registration and Forgot Password.
- **Sign Up Flow**: Registration collecting Full Name, Email, Password, and Confirm Password with real-time validation.
- **6-Digit Email OTP Verification**: Authentic multi-box PIN input with automatic auto-advance, support for a built-in Gboard numeric keypad, a 60-second resend countdown timer, and an interactive simulated email notification banner with one-tap auto-fill.
- **Forgot & Reset Password**: Secure self-service account recovery flow verifying user identity via OTP before letting the user establish a new password.
- **Dynamic Onboarding**: Multi-language support selection (English, Spanish, Hindi, Arabic, etc.) and permission handling dialogs (Android 13+ Notification Runtime Permissions).

### 💬 Chats & Dashboard
- **WhatsApp-Style Multi-Tab Interface**: Four main tabs:
  - **Chats**: Active conversation threads with unread counters, delivery status ticks (sent, delivered, read), pinned chats, and last message previews.
  - **Updates / Status**: Status stories section with recent updates and camera status creator.
  - **Communities**: Group and community hubs.
  - **Calls**: Call history logs with incoming/outgoing indicators and quick call triggers.
- **Interactive Top App Bar**: Quick search filter across messages and contacts, camera shortcut, and an overflow options dropdown.
- **Live Online / Offline Simulation**: Built-in network state toggle to test and demonstrate offline-first caching and presence indicators.

### 🎙️ Interactive Chat Experience
- **Voice Notes**: Voice recording animation with waveform visualizer and audio player controls with progress tracking.
- **Media & Photo Attachments**: Seamless integration with image pickers, image previews, and a dedicated full-screen media viewer (`ChatMediaViewer.kt`) with zoom support.
- **Location Sharing**: Interactive map picker preview (`SendLocationScreen.kt`) allowing users to share current or selected GPS coordinates.
- **Emoji & Sticker Picker**: Comprehensive bottom sheet picker categorized with smiles, animals, food, sports, and objects.
- **Message Status Indicators**: Real-time message receipt indicators (Single grey tick = Sent, Double grey tick = Delivered, Double blue tick = Read).

### 📞 Audio & Video Calling
- **Calling Overlays (`ChatCallingOverlay.kt`)**: WhatsApp-style full-screen calling experience.
- **Call States**: Dialing, Ringing, and Active Call with a real-time call duration timer (`00:15`, `00:16`, etc.).
- **Interactive Call Controls**: Speakerphone toggle, microphone mute/unmute, front/rear camera toggle, and end call button.

### 👤 User Profile Management
- **Dark Theme Profile Screen (`ProfileScreen.kt`)**: Centered circular avatar with a green floating camera button integrated with the Android Photo Picker (`ActivityResultContracts.PickVisualMedia`).
- **Profile Sections**:
  - **Name**: Edit and persist display name.
  - **About**: Custom status message (e.g. "Available", "At work", "In a meeting").
  - **Username**: Dedicated username reservation with `@` handle support.
  - **Email**: Displays verified account email address.
  - **Links**: Add external URLs, personal portfolios, or social media handles.
- **In-Place Dialog Editors**: Tap any row to modify profile attributes with immediate state synchronization via `UserRepository`.

### 👥 Contact Picker & New Chat
- **Select Contact (`SelectContactScreen.kt`)**: Accessible via the New Chat Floating Action Button (FAB).
- **Header Actions**: One-tap triggers for **New group**, **New contact** (with QR code), and **New community**.
- **Self-Chat**: Includes "Message yourself" for personal notes and reminders.
- **Instant Search**: Live search filtering by contact name or status subtitle.

### 💾 Offline-First Architecture
- **Room SQLite Persistence**: Conversations and messages are persisted locally in `ChatDatabase`.
- **Reactive Data Streams**: Room DAOs return Kotlin Coroutines `Flow`, ensuring the UI updates instantaneously when new messages or conversations arrive.
- **Repository Abstraction**: `ChatRepositoryImpl` abstracts network and database layers, ensuring seamless offline functionality.

---

## 🏛️ Architecture & Design Principles

Trigger App is built using modern **Clean Architecture** combined with **MVVM (Model-View-ViewModel)** and **Unidirectional Data Flow (UDF)**:

```
┌────────────────────────────────────────────────────────┐
│                   UI Layer (Jetpack Compose)           │
│  - Screens (WhatsAppDashboard, Chat, Profile, etc.)    │
│  - Components (CustomNumpad, MediaViewer, etc.)        │
│  - ViewModels (DashboardViewModel, ChatViewModel)      │
└───────────────────────────▲────────────────────────────┘
                            │ UI State & Events
┌───────────────────────────▼────────────────────────────┐
│                      Domain & Model Layer              │
│  - Domain Models (Conversation, Message, UserProfile)  │
│  - Service Contracts (MessageService, CallService)     │
└───────────────────────────▲────────────────────────────┘
                            │ Data Flow
┌───────────────────────────▼────────────────────────────┐
│                      Data Layer                        │
│  - ChatRepositoryImpl (Repository pattern)             │
│  - Room Database (ChatDatabase, MessageDao)            │
│  - Services (PresenceService, UploadService)           │
└────────────────────────────────────────────────────────┘
```

- **Unidirectional Data Flow (UDF)**: ViewModels expose immutable `StateFlow` streams that Composables collect via `collectAsStateWithLifecycle()`. UI events trigger ViewModel methods, preventing mutable state leaks.
- **Edge-to-Edge**: Supports modern Android edge-to-edge layouts using `enableEdgeToEdge()` and WindowInsets handling across all screen sizes.
- **Material Design 3**: Complete color scheme mapping for both dark and light modes, typography scales, and 48dp minimum touch target compliance.

---

## 🛠️ Tech Stack & Libraries

| Category | Technology | Description |
|---|---|---|
| **Language** | [Kotlin](https://kotlinlang.org/) | 100% modern Kotlin with Coroutines and Flow |
| **UI Toolkit** | [Jetpack Compose](https://developer.android.com/jetpack/compose) | Declarative UI framework with Compose Material 3 |
| **Navigation** | [Navigation Compose](https://developer.android.com/jetpack/compose/navigation) | Type-safe navigation routing and backstack management |
| **Local Database** | [Room Database](https://developer.android.com/training/data-storage/room) | SQLite abstraction layer with KSP code generation |
| **Image Loading** | [Coil Compose](https://coil-kt.github.io/coil/) | Lightweight, asynchronous image loading library |
| **Networking** | [Retrofit](https://square.github.io/retrofit/) & [OkHttp](https://square.github.io/okhttp/) | Type-safe HTTP client with logging interceptor |
| **Serialization** | [Moshi Kotlin](https://github.com/square/moshi) | JSON parsing and code generation with KSP |
| **Testing** | [Robolectric](https://robolectric.org/) & [Roborazzi](https://github.com/takahirom/roborazzi) | JVM unit testing and screenshot regression testing |
| **Dependency Injection** | Service Container / Manual DI | Lightweight, clean dependency injection container |

---

## 📂 Project Directory Structure

```
app/src/main/java/com/example/
├── MainActivity.kt                      # Application entry point with Edge-to-Edge
├── config/                             # App-wide configuration & constants
├── data/
│   ├── local/
│   │   ├── ChatDatabase.kt             # Room Database configuration
│   │   ├── ConversationDao.kt          # DAO for conversation list
│   │   ├── ConversationEntity.kt       # Room entity for chat threads
│   │   ├── MessageDao.kt               # DAO for individual chat messages
│   │   └── MessageEntity.kt            # Room entity for message records
│   └── repository/
│       └── ChatRepositoryImpl.kt       # Repository implementation with local persistence
├── di/
│   └── AppServiceContainer.kt          # Dependency injection container & service locator
├── model/
│   ├── ChatDomainModels.kt             # Domain entities for messages & threads
│   ├── ChatModels.kt                   # UI models and attachment types
│   ├── Country.kt                      # Country codes and dial information
│   ├── Language.kt                     # Localization languages
│   └── UserProfile.kt                  # User profile state & in-memory repository
├── service/
│   ├── CallService.kt                  # Audio/Video call state management
│   ├── MessageService.kt               # Message delivery simulation
│   ├── PresenceService.kt              # Online/offline user presence tracker
│   └── UploadService.kt                # Media upload & file handling
└── ui/
    ├── components/
    │   ├── CustomNumpad.kt             # WhatsApp-style custom numeric keypad
    │   ├── HelpSheet.kt                # Help & support bottom sheet
    │   └── NotificationPermissionDialog.kt # Runtime notification prompt
    ├── navigation/
    │   └── TriggerAppNavHost.kt        # Complete navigation graph & routes
    ├── screens/
    │   ├── LandingScreen.kt            # Welcome onboarding & Terms acceptance
    │   ├── EmailAuthScreen.kt          # Email & Password login
    │   ├── SignUpScreen.kt             # Account creation screen
    │   ├── EmailOtpVerificationScreen.kt # 6-digit email OTP verification
    │   ├── ForgotPasswordScreen.kt     # Email lookup for password recovery
    │   ├── ResetPasswordScreen.kt      # Password reset submission
    │   ├── WhatsAppDashboardScreen.kt  # Main 4-tab dashboard
    │   ├── ChatScreen.kt               # Live chat thread with media & voice notes
    │   ├── ChatCallingOverlay.kt       # Voice & video calling screen
    │   ├── ChatMediaViewer.kt          # Full-screen image attachment viewer
    │   ├── ProfileScreen.kt            # User profile, username, avatar & details
    │   ├── SelectContactScreen.kt      # Contact picker & New Chat selector
    │   └── SendLocationScreen.kt       # Google Maps location picker preview
    ├── theme/
    │   ├── Color.kt                    # Brand palette (WhatsApp green, dark surfaces)
    │   ├── Theme.kt                    # Material 3 theme wrapper
    │   └── Type.kt                     # Typography system
    └── viewmodel/
        └── DashboardViewModel.kt       # State manager for chats & presence
```

---

## 🗺️ User Journey & Navigation Flow

```
[ Welcome / Landing ]
         │
         ├───▶ [ Language Selection ]
         │
         ▼
[ Email + Password Login ] ───▶ [ Forgot Password ] ───▶ [ OTP Verification ] ───▶ [ Reset Password ]
         │                                                        ▲
         ├───▶ [ Sign Up Screen ] ────────────────────────────────┘
         │
         ▼ (Authentication Successful)
┌─────────────────────────────────────────────────────────────────┐
│                    WhatsApp Dashboard                           │
│   ├── [ Chats Tab ] ──────▶ [ Select Contact / New Message ]    │
│   │         │                                                   │
│   │         └─────────────▶ [ Chat Screen ]                     │
│   │                               ├──▶ [ Audio / Video Call ]   │
│   │                               ├──▶ [ Media Viewer ]         │
│   │                               └──▶ [ Location Sharing ]     │
│   ├── [ Updates / Status Tab ]                                  │
│   ├── [ Communities Tab ]                                       │
│   └── [ Calls Tab ]                                             │
└────────────────────────────────┬────────────────────────────────┘
                                 │
                                 ▼
                     [ User Profile Screen ]
               (Avatar, @Username, Email, About, Links)
```

---

## 🚀 Getting Started

### Prerequisites
- **Android Studio**: Ladybug (2024.2.1) or newer.
- **JDK**: Version 17 or higher.
- **Android SDK**: Minimum API 24 (Android 7.0), Target API 36 (Android 15+).
- **Gradle**: 8.x with Kotlin DSL (`build.gradle.kts`).

### Build and Run

1. **Clone the repository**:
   ```bash
   git clone https://github.com/your-username/trigger-app.git
   cd trigger-app
   ```

2. **Open in Android Studio**:
   - Open Android Studio and select **Open**.
   - Navigate to the cloned `trigger-app` folder and click **OK**.
   - Let Gradle sync the project dependencies.

3. **Build the Debug APK**:
   ```bash
   gradle assembleDebug
   ```

4. **Run on an Emulator or Device**:
   - Select an emulator (API 24+) or connect a physical Android device via USB debugging.
   - Click **Run** (`Shift + F10`) in Android Studio.

### Running Unit & Screenshot Tests

The project includes JVM unit tests and screenshot regression tests using **Robolectric** and **Roborazzi**:

- **Run Unit Tests**:
  ```bash
  gradle :app:testDebugUnitTest
  ```
- **Verify Screenshot Tests**:
  ```bash
  gradle :app:verifyRoborazziDebug
  ```
- **Record New Reference Screenshots**:
  ```bash
  gradle :app:recordRoborazziDebug
  ```

---

## 🔒 Security & Best Practices

- **Zero Broad Storage Permissions**: In accordance with Google Play Developer Program policies, the app uses Android's privacy-preserving Photo Picker (`ActivityResultContracts.PickVisualMedia`) for media selection without requiring broad storage permissions (`READ_EXTERNAL_STORAGE`).
- **Secure Credentials Handling**: API keys and external secrets are loaded using the Secrets Gradle Plugin via `.env` (avoiding hardcoded secrets in source code).
- **Accessibility Ready**: Minimum touch targets of 48dp are enforced across all interactive elements, with descriptive `contentDescription` attributes on all icons and visual elements.
- **Room Database Sanitization**: Parameterized queries in Room DAOs protect against SQL injection.

---

## 🤝 Contributing

Contributions, issues, and feature requests are welcome!

1. **Fork the Project**.
2. **Create your Feature Branch**:
   ```bash
   git checkout -b feature/AmazingFeature
   ```
3. **Commit your Changes**:
   ```bash
   git commit -m "Add some AmazingFeature"
   ```
4. **Push to the Branch**:
   ```bash
   git push origin feature/AmazingFeature
   ```
5. **Open a Pull Request**.

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
