# Escriba (watch). Libraries bring their own rules (serialization, Data Layer, WorkManager, Tiles).
# Only what R8 can't work out goes here.

# Messages between watch and phone are serialized with kotlinx.serialization: their generated
# serializers are looked up through the companion class.
-keepclassmembers @kotlinx.serialization.Serializable class dev.escriba.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
