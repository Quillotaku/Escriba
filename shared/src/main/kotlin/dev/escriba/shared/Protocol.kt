package dev.escriba.shared

/**
 * Contrato v1 entre reloj y móvil sobre la Wearable Data Layer.
 *
 * Solo se usan MessageClient y ChannelClient, y siempre hacia un nodo con
 * `isNearby == true`. DataClient queda descartado porque sus DataItems se
 * sincronizan también con el nodo en la nube.
 */
object Protocol {
    const val VERSION = 1

    private const val ROOT = "/escriba/v$VERSION"

    /** Prefijo que filtran los WearableListenerService de ambas apps. */
    const val PATH_PREFIX = "/escriba/"

    const val PATH_PING = "$ROOT/ping"
    const val PATH_PONG = "$ROOT/pong"
    const val PATH_PAIR = "$ROOT/pair"
    const val PATH_PROFILES = "$ROOT/perfiles"
    const val PATH_PROFILES_REQUEST = "$ROOT/perfiles/pedir"
    const val PATH_STATUS = "$ROOT/estado"
    const val PATH_FRAGMENT = "$ROOT/fragmento"
    const val PATH_ACK = "$ROOT/ack"
    const val PATH_SESSION_END = "$ROOT/fin-sesion"

    /** Capacidades declaradas en res/values/wear.xml de cada app. */
    const val CAPABILITY_PHONE = "escriba_phone"
    const val CAPABILITY_WATCH = "escriba_watch"
}
