# Proguard rules for Personal Live Voicemail

# Keep AIDL interfaces and generated classes
-keep class com.personal.voicemail.ipc.** { *; }
-keep interface com.personal.voicemail.ipc.** { *; }

# Keep Shizuku UserService and API
-keep class dev.rikka.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class com.personal.voicemail.service.VoicemailUserService { *; }

# Keep Sherpa-ONNX native bindings and model classes
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Keep HiddenApiBypass
-keep class org.lsposed.hiddenapibypass.** { *; }

# Keep Room database entities
-keep class com.personal.voicemail.data.** { *; }
