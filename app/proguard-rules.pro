# Keep line numbers in stack traces from release crashes, but hide
# original filenames (R8 still produces mapping.txt for full deobfuscation).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization, Room, Decompose, MVIKotlin, DataStore all ship
# their own consumer ProGuard rules — nothing to add here for them.

# Gomobile bindings are kept via :hysteria's consumer-rules.pro.

# Obfuscation diagnostics used by coverage, retrace, and shrink analysis.
-printmapping build/outputs/proguard/mapping.txt
-printseeds build/outputs/proguard/seeds.txt
-printusage build/outputs/proguard/usage.txt
