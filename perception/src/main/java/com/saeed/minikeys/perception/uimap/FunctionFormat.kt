package com.saeed.minikeys.perception.uimap

/**
 * The exact text shown in the two verification views. Kept here (not in the Android layer) so the
 * wording itself is unit-tested — these strings are how Saeed judges whether detection is correct.
 */
object FunctionFormat {

    /** Appended to any function that exists in the activity but is scrolled out of the visible area. */
    const val OFF_SCREEN_MARK = "  · off-screen"

    /** One-line summary of what a screen offers, used by the header, the pad and the notification. */
    fun counts(functions: List<UiFunction>): String =
        "${FunctionExtractor.onScreenCount(functions)} on screen · " +
            "${FunctionExtractor.offScreenCount(functions)} off screen"

    /**
     * Turns Android's fully-qualified activity class into the short form a human reads.
     *
     * `com.ai.assistance.operit` + `com.ai.assistance.operit.MainActivity` becomes
     * `com.ai.assistance.operit/.MainActivity`. Anonymous/inner classes (`Foo$1`) and framework
     * classes are not a meaningful screen name, so they collapse to the package alone.
     */
    fun activityShort(packageName: String, activityName: String): String {
        if (activityName.isBlank()) return ""
        if (activityName.contains('$')) return ""
        if (activityName.startsWith("android.")) return ""
        if (activityName == "android.widget.FrameLayout") return ""
        val pkg = packageName.trim()
        if (pkg.isNotEmpty() && activityName.startsWith(pkg)) {
            val rest = activityName.removePrefix(pkg)
            if (rest.isEmpty()) return pkg
            return pkg + "/" + if (rest.startsWith(".")) rest else ".$rest"
        }
        return activityName
    }

    /** The 5-second toast shown every time the user enters an app or an activity. */
    fun focusToast(focus: FocusInfo): String = buildString {
        append("FocusMap ▸ ").append(focus.appLabel).append('\n')
        val where = activityShort(focus.packageName, focus.activityName)
        append(if (where.isBlank()) focus.packageName else where)
    }

    /** Header line drawn at the top of the box overlay. */
    fun header(focus: FocusInfo?, functions: List<UiFunction>, boxesOn: Boolean): String {
        val where = focus?.let { it.appLabel + " · " + it.packageName } ?: "no app detected yet"
        val state = if (boxesOn) counts(functions) else "boxes OFF"
        return "FocusMap ▸ $where ▸ $state"
    }

    /** One line of the function list: `#3 [tap] Send`, with an honest marker when it is off-screen. */
    fun line(function: UiFunction): String = buildString {
        append("#").append(function.index)
        append(" [").append(function.category.label).append("] ")
        append(function.label)
        if (!function.onScreen) append(OFF_SCREEN_MARK)
    }

    /** The 10-second list toast body. */
    fun listText(focus: FocusInfo?, functions: List<UiFunction>): String = buildString {
        append("FocusMap — functions in this activity\n")
        append(focus?.display() ?: "unknown app")
        append("  (").append(counts(functions)).append(")\n")
        if (functions.isEmpty()) {
            append("\nNo actionable element found.\n")
            append("Either this screen has none, or the accessibility service is not seeing the tree.")
        } else {
            for (function in functions) {
                append('\n').append(line(function))
            }
        }
    }

    /** Header of the interactive action pad. */
    fun actionHeader(focus: FocusInfo?, functions: List<UiFunction>): String {
        val where = focus?.appLabel ?: "no app"
        return "$where — ${counts(functions)}"
    }

    /** Compact one-liner for the notification body. */
    fun notificationSummary(focus: FocusInfo?, functions: List<UiFunction>, limit: Int = 3): String {
        if (focus == null) return "Waiting for a window state change…"
        val head = focus.appLabel + " · " + counts(functions)
        if (functions.isEmpty()) return head
        val sample = functions.take(limit).joinToString(", ") { it.label }
        return "$head — $sample…"
    }

    /** In-app table row: includes bounds so a mis-detected rectangle can be reasoned about. */
    fun detailRow(function: UiFunction): String =
        "#${function.index} [${function.category.label}] ${function.label}  ${function.bounds}  ${function.detail}"
}
