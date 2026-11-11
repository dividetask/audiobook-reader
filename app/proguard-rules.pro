# R8 shrinks the app (unused code and resources). Names are kept so stack traces in
# the debug log stay readable; most of the size saving comes from shrinking anyway.
-dontobfuscate

# JSch (SFTP) loads its ciphers, key exchanges and MACs by class name
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn org.newsclub.**
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.ietf.jgss.**

# Ktor and its logging refer to JVM-only classes that aren't used on Android
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**

# Tink (security-crypto) annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
