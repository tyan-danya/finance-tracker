package com.dtyan.spendtracker.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Форматирование и парсинг сумм. Внутри всё в копейках (Long), наружу — рубли.
 * Чистый Kotlin без Android-зависимостей, чтобы покрывалось обычными unit-тестами.
 */
object MoneyFormat {

    private const val NBSP = ' '

    /** "1 234,56 ₽" */
    fun format(minor: Long, withSymbol: Boolean = true): String {
        val negative = minor < 0
        // -Long.MIN_VALUE переполняется и даёт отрицательный "модуль" — прижимаем к MAX_VALUE.
        val abs = when {
            minor == Long.MIN_VALUE -> Long.MAX_VALUE
            negative -> -minor
            else -> minor
        }
        val rubles = abs / 100
        val kopeks = (abs % 100).toInt()
        val grouped = groupDigits(rubles)
        val sb = StringBuilder()
        if (negative) sb.append('-')
        sb.append(grouped)
        if (kopeks != 0) sb.append(',').append(kopeks.toString().padStart(2, '0'))
        if (withSymbol) sb.append(NBSP).append('₽')
        return sb.toString()
    }

    /** Компактный вид для осей графиков: 1,2к / 3,4 млн / 2,1 млрд */
    fun formatCompact(minor: Long): String {
        val rubles = minor / 100 // деление не переполняется даже для Long.MIN_VALUE
        val sign = if (rubles < 0) "-" else ""
        val abs = if (rubles < 0) -rubles else rubles
        return when {
            abs >= 1_000_000_000 -> sign + trimZero(round1(abs / 1_000_000_000.0)) + " млрд"
            abs >= 1_000_000 -> {
                val r = round1(abs / 1_000_000.0)
                // 999 999 999 ₽ округляется до 1000 млн — переносим разряд.
                if (r >= THOUSAND) sign + "1 млрд" else sign + trimZero(r) + " млн"
            }

            abs >= 1_000 -> {
                val r = round1(abs / 1_000.0)
                if (r >= THOUSAND) sign + "1 млн" else sign + trimZero(r) + "к"
            }

            else -> sign + abs.toString()
        }
    }

    private val THOUSAND: BigDecimal = BigDecimal(1000)

    /**
     * Округление до одного знака.
     * Именно [BigDecimal.valueOf], а не конструктор от Double: конструктор берёт точное
     * двоичное значение (1150/1000.0 == 1.14999...), и HALF_UP даёт 1,1 вместо 1,2.
     */
    private fun round1(value: Double): BigDecimal =
        BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP)

    private fun trimZero(rounded: BigDecimal): String {
        val s = rounded.toPlainString().replace('.', ',')
        return if (s.endsWith(",0")) s.dropLast(2) else s
    }

    private fun groupDigits(value: Long): String {
        val raw = value.toString()
        if (raw.length <= 3) return raw
        val sb = StringBuilder()
        var count = 0
        for (i in raw.indices.reversed()) {
            sb.append(raw[i])
            count++
            if (count % 3 == 0 && i != 0) sb.append(NBSP)
        }
        return sb.reverse().toString()
    }

    /**
     * Парсит пользовательский ввод суммы: "1234", "1 234,56", "1234.5", "1.234,56".
     * @return сумма в копейках или null, если ввод не распознан / отрицателен.
     */
    fun parseToMinor(input: String): Long? {
        val cleaned = input.trim()
            .replace(NBSP, ' ')
            .replace(" ", "")
            .replace("₽", "")
            .replace("руб.", "", ignoreCase = true)
            .replace("руб", "", ignoreCase = true)
        if (cleaned.isEmpty()) return null

        // Определяем десятичный разделитель — последний из . или ,
        val lastComma = cleaned.lastIndexOf(',')
        val lastDot = cleaned.lastIndexOf('.')
        val sepIndex = maxOf(lastComma, lastDot)

        val normalized = if (sepIndex >= 0) {
            val intPart = cleaned.substring(0, sepIndex).replace(".", "").replace(",", "")
            val fracPart = cleaned.substring(sepIndex + 1)
            // Если "разделитель" на самом деле группировка тысяч (ровно 3 цифры после и нет других) —
            // трактуем как дробную часть только при 1-2 цифрах.
            if (fracPart.length in 1..2) "$intPart.$fracPart" else (intPart + fracPart)
        } else {
            cleaned
        }

        if (normalized.isEmpty() || !normalized.all { it.isDigit() || it == '.' }) return null
        return try {
            val value = BigDecimal(normalized)
            if (value.signum() < 0) return null
            value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** "12,3 %". Для NaN/бесконечности (доля вида 0/0) возвращает прочерк, а не падает. */
    fun formatPercent(share: Double): String {
        if (share.isNaN() || share.isInfinite()) return "—"
        return trimZero(round1(share * 100)) + NBSP + "%"
    }
}
