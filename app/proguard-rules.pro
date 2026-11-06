# WebRTC (JNI يستدعي الفئات بالاسم)
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }
-dontwarn org.webrtc.**
# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
