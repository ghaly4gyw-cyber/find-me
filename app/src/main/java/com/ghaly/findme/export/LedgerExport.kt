package com.ghaly.findme.export

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.ghaly.findme.model.LedgerAccount
import com.ghaly.findme.model.LedgerCurrency
import com.ghaly.findme.model.LedgerDirection
import com.ghaly.findme.ui.currencyLabel
import com.ghaly.findme.ui.formatLedgerAmount
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Locale

/** Turns the ledger into a PDF statement or a CSV (opens fine in Excel/Sheets)
 *  that can be shared off the phone — the two export buttons on the Accounts
 *  screen. Neither ever leaves the device on its own; both just hand a file
 *  to Android's share sheet so the person picks where it goes (WhatsApp,
 *  email, Bluetooth, a USB cable — whatever actually works for them). */
object LedgerExport {

    private const val PAGE_WIDTH = 595 // A4 @ 72dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 36

    fun exportPdf(context: Context, accounts: List<LedgerAccount>, title: String): File {
        val isRtl = context.resources.configuration.locales[0].language == "ar"
        // ALIGN_NORMAL means "start of the paragraph", which follows
        // whichever direction textDir below sets — so this one value
        // correctly right-aligns Arabic and left-aligns everything else.
        val align = Layout.Alignment.ALIGN_NORMAL
        val textDir = if (isRtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR

        val titlePaint = TextPaint().apply { textSize = 16f; isFakeBoldText = true; color = Color.BLACK }
        val headerPaint = TextPaint().apply { textSize = 12f; isFakeBoldText = true; color = Color.BLACK }
        val bodyPaint = TextPaint().apply { textSize = 10f; color = Color.BLACK }

        val doc = PdfDocument()
        var pageNumber = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN.toFloat()
        val contentWidth = PAGE_WIDTH - 2 * MARGIN

        fun drawLine(text: String, paint: TextPaint, gapAfter: Float = 4f) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
                .setAlignment(align)
                .setTextDirection(textDir)
                .build()
            if (y + layout.height > PAGE_HEIGHT - MARGIN) {
                doc.finishPage(page)
                pageNumber++
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                canvas = page.canvas
                y = MARGIN.toFloat()
            }
            canvas.save()
            canvas.translate(MARGIN.toFloat(), y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + gapAfter
        }

        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        drawLine(title, titlePaint, 2f)
        drawLine(dateFmt.format(System.currentTimeMillis()), bodyPaint, 14f)

        for (account in accounts.sortedByDescending { it.ts }) {
            val header = buildString {
                append(account.name)
                if (!account.phone.isNullOrBlank()) append(" — ").append(account.phone)
            }
            drawLine(header, headerPaint, 2f)

            for (currency in LedgerCurrency.values()) {
                val hasAny = account.entries.any { it.currency == currency }
                if (!hasAny) continue
                val balance = account.balance(currency)
                val label = currencyLabel(context, currency)
                val amountText = formatLedgerAmount(kotlin.math.abs(balance))
                val sign = if (balance >= 0) "+" else "-"
                drawLine("$sign$amountText $label", bodyPaint, 2f)
            }

            for (entry in account.entries.sortedByDescending { it.ts }) {
                val sign = if (entry.direction == LedgerDirection.THEY_OWE_ME) "+" else "-"
                val label = currencyLabel(context, entry.currency)
                val line = buildString {
                    append(dateFmt.format(entry.ts)).append("  ")
                    append(sign).append(formatLedgerAmount(entry.amount)).append(' ').append(label)
                    if (entry.details.isNotBlank()) append("  — ").append(entry.details)
                }
                drawLine(line, bodyPaint, 2f)
            }
            y += 10f
        }

        doc.finishPage(page)
        val outFile = File(context.cacheDir, "find_me_ledger_${System.currentTimeMillis()}.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        return outFile
    }

    fun exportCsv(context: Context, accounts: List<LedgerAccount>): File {
        val outFile = File(context.cacheDir, "find_me_ledger_${System.currentTimeMillis()}.csv")
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        OutputStreamWriter(FileOutputStream(outFile), Charsets.UTF_8).use { writer ->
            // UTF-8 BOM so Excel opens Arabic text correctly instead of mangling it.
            writer.write("﻿")
            writer.write("Account,Phone,Date,Direction,Amount,Currency,Details\n")
            for (account in accounts.sortedByDescending { it.ts }) {
                if (account.entries.isEmpty()) {
                    writer.write("${csvEscape(account.name)},${csvEscape(account.phone ?: "")},,,,,\n")
                    continue
                }
                for (entry in account.entries.sortedByDescending { it.ts }) {
                    val direction = if (entry.direction == LedgerDirection.THEY_OWE_ME) "they_owe_me" else "i_owe_them"
                    val row = listOf(
                        account.name,
                        account.phone ?: "",
                        dateFmt.format(entry.ts),
                        direction,
                        formatLedgerAmount(entry.amount),
                        entry.currency.name,
                        entry.details
                    )
                    writer.write(row.joinToString(",") { csvEscape(it) } + "\n")
                }
            }
        }
        return outFile
    }

    private fun csvEscape(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /** Hands [file] to Android's own share sheet via this app's FileProvider —
     *  the same provider already set up for installing update APKs. */
    fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }
}
