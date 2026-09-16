# libwebrtc: classes are reached from native code through JNI. The AAR ships no consumer rules.
# JNI_OnLoad looks up org.jni_zero classes by name; renaming them aborts the process with
# "JNI DETECTED ERROR: java_class == null" the moment a call starts (found on a Redmi Note 11).
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
-dontwarn org.webrtc.**
-dontwarn org.jni_zero.**

# Any class with native methods must keep its name and those methods for JNI registration.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# libsodium through JNA (multiplatform-crypto-libsodium-bindings).
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.ionspin.kotlin.crypto.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# SQLCipher native bindings.
-keep class net.zetetic.database.** { *; }

# Wire-generated protocol messages.
-keep class app.zoocall.protocol.v1.** { *; }

# JmDNS / desktop-only code paths referenced from shared modules.
-dontwarn javax.jmdns.**
-dontwarn org.slf4j.**
