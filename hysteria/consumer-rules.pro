# Gomobile generates Java bindings under the `golib` and `go` packages.
# The Go runtime invokes them by name via JNI, so R8 must not rename, remove,
# or shrink any of these classes or their members.
-keep class golib.** { *; }
-keep interface golib.** { *; }
-keep class go.** { *; }
-keep interface go.** { *; }

# Obfuscation diagnostics used by coverage, retrace, and shrink analysis.
-printmapping build/outputs/proguard/mapping.txt
-printseeds build/outputs/proguard/seeds.txt
-printusage build/outputs/proguard/usage.txt
