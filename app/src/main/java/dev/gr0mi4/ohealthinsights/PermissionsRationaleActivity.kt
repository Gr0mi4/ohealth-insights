package dev.gr0mi4.ohealthinsights

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val body = TextView(this).apply {
            text = """
                OHealth Insights reads health and fitness records only after you approve the requested Health Connect permissions.

                The diagnostic build exports records to a file location that you choose. It does not upload health data and does not request internet access.

                You can revoke access at any time in Health Connect settings.
            """.trimIndent()
            textSize = 18f
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padding, padding, padding, padding)
                addView(body)
            },
        )
    }
}

