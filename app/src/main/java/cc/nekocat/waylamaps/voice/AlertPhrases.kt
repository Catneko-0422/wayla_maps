package cc.nekocat.waylamaps.voice

import cc.nekocat.waylamaps.alerts.AlertDescription
import cc.nekocat.waylamaps.alerts.CameraAheadDetector

/** Picks the `alert_ahead*` phrase and its arguments for a camera or enforcement point ahead. */
object AlertPhrases {
    /**
     * @param limitSlots limits with recorded fragments; a limit outside them (new data) is left
     *   out rather than dropping the whole prompt.
     */
    fun ahead(
        description: AlertDescription,
        distanceM: Double,
        limitKmh: Int?,
        limitSlots: Collection<String>,
    ): Prompt {
        val args = mutableMapOf<String, Any>(
            "distance" to CameraAheadDetector.spokenDistanceMeters(distanceM),
            "type" to description.type,
        )
        val limit = limitKmh?.takeIf { it.toString() in limitSlots }
        limit?.let { args["limit"] = it }
        description.context?.let { args["context"] = it }
        val phraseId = buildString {
            append("alert_ahead")
            if (description.context != null) append("_context")
            if (limit != null) append("_limit")
        }
        return Prompt(phraseId, args, Priority.SPEED_CAMERA)
    }
}
