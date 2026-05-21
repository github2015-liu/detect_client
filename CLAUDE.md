# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an Android health detection SDK client project. It communicates with a remote `HealthDetectServer` service via AIDL IPC to perform health detection on vehicle seats.

## Build Commands

```bash
./gradlew assembleDebug     # Build debug APK
./gradlew assembleRelease   # Build release APK
./gradlew clean              # Clean build outputs
./gradlew :app:build        # Build specific module
```

## Architecture

### Module Structure

- **app** - Demo application that binds to the health detect service and triggers detections
- **healthdetectlib** - SDK library providing `HealthDetectManager` singleton and AIDL interfaces
- **healthdetectlistenerlib** - Listener library (AIDL-based, similar interface to healthdetectlib)

### Key Components

**AIDL Interface** (`healthdetectlib/src/main/aidl/com/senseauto/healthdetect/`):
- `IHealDetectApiInterface` - Main API with methods: `startHealthDetect`, `startAutoDetect`, `stopHealthDetect`, `updateAutoDetectSeatList`, `getDetectingSeat`, `registerCallback`, `unregisterCallback`
- `IHealthDetectCallback` - Callback interface with: `onHealthStateUpdate`, `onFinishResult`, `onError`

**HealthDetectManager** - Singleton facade managing service binding lifecycle:
- States: `IDLE` → `INITING` → `READY`
- Use `getInstance(Context).init(InitCallback)` to bind to service
- Service target: `com.senseauto.healthdetect/com.senseauto.healthdetect.HealthDetectServer`

**Seat Constants** (`Seat.java`):
- `FRONT_LEFT` ("front_left") - 主驾驶
- `FRONT_RIGHT` ("front_right") - 副驾驶
- `BACK_LEFT` ("back_left") - 后排左
- `BACK_RIGHT` ("back_right") - 后排右

**Error Codes** (`ErrorCode.java`):
- `LICENSE_FAILED` (201) - License validation failed
- `DETECT_ERROR` (500) - Detection error
- `FACE_FAILED` (401) - Face detection failed
- `DETECTING` (501) - Detection already in progress
- `DETECT_START_FAILED` (502) - Failed to start detection
- `SERVICE_IS_BINDING` (1001) - Service not ready
- `SERVICE_BIND_FAILED` (1002) - Service binding failed

### App Dependencies

The `app` module depends on a pre-built AAR (`app/libs/healthdetectlib-release.aar`), not the source library modules. The source library modules (`healthdetectlib`, `healthdetectlistenerlib`) are included for reference and can be built into JARs via `./gradlew :healthdetectlib:makeJar`.

## Configuration

- Gradle 8.2.2, Android Gradle Plugin 8.2.2
- compileSdk/targetSdk: 34, minSdk: 24
- Java 1.8 compatibility
- AndroidX enabled, non-transitive R class
