package com.glomopay.sdk.android.sampleApp

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import com.glomopay.sdk.android.ConnectionError
import com.glomopay.sdk.android.GlomoColorScheme
import com.glomopay.sdk.android.GlomoPayCheckoutHandle
import com.glomopay.sdk.android.GlomoPayConfig
import com.glomopay.sdk.android.GlomoPayListener
import com.glomopay.sdk.android.GlomoPayPayload
import com.glomopay.sdk.android.GlomoPayUserJourneyPayload
import com.glomopay.sdk.android.GlomoPaySdk
import com.glomopay.sdk.android.SdkError
import com.glomopay.sdk.android.TerminationSource

/** Manual integration wrapper for exercising the native SDK. */
class MainActivity : Activity(), GlomoPayListener {
    private val backgroundColor = Color.rgb(252, 248, 255)
    private val purple = Color.rgb(105, 83, 156)
    private val lavender = Color.rgb(218, 198, 255)
    private val ink = Color.rgb(40, 27, 62)
    private val muted = Color.rgb(103, 94, 112)

    private lateinit var publicKeyInput: EditText
    private lateinit var identifierInput: EditText
    private lateinit var statusLabel: TextView
    private lateinit var eventLog: TextView

    private var appColorScheme = GlomoColorScheme.AUTO

    /** Kept so the scheme picker can reach a checkout that is already on screen. */
    private var checkoutHandle: GlomoPayCheckoutHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
    }

    private fun buildScreen(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            setBackgroundColor(backgroundColor)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(20))
            background = rounded(lavender, 24)
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_kotlin)
            contentDescription = "Kotlin logo"
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        header.addView(TextView(this).apply {
            text = "GlomoPay SDK Tester"
            textSize = 25f
            setTextColor(ink)
            gravity = android.view.Gravity.CENTER
        })
        header.addView(TextView(this).apply {
            text = "Kotlin native integration wrapper"
            textSize = 13f
            setTextColor(muted)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        })
        content.addView(header, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))

        publicKeyInput = field("Public Key", "Enter public key")
        identifierInput = field("Order ID / Subscription ID", "Enter order ID or subscription ID")
        content.addView(labeledField("Public Key", publicKeyInput), fieldGroupParams())
        content.addView(labeledField("Order ID / Subscription ID", identifierInput), fieldGroupParams())

        content.addView(
            segmentedRow(
                "SDK Checkout colour scheme",
                listOf(
                    "Auto" to GlomoColorScheme.AUTO,
                    "Light" to GlomoColorScheme.LIGHT,
                    "Dark" to GlomoColorScheme.DARK,
                ),
                appColorScheme,
            ) { scheme ->
                appColorScheme = scheme
                // Reaches a checkout that is already open; dropped when there is none. That is
                // what makes this the live-push path rather than a second way to set the config.
                checkoutHandle?.setAppColorScheme(scheme)
                appendEvent("appColorScheme=$scheme")
            },
            fieldGroupParams(),
        )

        content.addView(
            segmentedRow(
                "Host App's night mode (Android X)",
                listOf(
                    "System" to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                    "Light" to AppCompatDelegate.MODE_NIGHT_NO,
                    "Dark" to AppCompatDelegate.MODE_NIGHT_YES,
                ),
                // Normalised to System: before a host sets one the delegate reports
                // MODE_NIGHT_UNSPECIFIED, which matches no button and leaves the row blank.
                // AUTO_BATTERY collapses here too - the SDK reads both as "host has not chosen".
                when (val mode = AppCompatDelegate.getDefaultNightMode()) {
                    AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES -> mode
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                },
            ) { mode ->
                // The first source AUTO consults. This screen is a plain Activity, so it does not
                // restyle itself - the point is only to move what the SDK reads.
                AppCompatDelegate.setDefaultNightMode(mode)
                appendEvent("defaultNightMode=$mode")
            },
            fieldGroupParams(),
        )

        content.addView(Button(this).apply {
            text = "START CHECKOUT"
            textSize = 14f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = rounded(purple, 28)
            setOnClickListener { startCheckout() }
        }, LinearLayout.LayoutParams(-1, dp(54)).apply {
            topMargin = dp(16)
            bottomMargin = dp(18)
        })

        content.addView(TextView(this).apply {
            text = "LAST PAYMENT RESULT"
            textSize = 15f
            setTextColor(ink)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        })
        statusLabel = TextView(this).apply {
            text = "Status: Ready"
            textSize = 15f
            setTextColor(ink)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.WHITE, 18, Color.rgb(222, 210, 234))
        }
        content.addView(statusLabel)

        eventLog = TextView(this).apply {
            text = "Events:\n"
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        content.addView(ScrollView(this).apply {
            addView(eventLog)
            background = rounded(Color.WHITE, 18, Color.rgb(232, 222, 242))
        }, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(12) })

        return ScrollView(this).apply { addView(content) }
    }

    private fun startCheckout() {
        val publicKey = publicKeyInput.text.toString().trim()
        val identifier = identifierInput.text.toString().trim()
        if (publicKey.isEmpty()) {
            publicKeyInput.error = "Please enter public key"
            publicKeyInput.requestFocus()
            return
        }
        if (identifier.isEmpty()) {
            identifierInput.error = "Please enter order ID or subscription ID"
            identifierInput.requestFocus()
            return
        }

        val state = CheckoutFormState(publicKey, identifier, appColorScheme)
        try {
            val config: GlomoPayConfig = state.toConfig()
            statusLabel.text = "Status: Starting"
            appendEvent("startCheckout type=auto (API detected) colorScheme=$appColorScheme")
            checkoutHandle = GlomoPaySdk.startCheckout(this, config, this, "auto")
        } catch (error: IllegalArgumentException) {
            statusLabel.text = "Status: Validation error"
            toast(error.message ?: "Invalid checkout form")
            appendEvent("validation.error ${error.message}")
        }
    }

    override fun onPaymentSuccess(payload: GlomoPayPayload) {
        updateStatus("Payment successful")
        appendEvent("payment.success")
    }

    override fun onPaymentFailure(payload: GlomoPayPayload) {
        updateStatus("Payment failed")
        appendEvent("payment.failure")
    }

    override fun onUserJourneyCompleted(payload: GlomoPayUserJourneyPayload) {
        updateStatus("Bank transfer submitted")
        appendEvent("journey.completed type=${payload.journeyType} orderId=${payload.orderId} ref=${payload.transactionReference}")
    }

    override fun onSdkError(errors: List<SdkError>) {
        val error = errors.firstOrNull()
        updateStatus("SDK error")
        appendEvent("sdk.error ${error?.type}: ${error?.message}")
    }

    override fun onConnectionError(error: ConnectionError) {
        updateStatus("Connection error")
        appendEvent("connection.error ${error.type}: ${error.message}")
    }

    override fun onPaymentTerminate(source: TerminationSource) {
        updateStatus("Checkout terminated")
        appendEvent("payment.terminated source=$source")
    }

    override fun onEvent(name: String, payload: Map<String, Any?>) {
        appendEvent("$name ${payload.keys.joinToString()}")
    }

    private fun updateStatus(text: String) = runOnUiThread { statusLabel.text = "Status: $text" }
    private fun appendEvent(text: String) = runOnUiThread { eventLog.append("• $text\n") }

    private fun field(label: String, placeholder: String): EditText = EditText(this).apply {
        hint = placeholder
        contentDescription = label
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT
        textSize = 15f
        setTextColor(ink)
        setHintTextColor(muted)
        setPadding(dp(16), 0, dp(16), 0)
        background = rounded(Color.WHITE, 16, Color.rgb(181, 166, 194))
    }

    private fun labeledField(label: String, input: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 13f
            setTextColor(ink)
            setPadding(dp(4), 0, 0, dp(5))
        })
        addView(input, LinearLayout.LayoutParams(-1, dp(56)))
    }

    /**
     * A labelled row of mutually exclusive buttons.
     *
     * [initial] is read once; the row owns the selection from then on, so a caller that also keeps
     * the value has to update it in [onSelect].
     */
    private fun <T> segmentedRow(
        label: String,
        options: List<Pair<String, T>>,
        initial: T,
        onSelect: (T) -> Unit,
    ): View {
        val buttons = mutableListOf<Pair<Button, T>>()
        var current = initial

        fun repaint() = buttons.forEach { (button, value) ->
            val active = value == current
            button.background = rounded(if (active) purple else Color.WHITE, 20, lavender)
            button.setTextColor(if (active) Color.WHITE else ink)
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEach { (title, value) ->
            val button = Button(this).apply {
                text = title
                textSize = 13f
                isAllCaps = false
                contentDescription = "$label $title"
                setOnClickListener {
                    current = value
                    repaint()
                    onSelect(value)
                }
            }
            buttons += button to value
            row.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = if (buttons.size == 1) 0 else dp(8)
            })
        }
        repaint()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                setTextColor(ink)
                setPadding(dp(4), 0, 0, dp(5))
            })
            addView(row)
        }
    }

    private fun fieldGroupParams() = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(14)
    }

    private fun cardParams() = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(14)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
