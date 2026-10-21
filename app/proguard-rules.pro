# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.xpdt.exbloattv.**$$serializer { *; }
-keepclassmembers class dev.xpdt.exbloattv.** { *** Companion; }
-keepclasseswithmembers class dev.xpdt.exbloattv.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp / Okio / dadb optional deps
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
