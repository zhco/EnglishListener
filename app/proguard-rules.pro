# Keep sherpa-onnx classes (JNI reflection)
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Keep JNA classes and our Structure subclasses
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-keep class com.englishlistener.translate.** { *; }
