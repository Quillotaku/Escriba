# Escriba (móvil). Las librerías traen sus propias reglas (Room, serialización, Data Layer,
# WorkManager, Compose). Aquí solo lo que R8 no puede deducir.

# sherpa-onnx: su código nativo lee por nombre los campos de las clases de configuración y crea sus
# resultados con FindClass. Su AAR no trae reglas, así que se conserva entero.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# whisper.cpp y llama.cpp: los métodos nativos se buscan por el nombre de su clase.
-keepclasseswithmembernames class dev.escriba.** {
    native <methods>;
}

# Mensajes entre reloj y móvil y datos guardados en JSON (kotlinx.serialization).
-keepclassmembers @kotlinx.serialization.Serializable class dev.escriba.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
