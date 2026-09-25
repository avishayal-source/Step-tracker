# Play release signing (cloud / CI)

Y Walk’s Play upload key (`.jks` / `.keystore`) must **never** be committed to git.
That is why a Cloud Agent build was unsigned until secrets are configured: the agent
had no access to your private key.

Once secrets are set, agents and CI can build a Play-ready AAB automatically.

## One-time setup (you do this once)

### 1. Encode your keystore

On your PC (PowerShell), from the folder that contains your `.jks`:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\Users\Avishay\ywalk-release.jks")) |
  Set-Clipboard
```

Or bash:

```bash
base64 -w0 ywalk-release.jks | pbcopy   # macOS
base64 -w0 ywalk-release.jks            # Linux — copy the output
```

### 2. Add Cursor Cloud secrets

Open your Cloud Agent environment secrets and add:

| Secret name | Value |
|---|---|
| `YWALK_KEYSTORE_BASE64` | the base64 string from step 1 |
| `YWALK_STORE_PASSWORD` | keystore password |
| `YWALK_KEY_ALIAS` | key alias (same as in `keystore.properties`) |
| `YWALK_KEY_PASSWORD` | key password |

Environment dashboard (this run’s personal env):  
https://cursor.com/dashboard/cloud-agents/environments

### 3. (Optional) GitHub Actions secrets

Repo → Settings → Secrets and variables → Actions — same four names.
Then the workflow `.github/workflows/play-bundle.yml` can build a signed AAB on demand.

## Build a signed AAB (agent or CI)

```bash
./scripts/prepare-release-signing.sh
./gradlew :app:bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

## Local builds (your laptop)

Keep using gitignored `keystore.properties` as today — no change required.
Do **not** paste passwords into chat or commit `keystore.properties` / `*.jks`.
