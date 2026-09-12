# TRIGGER APP - NEW UI & PAYMENT ARCHITECTURE SPECIFICATION (`NEWUI.MD`)

---

## 1. Executive Summary

This document details the newly created **Post View Page**, **Payment Overview Page**, **Razorpay Gateway Integration**, and **Payment Validation Page**, alongside the Instagram-model commenting architecture, like counters, and media interaction pipelines.

---

## 2. Page Hierarchy & User Flow

```
+-------------------------------------------------------------------------+
|                              UPDATES TAB                                |
|  (Feed Tab: Status tray, Instagram feed cards, "Pay and Watch" prompt)   |
+-----------------------------------+-------------------------------------+
                                    |
            +-----------------------+-----------------------+
            | Taps post / comment                          | Taps "Pay and Watch"
            v                                               v
+-------------------------------+       +---------------------------------+
|        POST VIEW PAGE         |       |      PAYMENT OVERVIEW PAGE      |
|  - Black immersion canvas     |       |  - Order itemized breakdown     |
|  - Full-screen media player   |       |  - Creator access summary       |
|  - Instagram comment section  |       |  - Unlock guarantees            |
|  - Comment likes & emojis     |       |  - "Pay via Razorpay" button    |
+---------------+---------------+       +----------------+----------------+
                |                                        |
                | Taps "Pay and Watch"                   |
                +----------------------------------------+
                                                         |
                                                         v
                                        +---------------------------------+
                                        |    RAZORPAY PAYMENT GATEWAY     |
                                        |  - Hosted checkout activity     |
                                        |  - UPI / Cards / Netbanking     |
                                        +----------------+----------------+
                                                         |
                                                         v
                                        +---------------------------------+
                                        |     PAYMENT VALIDATION PAGE     |
                                        |  - Gateway signature check      |
                                        |  - Permanent post unblur/unlock |
                                        |  - Digital transaction receipt  |
                                        |  - "Watch Post Now" CTA         |
                                        +----------------+----------------+
                                                         |
                                                         | Taps "Watch Post Now"
                                                         v
                                        +---------------------------------+
                                        |        POST VIEW PAGE           |
                                        |  (Unlocked in Full HD Stream)   |
                                        +---------------------------------+
```

---

## 3. Screen Specifications

### 3.1 Full-Screen Post View Page (`PostViewScreen.kt`)
- **Route**: `TriggerDestinations.POST_VIEW = "post_view/{postId}"`
- **Background**: Immersive pure black `#000000` layout with translucent top navigation bar.
- **Navigation**: Back icon button (`Icons.AutoMirrored.Filled.ArrowBack`) returning to previous screen or dashboard feed.
- **Author Identity Header**:
  - Creator circular avatar with image or initial fallback.
  - Creator display name and verified badge.
  - Dynamic formatted timestamp (e.g. `2h ago`, `Yesterday`, `Just now`).
- **Media Viewport**:
  - **Photos**: Swipeable `HorizontalPager` with indicator dots and current index badge (`1 / 3`).
  - **Videos**: Dedicated `ExoPlayer` controller (`PostViewVideoPlayer`) supporting auto-loop, tap-to-pause, buffering indicator, and audio mute/unmute control.
  - **Exclusive Blur**: 85% Gaussian blur (`Modifier.blur(28.dp)`) applied dynamically if post is locked and paid.
  - **Double-Tap to Like**: Spring-animated heart overlay icon triggering post like and heart scale animation.
- **Engagement Bar**:
  - Like icon with filled red state and live counter.
  - Comment icon with live comments count.
  - Direct "Pay and Watch" action card overlay when viewing locked content.
- **Expandable Caption**:
  - Author handle prefix with 2-line preview and "more" toggle expansion.
- **Instagram-Model Commenting Section**:
  - Dedicated comment cards with author avatar, username, timestamp, and body text.
  - **Comment-Level Likes**: Individual heart button on each comment with independent like state and like count.
  - **Quick Emoji Reaction Bar**: 7 one-tap reaction emojis (`❤️`, `🔥`, `👏`, `😍`, `😂`, `😮`, `🙌`).
  - **Sticky Comment Input Field**: Bottom pinned text input with rounded shape, clean contrast, Send button, and live updates to `FeedRepository`.

---

### 3.2 Payment Overview Page (`PaymentOverviewScreen.kt`)
- **Route**: `TriggerDestinations.PAYMENT_OVERVIEW = "payment_overview/{postId}"`
- **Header**: WhatsApp Trigger Green (`#128C7E`) top bar with "256-Bit SSL Encrypted Checkout" security badge and back navigation button.
- **Components**:
  1. **Post Content Summary**:
     - Creator verified badge, username, and "EXCLUSIVE" tag.
     - Locked thumbnail preview snippet with security lock icon.
     - Media format indicator (`HD Video Stream Access` or `Photo Gallery`).
  2. **Transparent Cost Breakdown**:
     - Creator post access fee (e.g. `₹99.00`).
     - Platform convenience fee (`FREE`).
     - Taxes & GST (`Included`).
     - Total payable amount highlighted in large bold `TriggerFabGreen`.
  3. **Unlock Guarantees**:
     - Instant Activation: Media unblurs immediately upon payment validation.
     - Lifetime Access: Permanent unlock stored on user profile.
     - 100% Secure Gateway: Razorpay encrypted pipeline.
     - Direct Creator Support: Funds directly mapped to creator balance.
  4. **Billing Identity**: Prefilled user name and email from `UserRepository`.
  5. **Payment Gateway Trigger**:
     - Large rounded button: `"Pay ₹XX.XX via Razorpay"`.
     - Calls `RazorpayPaymentService.createOrder` and launches `RazorpayCheckoutActivity`.

---

### 3.3 Payment Validation Page (`PaymentValidationScreen.kt`)
- **Route**: `TriggerDestinations.PAYMENT_VALIDATION = "payment_validation/{postId}?orderId={orderId}&paymentId={paymentId}&signature={signature}&status={status}&error={error}"`
- **States**:
  - **`VERIFYING`**:
    - Animated pulsing radar ring and spinner.
    - Asynchronous validation of `orderId`, `paymentId`, and `signature` via `RazorpayPaymentService.verifyPayment()`.
  - **`SUCCESS`**:
    - Animated green checkmark badge.
    - "Payment Verified & Post Unlocked" headline.
    - **Digital Transaction Receipt**:
      - Status: `VERIFIED`
      - Payment ID (`pay_xxxxxxxxxxxxxx`)
      - Order ID (`order_xxxxxxxxxxxx`)
      - Amount Paid (`₹XX.XX`)
      - Item description (`Post by Creator`)
      - Date and Timestamp
      - Payment Gateway (`Razorpay Gateway (UPI/Card)`)
    - Automatically unlocks the post in `FeedRepository` via `unlockPaidPost(postId)`.
    - **"Watch Post Now" Button**: Directly launches `PostViewScreen(postId)` with zero blur and instant playback.
    - **"Back to Feed" Button**: Returns to main dashboard.
  - **`FAILED`**:
    - Red alert badge with error message diagnosis.
    - "Try Again" button navigating back to Payment Overview.
    - "Back to Feed" fallback.

---

## 4. Technical Architecture & File Map

| Component / Feature | File Path | Key Responsibilities |
| :--- | :--- | :--- |
| **Post Data Model** | `/app/src/main/java/com/example/model/FeedPost.kt` | Added `likesCount` & `isLiked` to `PostComment` data model. |
| **Feed Repository** | `/app/src/main/java/com/example/service/FeedRepository.kt` | Implemented `getPost(id)`, `toggleCommentLike()`, and `unlockPaidPost()`. |
| **Post View Page** | `/app/src/main/java/com/example/ui/screens/PostViewScreen.kt` | Full-screen black UI, ExoPlayer, carousel, Instagram comments & reaction bar. |
| **Payment Overview Page** | `/app/src/main/java/com/example/ui/screens/PaymentOverviewScreen.kt` | Itemized price breakdown, Razorpay SDK launcher, security badges. |
| **Payment Validation Page** | `/app/src/main/java/com/example/ui/screens/PaymentValidationScreen.kt` | Signature verification, digital receipt generator, "Watch Post Now" CTA. |
| **Dashboard Feed Tab** | `/app/src/main/java/com/example/ui/screens/FeedTabContent.kt` | Wired post clicks to `PostView` and "Pay and Watch" to `PaymentOverview`. |
| **Main Navigation Graph** | `/app/src/main/java/com/example/ui/navigation/TriggerAppNavHost.kt` | Registered `POST_VIEW`, `PAYMENT_OVERVIEW`, and `PAYMENT_VALIDATION` routes. |

---

## 5. Verification & Testing

- **Compilation**: Verified cleanly via `compile_applet` with zero Gradle errors or missing symbols.
- **Edge-to-edge & Theming**: Integrated seamlessly with Trigger App green accents and Material 3 responsive layouts.
- **Test Tags**: Provided test tags across all pages (`post_view_screen`, `payment_overview_screen`, `payment_validation_screen`, `proceed_to_razorpay_button`, `watch_unlocked_post_button`).

---

## 6. Comprehensive UI/UX Refresh & Modernization

### 6.1 Universal Dialogue Box Standardization (`TriggerAlertDialog`)
- Created `app/src/main/java/com/example/ui/components/TriggerAlertDialog.kt` conforming to high-craft mobile dialogue standards:
  - Max container width of `360.dp` with dynamic height wrapping to eliminate excessive whitespace.
  - Consistent `RoundedCornerShape(20.dp)`.
  - Standardized title typography (`17.sp`, `FontWeight.Bold`) and padding (`20.dp` horizontal, `18.dp` vertical).
  - 100% of alert dialogs across 17+ screens migrated from generic `AlertDialog` to `TriggerAlertDialog`.

### 6.2 Global Header Theme Standardization
- Unified all top app bars to the Trigger brand green (`#008069`, `TriggerHeaderGreen`) with high-contrast white typography (`18.sp`, bold) and white back/action icons.
- Screens updated: `WhatsAppDashboardScreen`, `FeedTabContent`, `FeedCropEditor`, `NewMessageScreen`, `SelectContactScreen`, `UserProfileScreen`, `ProfileScreen`, `SettingsScreen`, `AccountSettingsScreen`, `ChatsSettingsScreen`, `NotificationsSettingsScreen`, `StorageSettingsScreen`, `PrivacySettingsScreen`, `HelpSettingsScreen`, `BlockedUsersScreen`, `DeleteAccountScreen`, `LanguageSelectionScreen`, `WalletScreen`, `ScheduleStreamScreen`, `StreamHistoryScreen`, `SecretVaultScreen`, `SendLocationScreen`, `PaymentOverviewScreen`, `PaymentValidationScreen`, and `PostUploadScreen`.

### 6.3 Media Crop Editor (`FeedCropEditor.kt`) Overhaul
- Fixed loading freeze by binding image URI directly to the canvas viewport.
- Added multi-touch pan, pinch-to-zoom, 90° clockwise rotation, and instant aspect-ratio selection chips (Original, 1:1, 4:5, 16:9).
- Clean top header with white back arrow and action buttons.

### 6.4 New Message Screen Search Bar
- Replaced oversized multi-line box with a modern 40dp height container, `RoundedCornerShape(20.dp)`, `#F0F2F5` background, and dedicated clear query button.

### 6.5 Production Polish & Prototype Text Cleanup
- Removed lingering placeholder copy, explanatory tooltips, and duplicate reaction widgets for a clean, production-grade finish.

### 6.6 Attached Model UI for Payment Verification (Section.png Reference)
- Overhauled `PaymentValidationScreen.kt` to mirror the user-attached specification:
  - **Clean Header**: White background, back navigation arrow, "Payment" title, and bold "Amount payable: ₹{amount}".
  - **Custom Two-Tone Circular Spinner (`TriggerProcessingSpinner.kt`)**: 84dp diameter with continuous 360-degree light grey track (`#E5E7EB`) and animated royal blue sweep arc (`#0052CC`).
  - **Title & Message**: "Your payment is being processed." display text and "Please hold on as it may take upto 30 mins in some cases." helper text.
  - **Anti-Exit Caution**: "Note: Do not hit back button or close this screen until the transaction is complete".
  - **Bottom Navigation Bar**: 5 tabs matching design (Chats active with green pill indicator, Updates, Stream, Calls, Profile).
  - **Leave Protection Dialog**: Hardware and UI back taps trigger a `TriggerAlertDialog` explaining that transaction is running to prevent accidental exits.
  - **Instant State Resolution**: Transitions cleanly to itemized receipt + "Watch Post Now" upon verification or offers retry on failure.

### 6.7 Tab Switching Navigation Loading Screen
- Added smooth, responsive loading transitions between tab switches in `WhatsAppDashboardScreen.kt`:
  - Uses `TriggerTabLoadingView` featuring the royal blue two-tone spinner.
  - Custom dynamic tab indicator ("Loading Chats...", "Loading Updates...", "Loading Stream...", "Loading Calls...", "Loading Profile...").
  - Ensures a fluid, high-performance transition across heavy views.

