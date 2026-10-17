# Escriba (phone). Libraries bring their own rules (Room, serialization, Data Layer, WorkManager,
# Compose). Only what R8 can't work out goes here.

# sherpa-onnx: its native code reads the config classes' fields by name and creates its results
# with FindClass. Its AAR has no rules, so it's kept whole.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# whisper.cpp and llama.cpp: native methods are looked up by their class name.
-keepclasseswithmembernames class dev.escriba.** {
    native <methods>;
}

# Messages between watch and phone and data stored as JSON (kotlinx.serialization).
-keepclassmembers @kotlinx.serialization.Serializable class dev.escriba.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
