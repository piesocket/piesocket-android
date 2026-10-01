# Publishing `channels-sdk` (piesocket-android)

Release guide for pushing a new version of this library to **Maven Central**
(`com.piesocket:channels-sdk`).

Current release: **7.1.0** — see `CHANGELOG.md`.

Publishing is done by the [vanniktech `maven-publish`](https://github.com/vanniktech/gradle-maven-publish-plugin)
plugin (`build.gradle`), targeting the Sonatype **Central Portal**
(`SonatypeHost.CENTRAL_PORTAL`), with `signAllPublications()`.

## Prerequisites (one-time, per machine)

`gradle.properties` (gitignored, **never commit it**) must hold:

```properties
mavenCentralUsername=<Central Portal token username>
mavenCentralPassword=<Central Portal token password>
signing.keyId=<last 8 chars of the GPG key id>
signing.password=<GPG key passphrase>
signing.secretKeyRingFile=/Users/<you>/.gnupg/secring.gpg
```

- Central Portal token: generate at https://central.sonatype.com/account
  (Account → Generate User Token) for a user with publish rights on the
  `com.piesocket` namespace.
- GPG key: must be published to a public keyserver
  (`gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>`). If your GPG
  is 2.1+ and has no `secring.gpg`, export one:
  `gpg --export-secret-keys > ~/.gnupg/secring.gpg`.

## 1. Pre-flight

- [ ] `build.gradle` `version = "7.0.0"` is the version you intend to ship.
- [ ] `CHANGELOG.md` has a matching top entry.
- [ ] `README.md` dependency snippets show the new version.
- [ ] Working tree clean apart from the release commit.
- [ ] A given version is **immutable** on Central — a mistake means a new
      version, never a re-upload.

## 2. Verify locally

```sh
cd sdks/piesocket-android
./gradlew clean
./gradlew test                 # JVM unit tests (ConnectionTest etc.)
./gradlew assembleRelease      # release AAR builds & links
./gradlew publishToMavenLocal  # inspect the POM + artifacts in ~/.m2
```

`LiveV4IntegrationTest` is `@Ignore`d (network-dependent) and does not run in
`./gradlew test` — that's expected.

After `publishToMavenLocal`, check
`~/.m2/repository/com/piesocket/channels-sdk/7.1.0/` — the `.aar`, the
`.pom` (correct version, `io.github.webrtc-sdk:android` + `okhttp` as
`compile`-scope deps), sources and javadoc jars, and a `.asc` signature next
to each.

## 3. Publish to Maven Central

```sh
./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
```

This uploads a deployment to the Central Portal and auto-releases it once
validation passes. To gate the release manually instead, run
`./gradlew publishToMavenCentral` and then approve the deployment at
https://central.sonatype.com/publishing/deployments.

Propagation to `repo1.maven.org` takes a few minutes to ~an hour; the
Central Portal search reflects it sooner.

## 4. Tag and push

```sh
# from sdks/piesocket-android (its own repo: github.com/piesocket/piesocket-android, branch master)
git add -A
git commit -m "v7.1.0 - reconnect backoff, PieRTC renegotiation/dispose fixes, camera controls"
git tag v7.1.0
git push origin master --tags
```

Then commit the bumped submodule pointer in the parent `piesocket-server`
repo.

## 5. Post-publish: cut the demo over to the published library

`demos/android-demo` builds against this SDK via a Gradle **composite
build**. Once `7.0.0` is on Central:

1. In `demos/android-demo/settings.gradle.kts`, remove
   `includeBuild("../../sdks/piesocket-android")` (and its comment).
2. `app/build.gradle.kts` already declares
   `implementation("com.piesocket:channels-sdk:7.0.0")` — no change needed.
3. `./gradlew :app:dependencies` (or a full build) to confirm it resolves
   from Maven Central, then smoke-test and commit.

## 6. Cutting a later release

1. Bump `build.gradle` `version`.
2. Add a matching `CHANGELOG.md` section; update `README.md` snippets.
3. Run sections 2 → 4 with the new version/tag.

Semver: additive API → minor, fix-only → patch, breaking change → major.
