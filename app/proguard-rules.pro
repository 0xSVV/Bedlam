-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# kotlinx.serialization, Room, Decompose, MVIKotlin, DataStore all ship
# their own consumer ProGuard rules — nothing to add here for them.

# Gomobile bindings are kept via :hysteria's consumer-rules.pro.

# Obfuscation diagnostics used by coverage, retrace, and shrink analysis.
-printmapping build/outputs/proguard/mapping.txt
-printseeds build/outputs/proguard/seeds.txt
-printusage build/outputs/proguard/usage.txt
