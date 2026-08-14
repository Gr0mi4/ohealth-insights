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

                The app creates compressed full or incremental exports and saves them to a file location that you choose.

                If you turn on Google Drive upload, those exports and the reports derived from them are sent to your own Google Drive account. The app asks only for access to the files it creates there, and it sends your health data to no other destination.

                A local sync checkpoint is advanced only after an export has been saved or uploaded successfully.

                You can revoke Health Connect access at any time in Health Connect settings, and Drive access at any time in your Google account.
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
