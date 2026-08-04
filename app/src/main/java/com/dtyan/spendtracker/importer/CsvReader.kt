package com.dtyan.spendtracker.importer

/**
 * Минимальный потоковый парсер CSV по RFC 4180.
 * Поддерживает: настраиваемый разделитель, поля в кавычках, удвоенные кавычки внутри поля,
 * переводы строк внутри закавыченного поля, окончания строк \n и \r\n.
 *
 * Чистый Kotlin без Android — покрывается обычными unit-тестами.
 */
object CsvReader {

    /**
     * Разбирает весь текст в список строк, каждая строка — список полей.
     * Пустые физические строки (вне кавычек) пропускаются.
     */
    fun parse(text: String, delimiter: Char = ';'): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var field = StringBuilder()
        var row = ArrayList<String>()
        var inQuotes = false
        var i = 0
        var fieldStarted = false

        fun endField() {
            row.add(field.toString())
            field = StringBuilder()
            fieldStarted = false
        }

        fun endRow() {
            endField()
            // Пропускаем строки, состоящие из единственного пустого поля.
            if (!(row.size == 1 && row[0].isEmpty())) {
                rows.add(row)
            }
            row = ArrayList()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"'); i++ // удвоенная кавычка → одна кавычка
                    }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' && !fieldStarted -> { inQuotes = true; fieldStarted = true }
                c == delimiter -> endField()
                c == '\r' -> { /* игнорируем — обработаем на \n или в конце */ }
                c == '\n' -> endRow()
                else -> { field.append(c); fieldStarted = true }
            }
            i++
        }
        // Последнее поле/строка без завершающего перевода строки.
        if (field.isNotEmpty() || row.isNotEmpty() || fieldStarted) {
            endRow()
        }
        return rows
    }
}
