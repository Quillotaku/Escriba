# Escriba (reloj). Las librerías traen sus propias reglas (serialización, Data Layer, WorkManager,
# Tiles). Aquí solo lo que R8 no puede deducir.

# Los mensajes entre reloj y móvil se serializan con kotlinx.serialization: sus serializadores
# generados se buscan por la clase companion.
-keepclassmembers @kotlinx.serialization.Serializable class dev.escriba.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
