# SPDX-License-Identifier: GPL-3.0-or-later
# KUN -->
# Keep rules for :core:dictionary.
#
# libhoshidicts_jni.so resolves these classes by *exact* binary name + constructor
# signature via JNI FindClass/GetMethodID, so R8 must not rename or strip them.
# The matching signature strings live in src/main/cpp/hoshidicts_jni.cpp — change
# both together (contract §2.1/§3.5).

-keep class mihon.core.dictionary.engine.** { <init>(...); *; }
-keep class mihon.core.dictionary.model.** { <init>(...); *; }
-keepclassmembers class mihon.core.dictionary.engine.** { <init>(...); *; }
-keepclassmembers class mihon.core.dictionary.model.** { <init>(...); *; }

# Keep dictionary WebView JavaScript bridges (called reflectively by WebView / JavaScript).
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class mihon.core.dictionary.ui.DictionaryReadyBridge, mihon.core.dictionary.ui.PayloadBridge, mihon.core.dictionary.ui.AnkiJsBridge, mihon.core.dictionary.ui.WordAudioBridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keepattributes Signature
-keepattributes *Annotation*
-keep class androidx.annotation.Keep
# KUN <--
