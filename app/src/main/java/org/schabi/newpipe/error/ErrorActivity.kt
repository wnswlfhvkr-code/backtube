/*
 * SPDX-FileCopyrightText: 2015-2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.error

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.grack.nanojson.JsonWriter
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ActivityErrorBinding
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.ThemeHelper
import org.schabi.newpipe.util.external_communication.ShareUtils
import org.schabi.newpipe.util.text.setTextWithLinks

/**
 * This activity shows local error details that the user can copy or share.
 * Use [ErrorUtil.openActivity] to correctly open this activity.
 */
class ErrorActivity : AppCompatActivity() {
    private lateinit var errorInfo: ErrorInfo
    private lateinit var currentTimeStamp: String

    private lateinit var binding: ActivityErrorBinding

    private val contentCountryString: String
        get() = Localization.getPreferredContentCountry(this).countryCode

    private val contentLanguageString: String
        get() = Localization.getPreferredLocalization(this).localizationCode

    private val appLanguage: String
        get() = Localization.getAppLocale().toString()

    private val osString: String
        get() {
            val name = System.getProperty("os.name")!!
            val osBase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Build.VERSION.BASE_OS.ifEmpty { "Android" }
            } else {
                "Android"
            }
            return "$name $osBase ${Build.VERSION.RELEASE} - ${Build.VERSION.SDK_INT}"
        }

    // /////////////////////////////////////////////////////////////////////
    // Activity lifecycle
    // /////////////////////////////////////////////////////////////////////

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeHelper.setDayNightMode(this)
        ThemeHelper.setTheme(this)

        binding = ActivityErrorBinding.inflate(layoutInflater)
        setContentView(binding.getRoot())

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.backtube_error_details_title)
            setDisplayShowTitleEnabled(true)
        }

        errorInfo = IntentCompat.getParcelableExtra(intent, ERROR_INFO, ErrorInfo::class.java)!!

        // important add guru meditation
        addGuruMeditation()
        // print current time, as zoned ISO8601 timestamp
        currentTimeStamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        setupErrorActions()

        // normal bugreport
        buildInfo(errorInfo)
        binding.errorMessageView.setTextWithLinks(errorInfo.getMessage(this))
        binding.errorView.text = formErrorText(errorInfo.stackTraces)

        // print stack trace once again for debugging:
        errorInfo.stackTraces.forEach { Log.e(TAG, it) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.error_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }

            R.id.menu_item_share_error -> {
                ShareUtils.shareText(
                    applicationContext,
                    getString(R.string.backtube_error_details_title),
                    buildJson()
                )
                true
            }

            else -> false
        }
    }

    private fun setupErrorActions() {
        binding.errorReportCopyButton.setOnClickListener {
            ShareUtils.copyToClipboard(this, buildMarkdown())
        }
    }

    private fun formErrorText(stacktrace: Array<String>): String {
        val separator = "-------------------------------------"
        return stacktrace.joinToString(separator + "\n", separator + "\n", separator)
    }

    private fun buildInfo(info: ErrorInfo) {
        binding.errorInfoLabelsView.text = getString(R.string.info_labels)

        val text = info.userAction.message + "\n" +
            info.request + "\n" +
            contentLanguageString + "\n" +
            contentCountryString + "\n" +
            appLanguage + "\n" +
            info.getServiceName() + "\n" +
            currentTimeStamp + "\n" +
            packageName + "\n" +
            BuildConfig.VERSION_NAME + "\n" +
            osString

        binding.errorInfosView.text = text
    }

    private fun buildJson(): String {
        try {
            return JsonWriter.string()
                .`object`()
                .value("user_action", errorInfo.userAction.message)
                .value("request", errorInfo.request)
                .value("content_language", contentLanguageString)
                .value("content_country", contentCountryString)
                .value("app_language", appLanguage)
                .value("service", errorInfo.getServiceName())
                .value("package", packageName)
                .value("version", BuildConfig.VERSION_NAME)
                .value("os", osString)
                .value("time", currentTimeStamp)
                .array("exceptions", errorInfo.stackTraces.toList())
                .value("user_comment", binding.errorCommentBox.getText().toString())
                .end()
                .done()
        } catch (exception: Exception) {
            Log.e(TAG, "Error while erroring: Could not build json", exception)
        }

        return ""
    }

    private fun buildMarkdown(): String {
        try {
            return buildString(1024) {
                val userComment = binding.errorCommentBox.text.toString()
                if (userComment.isNotEmpty()) {
                    appendLine(userComment)
                }

                // basic error info
                appendLine("## Exception")
                appendLine("* __User Action:__ ${errorInfo.userAction.message}")
                appendLine("* __Request:__ ${errorInfo.request}")
                appendLine("* __Content Country:__ $contentCountryString")
                appendLine("* __Content Language:__ $contentLanguageString")
                appendLine("* __App Language:__ $appLanguage")
                appendLine("* __Service:__ ${errorInfo.getServiceName()}")
                appendLine("* __Timestamp:__ $currentTimeStamp")
                appendLine("* __Package:__ $packageName")
                appendLine("* __Service:__ ${errorInfo.getServiceName()}")
                appendLine("* __Version:__ ${BuildConfig.VERSION_NAME}")
                appendLine("* __OS:__ $osString")

                // Collapse all logs to a single paragraph when there are more than one
                // to keep the GitHub issue clean.
                if (errorInfo.stackTraces.size > 1) {
                    append("<details><summary><b>Exceptions (")
                    append(errorInfo.stackTraces.size)
                    append(")</b></summary><p>\n")
                }

                // add the logs
                errorInfo.stackTraces.forEachIndexed { index, stacktrace ->
                    append("<details><summary><b>Crash log ")
                    if (errorInfo.stackTraces.size > 1) {
                        append(index + 1)
                    }
                    append("</b>")
                    append("</summary><p>\n")
                    append("\n```\n${stacktrace}\n```\n")
                    append("</details>\n")
                }

                // make sure to close everything
                if (errorInfo.stackTraces.size > 1) {
                    append("</p></details>\n")
                }

                append("<hr>\n")
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Error while erroring: Could not build markdown", exception)
            return ""
        }
    }

    private fun addGuruMeditation() {
        // just an easter egg
        var text = binding.errorSorryView.text.toString()
        text += "\n" + getString(R.string.guru_meditation)
        binding.errorSorryView.text = text
    }

    companion object {
        // LOG TAGS
        private val TAG = ErrorActivity::class.java.toString()

        // BUNDLE TAGS
        const val ERROR_INFO = "error_info"
    }
}
