package cc.nekocat.waylamaps.voice

/** Prompt priority from CLAUDE.md, lowest first so [compareTo] orders by urgency. */
enum class Priority {
    CHATTER,
    TRAFFIC,
    SPEED_CAMERA,
    TURN,
}
