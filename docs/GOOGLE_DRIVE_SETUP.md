# Google Drive auto-upload setup

OHealth Insights uploads sync results to a Google Drive folder created by the app (`drive.file` scope). Follow these steps once before using auto-upload.

## 1. Google Cloud project

1. Open [Google Cloud Console](https://console.cloud.google.com/).
2. Create a project (or reuse an existing one).
3. Enable **Google Drive API** for the project.

## 2. OAuth consent screen

1. Go to **APIs & Services → OAuth consent screen**.
2. Choose **External** (personal Google account) or **Internal** (Workspace).
3. Fill in the required app name and support email.
4. Add scope: `https://www.googleapis.com/auth/drive.file`.
5. Add your Google account as a **Test user** while the app is in Testing, or publish the consent screen to **In production** for long-term use without the 7-day token limit.

## 3. Android OAuth client

1. Go to **APIs & Services → Credentials → Create credentials → OAuth client ID**.
2. Application type: **Android**.
3. Package name: `dev.gr0mi4.ohealthinsights`.
4. SHA-1 certificate fingerprint: see below.

### SHA-1 fingerprint

The OAuth client must match the signing certificate of the APK you install.

**Local debug build** (default Android debug keystore):

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1
```

**CI / release build** (keystore from GitHub Secrets):

```bash
keytool -list -v -keystore your-release.keystore -alias YOUR_ALIAS | grep SHA1
```

Register **both** SHA-1 values if you install APKs from CI and from local builds.

Copy the **Client ID** (ends with `.apps.googleusercontent.com`).

## 4. App configuration

Create or edit `local.properties` in the project root (never commit this file):

```properties
DRIVE_OAUTH_CLIENT_ID=YOUR_CLIENT_ID.apps.googleusercontent.com
```

Rebuild the app. Open **Drive settings** in the app, tap **Connect Google account**, and run **Test connection**.

## 5. GitHub Actions signing (recommended)

So CI-built APKs use a stable SHA-1, add these repository secrets:

| Secret | Description |
|--------|-------------|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded `.jks` or `.keystore` file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |
| `DRIVE_OAUTH_CLIENT_ID` | Same OAuth client ID as above |

Generate base64 keystore:

```bash
base64 -i your-release.keystore | pbcopy
```

Register the keystore SHA-1 in the same Android OAuth client (step 3).

## 6. ChatGPT access

1. In ChatGPT: **Settings → Apps → Google Drive → Connect → Sync**.
2. Grant permission to read Drive files.
3. After a sync, open the app's **Reports** folder on Drive. ChatGPT can read:
   - `ohealth-latest-report.md` (human-readable summary)
   - `ohealth-latest-metrics.csv` (daily metrics table)
   - Raw `.ndjson.gz` files in **Archive** (binary; use the report files for GPT)

Example prompt: *"Read my latest OHealth report on Drive and tell me if I'm overtraining this week."*

## Folder layout (created by the app)

```
OHealth Insights/          ← root folder (name configurable)
├── Reports/
│   ├── ohealth-latest-report.md
│   ├── ohealth-latest-metrics.csv
│   └── ohealth-report-2026-08-12.md   ← dated snapshots
└── Archive/
    └── ohealth-raw-incremental-20260812-143022.ndjson.gz
```
