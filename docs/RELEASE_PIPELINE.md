# SEQ APK channels — mRadio/VRec parity

The single workflow `.github/workflows/ci.yml` has explicit channels:

| Trigger | Build verification | Publication |
|---|---|---|
| Open / update PR targeting `main` | JVM unit tests, Android lint, R8-minified unsigned release APK, C++ DSP tests | No release from a PR check |
| Push to `experimental/high-fidelity-engine` | Same release/R8 tests | Sign with SEQ's persistent identity, GitHub **pre-release**, `updates/experimental.json` |
| Push to `main` | Same release/R8 tests | Sign with the **same** identity, GitHub **stable release**, `updates/stable.json` |
| Manual dispatch from either branch | Same tests | Only that branch's channel; stable dispatch may specify a newer semantic version |

**Stable does not ship until the experimental PR is merged into `main`.** This repository intentionally maintains one experimental PR. Do not merge purely because a build is green; device testing is also needed.

## One-time signing setup (required before new signed releases)

SEQ previously used ephemeral GitHub Actions debug keys. Android does not accept a different signing identity as a seamless upgrade; those early experimental APKs may need one uninstall/reinstall. All subsequent stable and experimental releases will use one persistent, app-specific signer.

On a **trusted local computer** with `keytool`, `openssl` and authenticated `gh`:

```bash
bash scripts/setup-seq-signing.sh /path/to/private/offline-backup
```

The script creates a SEQ-only PKCS12 signing identity, stores an encrypted backup **outside the repository**, and sets the following repository Actions secrets without displaying their values:

- `SIGNING_KEYSTORE_ENC_BASE64`: AES-256 encrypted keystore
- `SIGNING_PASSPHRASE`: the password for keystore and encrypted backup
- `SEQ_SIGNING_CERT_SHA256`: expected public certificate digest, used to reject accidental key rotation

Do not commit keystores, export them into workflow artifacts, or reuse the mRadio/VRec signing key. Preserve the encrypted offline backup and passphrase independently. If signing secrets are missing or the fingerprint differs, the publish job deliberately fails **without** uploading a new update. Android tests and R8 may still pass.

A repository `N2K02_PACKAGES_TOKEN` Actions secret with GitHub Packages read permission is also needed to resolve `n2k02theme` and `n2k02updater`.

## Release identity and updater

- Production signing is performed by `apksigner`, after the R8 release APK is built and tested.
- Version codes are increasing across *both* channels: `100000 + GitHub run number`.
- First stable tag is `v0.3.0` (or a newer explicitly requested version); later stable pushes advance the patch.
- Experimental tag is `experimental-pr-1-run-N`, tied to the single existing experimental PR.
- GitHub Releases publish both artifacts. `n2k02updater` reads independent HTTPS `stable.json` and `experimental.json` feeds; SHA-256, size and actual release asset are verified before publishing.
- Releases are serialized. A superseded commit cannot overwrite the updater feed, and an existing stable release cannot be silently replaced.

## Verification

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleRelease -PseqVersion=0.3.0 -PseqChannel=experimental -PseqBuild=123
cmake -S dsp -B dsp/build -DCMAKE_BUILD_TYPE=Release
cmake --build dsp/build
ctest --test-dir dsp/build --output-on-failure
```

The unsigned release artifact in CI is an intermediate input to the signing job, **not** an installable release. Download the signed APK from GitHub Releases.
