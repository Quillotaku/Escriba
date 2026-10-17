package dev.escriba.shared

/**
 * Contract v1 between watch and phone over the Wearable Data Layer.
 *
 * Only MessageClient and ChannelClient are used, always towards a node with `isNearby == true`.
 * DataClient is ruled out because its DataItems also sync through the cloud node.
 */
object Protocol {
    const val VERSION = 1

    private const val ROOT = "/escriba/v$VERSION"

    /** Prefix filtered by the WearableListenerService of both apps. */
    const val PATH_PREFIX = "/escriba/"

    const val PATH_PING = "$ROOT/ping"
    const val PATH_PONG = "$ROOT/pong"
    const val PATH_PAIR_INIT = "$ROOT/pair/init"
    const val PATH_PAIR_REPLY = "$ROOT/pair/reply"
    const val PATH_PAIR_REVEAL = "$ROOT/pair/reveal"
    const val PATH_PAIR_CONFIRM = "$ROOT/pair/confirm"
    /** Plain and empty: "I have no link with you" (e.g. after the app's data was cleared). */
    const val PATH_PAIR_UNKNOWN = "$ROOT/pair/unknown"
    const val PATH_PROFILES = "$ROOT/profiles"
    const val PATH_PROFILES_REQUEST = "$ROOT/profiles/request"
    const val PATH_STATUS = "$ROOT/status"
    const val PATH_FRAGMENT = "$ROOT/fragment"
    const val PATH_ACK = "$ROOT/ack"
    const val PATH_SESSION_END = "$ROOT/session-end"
    const val PATH_SESSION_END_ACK = "$ROOT/session-end/ack"

    /** Authenticated contexts for encrypting a fragment (header and audio). */
    const val SEAL_FRAGMENT_HEADER = "fragment/header"

    fun sealFragmentAudio(fragmentKey: String) = "fragment/audio/$fragmentKey"

    /** Capabilities declared in each app's res/values/wear.xml. */
    const val CAPABILITY_PHONE = "escriba_phone"
    const val CAPABILITY_WATCH = "escriba_watch"
}
