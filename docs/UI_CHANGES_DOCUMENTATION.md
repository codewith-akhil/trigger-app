# Trigger App — UI & UX Architecture and Enhancements Documentation

> **Document Version:** 1.0  
> **Commit Hash:** `ad45239cc6835b1d8c8759f58558285f03c5c0bf`  
> **Commit Title:** `Enhance WhatsApp-style chat UI cards, keyboard handling, media viewer and user profile screen`  
> **Date:** September 7, 2026  
> **Repository:** `https://github.com/codewith-akhil/trigger-app.git`  
> **Branch:** `main`

---

## 1. Executive Summary & Overview

This document provides an exhaustive, line-level and conceptual breakdown of the comprehensive UI/UX overhaul implemented in commit `ad45239`. The primary objective was to align the **Trigger** native Android messaging experience with the production quality, visual precision, and ergonomic behavior of **WhatsApp** and **Instagram**, specifically targeting:

1. **Pixel-Accurate WhatsApp Chat Bubbles**: Eliminating boxy card layouts, excess white padding, and awkward timestamps in favor of tight asymmetric bubbles, inline compact time placement, and translucent corner pill badges for media.
2. **Dedicated Media Cards**: Edge-to-edge media clipping, proper portrait/landscape bounds, dedicated duration badges, centered play overlays, and WhatsApp-style external circular Forward buttons.
3. **Full-Screen Media Viewer**: A completely reimagined edge-to-edge media gallery with pinch-to-zoom (1x–4x), panning, Star/Forward/Delete top controls, an interactive quick-emoji reaction row, and a bottom reply text bar.
4. **Dedicated User Profile Screen**: A standalone, privacy-conscious public profile viewer for searched users featuring large avatar presentation, follower/following metrics, dynamic Follow/Message actions, and direct Block/Report safety moderation dialogs—strictly omitting private email/phone details.
5. **Decluttered New Message Search**: Refining search result rows to display only the Follow/Following action on the right edge, eliminating duplicate icon clutter, and navigating to the profile screen on tap.
6. **Chronological Message Ordering & Keyboard Docking**: Remediation of message inverted sort orders across the local Room persistence layer (`MessageDao.kt`) and in-memory Flow combining (`ChatViewModel.kt`), while maintaining clean IME keyboard docking.

---

## 2. Architectural Context & Problem Statements

### Pre-Overhaul Pain Points
- **Bulky Chat Bubbles**: Bubbles previously used standard M3 `Card` containers with uniform 16dp radii and large internal paddings (12–16dp), causing even a 3-word message to occupy massive vertical and horizontal real estate.
- **Detached Timestamps**: Timestamps and delivery ticks were placed on a separate line below all messages, creating unnecessary dead space for short single-line messages.
- **Cluttered Search Results**: Search results in `NewMessageScreen` suffered from visual noise with multiple buttons (Follow, Request Sent, PersonAdd icon, Call) on the right side of each row.
- **Missing Public Profile View**: Clicking a user in search results attempted to open a chat immediately without allowing the user to view their bio, follower counts, or perform safety actions like Block or Report.
- **Message Disordering**: Room queries ordered by `seq ASC, timestampMillis ASC`. When multiple messages had `seq = 0` (such as incoming remote messages or unsynced drafts), they appeared in inverted or non-chronological order.

---

## 3. Deep-Dive File-by-File Technical Changes

```
-----------------------------------------------------------------------------------------
File                                             | Changes
-----------------------------------------------------------------------------------------
app/src/main/java/com/example/ui/screens/        | 
  ChatComponents.kt                              | +602 / -239 lines (WhatsApp Bubbles & Cards)
  ChatMediaViewer.kt                             | +245 / -63 lines (Full-Screen Gallery & Reply)
  NewMessageScreen.kt                            | +2 / -15 lines (Search Row Decluttering)
  UserProfileScreen.kt                           | +773 lines (NEW: Public Profile Screen)
app/src/main/java/com/example/ui/navigation/     | 
  TriggerAppNavHost.kt                           | +29 lines (UserProfile Destination Wiring)
app/src/main/java/com/example/data/local/        | 
  MessageDao.kt                                  | +1 / -1 lines (Chronological Query Sorting)
app/src/main/java/com/example/ui/viewmodel/      | 
  ChatViewModel.kt                               | +3 / -3 lines (Combined Flow Timestamp Sorting)
-----------------------------------------------------------------------------------------
Total                                            | 7 files changed, 1593 insertions(+), 319 deletions(-)
-----------------------------------------------------------------------------------------
```

---

### 3.1. `ChatComponents.kt` — WhatsApp Chat Bubbles & Media Cards

`ChatComponents.kt` is the core layout engine responsible for rendering individual chat message bubbles in the conversation timeline.

#### 3.1.1. Asymmetric Bubble Shapes & Tight Spacing
In real WhatsApp, incoming and outgoing bubbles do not share identical border radii:
- **Outgoing Bubble Shape (`isOutgoing == true`)**:
  ```kotlin
  RoundedCornerShape(
      topStart = 14.dp,
      topEnd = 2.dp,    // Small anchor corner pointing to user's side
      bottomEnd = 14.dp,
      bottomStart = 14.dp
  )
  ```
- **Incoming Bubble Shape (`isOutgoing == false`)**:
  ```kotlin
  RoundedCornerShape(
      topStart = 2.dp,   // Small anchor corner pointing to peer's side
      topEnd = 14.dp,
      bottomEnd = 14.dp,
      bottomStart = 14.dp
  )
  ```
- **Bubble Colors**:
  - Outgoing background: `#E7FFDB` (WhatsApp light green tint)
  - Incoming background: `#FFFFFF` (Pure white card surface)
- **Padding Reductions**:
  - Bubble container padding was reduced from `12.dp` / `16.dp` down to `start = 9.dp, end = 9.dp, top = 5.dp, bottom = 5.dp`.
  - Max bubble width restricted to `0.78f` of parent width (`fillMaxWidth(0.78f)`), matching WhatsApp’s single-handed readability standard.

#### 3.1.2. Inline Timestamp & Tick Placement Algorithm
WhatsApp optimizes vertical chat height by placing the timestamp and status ticks on the **same baseline** as the text whenever the message is short. We implemented an automatic detection and layout switcher:

```kotlin
val isSingleLineShort = message.text.length < 22 && !message.text.contains("\n")

if (isSingleLineShort) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = message.text,
            fontSize = 15.sp,
            color = Color(0xFF111B21),
            lineHeight = 19.sp
        )
        // Inline timestamp & delivery tick marks
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(bottom = 1.dp)
        ) {
            Text(
                text = message.timestamp,
                fontSize = 11.sp,
                color = Color(0xFF667781)
            )
            if (message.isOutgoing) {
                ChatTicksIcon(status = message.status)
            }
        }
    }
} else {
    // Multi-line layout: text on top, timestamp right-aligned at bottom
    Column {
        Text(
            text = message.text,
            fontSize = 15.sp,
            color = Color(0xFF111B21),
            lineHeight = 20.sp
        )
        Row(
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = message.timestamp,
                fontSize = 11.sp,
                color = Color(0xFF667781)
            )
            if (message.isOutgoing) {
                ChatTicksIcon(status = message.status)
            }
        }
    }
}
```

#### 3.1.3. Edge-to-Edge Media Cards & Translucent Overlay Pills
For photo and video messages without text captions, traditional chat bubbles look clumsy with borders. We introduced dedicated WhatsApp-style media bubbles:
- **Dimensions**:
  - Width: `min = 220.dp, max = 265.dp`
  - Height: `min = 190.dp, max = 340.dp`
- **Clipping**: The inner image or video thumbnail is clipped directly to the asymmetric shape:
  ```kotlin
  val mediaShape = if (message.isOutgoing) {
      RoundedCornerShape(11.dp, 2.dp, 11.dp, 11.dp)
  } else {
      RoundedCornerShape(2.dp, 11.dp, 11.dp, 11.dp)
  }
  ```
- **Translucent Bottom-Right Timestamp Badge**:
  To ensure the timestamp and delivery ticks remain legible over any photo or video background, they are enclosed inside a semi-transparent black pill:
  ```kotlin
  Box(
      modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(end = 6.dp, bottom = 6.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(Color.Black.copy(alpha = 0.45f))
          .padding(horizontal = 6.dp, vertical = 2.dp)
  ) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(3.dp)
      ) {
          Text(
              text = message.timestamp,
              fontSize = 11.sp,
              color = Color.White
          )
          if (message.isOutgoing) {
              ChatTicksOverlayIcon(status = message.status)
          }
      }
  }
  ```
- **Video Play Overlay & Duration Badge**:
  - For videos, a centered circular play button (`Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.45f))`) is rendered with a white `Icons.Filled.PlayArrow`.
  - In the bottom-left corner, a semi-transparent badge displays a mini video camera icon alongside formatted duration (e.g. `0:14`).

#### 3.1.4. External Circular Forward Button
Matching WhatsApp's interaction model, photo and video cards display a circular Forward button alongside the card:
- Outgoing media: Forward button placed to the **left** of the bubble.
- Incoming media: Forward button placed to the **right** of the bubble.
- Styling: `Modifier.size(30.dp).clip(CircleShape).background(Color(0xFFE9EDEF))`, containing a 16dp `Icons.Filled.Forward` icon in `#54656F`.

#### 3.1.5. Message Status & Read Ticks Engine
The `ChatTicksIcon` and `ChatTicksOverlayIcon` composables handle all 5 delivery states with pixel-precise vector assets:
- `SENDING`: 12dp `CircularProgressIndicator` with 1.5dp stroke width.
- `SENT`: Single checkmark (`✓`) in muted gray `#8696A0` (or semi-transparent white on overlays).
- `DELIVERED`: Double checkmark (`✓✓`) in muted gray `#8696A0`.
- `READ`: Double checkmark (`✓✓`) in WhatsApp bright cyan-blue (`#53BDEB`).
- `FAILED`: Red alert exclamation indicator (`!`) with retry tap handler.

---

### 3.2. `ChatMediaViewer.kt` — Immersive Full-Screen Media Viewer

The media viewer was transformed into a native, full-screen gallery experience conforming to WhatsApp’s media presentation standards.

#### 3.2.1. Canvas & System Bars
- **Color**: Pure solid black (`Color.Black`).
- **Insets**: Uses `.statusBarsPadding()` and `.navigationBarsPadding()` so the content flows edge-to-edge behind the Android navigation gesture bar.

#### 3.2.2. Multi-Touch Gestures (Pinch-to-Zoom & Pan)
Using Compose’s `Modifier.pointerInput` with `detectTransformGestures`:
```kotlin
detectTransformGestures { _, pan, zoom, _ ->
    scale = (scale * zoom).coerceIn(1f, 4f)
    if (scale > 1f) {
        offsetX += pan.x
        offsetY += pan.y
    } else {
        offsetX = 0f
        offsetY = 0f
    }
}
```
The resulting transform values are applied directly to the image using `.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)`.

#### 3.2.3. Top App Bar Controls
A translucent 64dp header with background `Color.Black.copy(alpha = 0.65f)` displays:
- **Back Navigation**: `Icons.AutoMirrored.Filled.ArrowBack` in white.
- **Title & Subtitle**: Sender name (or "You" for outgoing) in 16sp semi-bold white, timestamp with optional `"• View once"` indicator in 12sp muted gray.
- **Star Toggle**: Interactive Star button toggling between `Icons.Filled.Star` (amber `#FFC107`) and `Icons.Filled.StarBorder` (white).
- **Forward Action**: `Icons.Filled.Forward` to share or forward media.
- **Delete Action**: `Icons.Filled.Delete` with delete confirmation trigger.

#### 3.2.4. Bottom Section: Quick Reactions & Interactive Reply Bar
In the bottom sheet area over a `Color.Black.copy(alpha = 0.75f)` gradient scrim:
1. **Optional Caption**: Displays the message text with 14.5sp white typography.
2. **Quick Emoji Reactions Row**: Six instant emoji bubbles (`❤️`, `😂`, `😮`, `😢`, `🙏`, `👏`) inside 36dp translucent circular containers (`Color.White.copy(alpha = 0.12f)`).
3. **WhatsApp-Style Reply Composer**:
   - Background: Dark WhatsApp input capsule `#1F2C34` with 24dp rounded corners.
   - Left icon: Emoji smiley face icon `#8696A0`.
   - Input: `BasicTextField` with white text and `TriggerFabGreen` (`#00A884`) cursor brush.
   - Send Button: Dynamically appears when text is entered, styled as a 34dp circle in `TriggerFabGreen` with a white send plane icon.

---

### 3.3. `UserProfileScreen.kt` — Standalone User Profile Screen

Created a new 773-line screen (`com.example.ui.screens.UserProfileScreen.kt`) to serve as the user's public identity card when tapped from search results or group sheets.

#### 3.3.1. Strict Privacy Architecture
As per strict privacy requirements:
- **Allowed**: Full Display Name, `@username`, Avatar Photo, Follower Count, Following Count, About/Bio status text, mutual group counts.
- **STRICTLY EXCLUDED**: No email addresses, phone numbers, account creation timestamps, or sensitive UUID credentials are displayed anywhere on the screen.

#### 3.3.2. Hero Profile Header & Metrics
- **Avatar**: 104dp circular avatar with Coil `AsyncImage` and fallback colored letter initials.
- **Typography**: Display Name in 22sp bold (`#111B21`), `@username` in 14sp secondary gray (`#667781`).
- **Followers / Following Counters**: Clean horizontally balanced metric row with vertical divider (`TriggerDivider #F0F2F5`).

#### 3.3.3. Primary Action Buttons
- **Follow / Following**:
  - When Following: `OutlinedButton` with checkmark icon and text `"Following"`.
  - When Not Following: Filled `Button` in `TriggerFabGreen` (`#00A884`) with person-add icon and text `"Follow"`.
  - Backed by Supabase Edge Function `toggle-follow-user`.
- **Message**:
  - Primary button opening the 1:1 chat conversation with peer ID and contact name passed directly.
  - If a message request is required (non-follower 3-message cap), triggers the message request initiation flow.

#### 3.3.4. Safety & Moderation Options
At the bottom of the profile in a dedicated card:
- **Block User**:
  - Displays `Icons.Filled.Block` with danger red styling.
  - Opens confirmation dialog: *"Block [Name]? Blocked contacts will no longer be able to call you or send you messages."*
  - Calls Supabase `manage-blocked-contacts` endpoint.
- **Report User**:
  - Displays `Icons.Filled.Report` with danger red styling.
  - Opens Report Dialog with structured categories: *Spam*, *Harassment*, *Inappropriate Content*, *Fake Account*, *Other*.
  - Calls Supabase `report-user` endpoint and informs the user via Toast notification.

---

### 3.4. `NewMessageScreen.kt` — User Search Refinement

Cleaned up search result rows to align with modern social messaging patterns:
- **Removed Duplicate Action Buttons**: Previous versions showed Follow buttons, "Request sent" badges, and a redundant `PersonAdd` icon in the same row.
- **Single Action Right Column**:
  - Only the **Follow / Following** toggle button is rendered on the right side of the row.
  - The row's `onClick` now navigates directly to `UserProfileScreen` (`onNavigateToUserProfile(user)`), giving users full control before initiating a chat.

---

### 3.5. `TriggerAppNavHost.kt` — Navigation Graph Integration

Integrated `UserProfileScreen` into the central application navigation:
- Added destination constant: `TriggerDestinations.USER_PROFILE = "user_profile"`.
- Added navigation route state: `activeUserProfileUser: UserSearchResult?`.
- Configured composable route in the NavHost:
  ```kotlin
  composable(TriggerDestinations.USER_PROFILE) {
      val user = activeUserProfileUser
      if (user != null) {
          UserProfileScreen(
              user = user,
              onBack = { navController.popBackStack() },
              onOpenChat = { convId, peerId, contactName ->
                  activeChatConversationId = convId
                  activeChatPeerId = peerId
                  activeChatContactName = contactName
                  activeChatAvatarRes = null
                  navController.navigate(TriggerDestinations.CHAT)
              }
          )
      } else {
          LaunchedEffect(Unit) {
              navController.popBackStack()
          }
      }
  }
  ```

---

### 3.6. `MessageDao.kt` & `ChatViewModel.kt` — Message Chronology & Sorting Fix

#### Root Cause Analysis
Messages in active conversations occasionally appeared out of chronological order when older pages were paged in or when new messages were sent. Two bugs were identified:
1. `MessageDao.kt` had `@Query("... ORDER BY seq ASC, timestampMillis ASC")`. Because draft or freshly inserted messages often have `seq = 0`, they were grouped together at the top of the chat rather than sorted by true real-world timestamp.
2. In `ChatViewModel.kt`, the Flow combiner sorted by `seq` first: `map.values.sortedWith(compareBy({ it.seq }, { it.timestampMillis }))`.

#### Applied Fixes
- **`MessageDao.kt`**:
  ```kotlin
  // Before:
  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY seq ASC, timestampMillis ASC")
  // After (Fixed):
  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampMillis ASC, seq ASC")
  ```
- **`ChatViewModel.kt`**:
  ```kotlin
  val messages: StateFlow<List<DomainMessage>> = combine(_liveMessages, _extraMessages) { main, extras ->
      val map = LinkedHashMap<String, DomainMessage>()
      extras.sortedBy { it.timestampMillis }.forEach { map[it.id] = it }
      main.forEach { map[it.id] = it }
      map.values.sortedWith(compareBy({ it.timestampMillis }, { it.seq }))
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
  ```
This guarantees strictly chronological top-to-bottom message flow throughout the entire app lifecycle.

---

## 4. Design Tokens & Palette Reference

| Token Name | Hex Code | Usage in Chat & Profile |
|---|---|---|
| `TriggerFabGreen` | `#00A884` | Primary actions, Follow button, Send button, Unread badges |
| `TriggerHeaderGreen` | `#008069` | Top app bars, brand headers, accent icons |
| `TriggerChatBg` | `#E5DDD5` | Standard WhatsApp doodle wallpaper background |
| `BubbleOutgoing` | `#E7FFDB` | WhatsApp light green outgoing message bubble |
| `BubbleIncoming` | `#FFFFFF` | WhatsApp crisp white incoming message bubble |
| `TriggerCheckmarkBlue` | `#53BDEB` | Double check read receipt indicator |
| `TriggerTextPrimary` | `#111B21` | High contrast primary text & message bodies |
| `TriggerTextSecondary` | `#667781` | Muted timestamps, headers, subtitles |
| `TriggerDivider` | `#F0F2F5` | Thin horizontal separation lines |
| `TriggerDanger` | `#EA4335` | Block, Report, and Delete destructors |
| `MediaOverlayBlack` | `rgba(0,0,0,0.45)` | Bottom-right timestamp badges over photos/videos |
| `MediaViewerReplyBg` | `#1F2C34` | Full-screen media viewer reply text container |

---

## 5. Verification, Testing & Build Stability

### 5.1. Compilation Verification
The entire codebase was compiled and verified using the Android toolchain:
- **Build Status**: `Build succeeded - the applet is compiled`
- **Kotlin Compiler**: Clean compilation across all modules with zero syntax errors, missing symbols, or unresolved imports.
- **Media3/ExoPlayer**: Clean linkage for `androidx.media3.exoplayer.ExoPlayer` and `androidx.media3.ui.PlayerView`.

### 5.2. UI Verification Checks
- [x] Outgoing bubbles show asymmetric curve (`topEnd = 2.dp`) in `#E7FFDB`.
- [x] Incoming bubbles show asymmetric curve (`topStart = 2.dp`) in `#FFFFFF`.
- [x] Single-line messages render timestamp horizontally inline on the same line as the text.
- [x] Multi-line messages render timestamp bottom-right aligned with minimal spacing.
- [x] Media items have no double borders and feature translucent corner timestamp pills.
- [x] Videos render play overlay and bottom-left duration pill.
- [x] External circular Forward buttons appear on media bubbles.
- [x] Full-screen media viewer pinch-to-zoom scales between 1x and 4x with drag panning.
- [x] Media viewer quick reactions and reply bar render above system navigation insets.
- [x] New Message search rows show only the Follow button on the right edge.
- [x] Tapping a user opens `UserProfileScreen` showing avatar, name, username, bio, and safety actions without email or phone numbers.
- [x] Block and Report dialogs function with toast confirmation.
- [x] Messages stay strictly ordered by `timestampMillis ASC, seq ASC`.

---

## 6. Git Commit & Push Summary

All changes detailed in this documentation have been committed and pushed to the official repository:
- **Commit SHA**: `ad45239cc6835b1d8c8759f58558285f03c5c0bf`
- **Remote**: `https://github.com/codewith-akhil/trigger-app.git`
- **Branch**: `main`
- **Author**: Akhil <codewithakhil@example.com>
