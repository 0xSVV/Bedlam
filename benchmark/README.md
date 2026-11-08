# Bedlam performance checks

The `:benchmark` test APK drives the production Compose screens. The Baseline Profile plugin creates two target variants:

- `nonMinifiedRelease` collects readable profile rules.
- `benchmarkRelease` runs with R8 and resource shrinking enabled.

Both use application ID `ru.shapovalov.bedlam.benchmark`, the development signing key, and the fixtures in `app/src/performance`. Those activities and fixtures are absent from the normal release APK. They seed 40 profiles, 600 log messages, and a disabled ASN source with 300 cached networks. The test APK cannot replace the normal Bedlam installation.

## Generate profiles

Connect an API 33+ emulator or supported physical device, then run:

```sh
./gradlew :app:generateBaselineProfile
```

Generated `baseline-prof.txt` and `startup-prof.txt` files are saved under `app/src/main/generated/baselineProfiles` and should be committed after regeneration. App and Hysteria Kotlin rules are included; fixture classes are excluded. Library profiles are also merged by AGP. Profile generation is explicit so an ordinary release build does not need a connected device.

The startup generator records only launch code in the Startup Profile. The other generator records navigation, app and log scrolling, large network details, About, connection morphing, and session skeleton rendering in the Baseline Profile. The fixed loading states call the same production composables without depending on network response times.

## Run benchmarks

```sh
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
```

Startup tests clear only the isolated benchmark app data before each test method, so a previous manual VPN connection cannot affect launch measurements. UI fixtures disconnect an existing tunnel before measuring.

The suite records cold startup with and without the packaged profile, scrolling frame times, network detail expansion, About opening, and the loading animations. The default is five iterations. Use `-Pandroid.testInstrumentationRunnerArguments.bedlam.iterations=10` for longer runs.

For emulator verification, explicitly acknowledge the emulator measurement limitation:

```sh
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

Only the `EMULATOR` check is suppressed. Target APKs remain non-debuggable and profileable. Use a physical device for release performance decisions: host load, emulator graphics, and virtual CPU scheduling make emulator numbers unsuitable as device performance targets.

JSON metrics and Perfetto traces are copied into `benchmark/build/outputs/connected_android_test_additional_output`. The HTML test report is under `benchmark/build/reports/androidTests/connected`.

## Real tunnel verification

The fixtures default to a local Hysteria server at `10.0.2.2:18443` with password `bedlam-ui-review` and a self-signed certificate. Run a server on the emulator host's loopback address, then open the isolated fixture app:

```sh
adb shell am start -W \
  -n ru.shapovalov.bedlam.benchmark/ru.shapovalov.bedlam.benchmark.BenchmarkSetupActivity
```

The setup activity also accepts `--es server HOST:PORT --es auth PASSWORD`. Connect from Home and open Session to check real tunnel data. The benchmark-only local profile accepts a self-signed certificate. A real server is unnecessary for the deterministic frame benchmarks.

Use the native library build normally. When testing only Kotlin/UI changes with an already built `hysteria/libs/golib.aar`, append `-x :hysteria:buildGolib -x :hysteria:syncHysteriaSubmodule` to reuse that artifact.

References: [Android Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile), [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview).
