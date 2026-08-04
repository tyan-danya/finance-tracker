package com.dtyan.spendtracker.importer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CsvReaderTest {

    // В тестовых данных '~' заменяет двойную кавычку, чтобы не конфликтовать с raw-строками Kotlin.
    private fun csv(s: String) = s.replace('~', '"')

    @Test
    fun `простые строки с точкой с запятой`() {
        val rows = CsvReader.parse("a;b;c\n1;2;3", ';')
        assertThat(rows).hasSize(2)
        assertThat(rows[0]).containsExactly("a", "b", "c").inOrder()
        assertThat(rows[1]).containsExactly("1", "2", "3").inOrder()
    }

    @Test
    fun `поля в кавычках`() {
        val rows = CsvReader.parse(csv("~a~;~b~;~c~"), ';')
        assertThat(rows[0]).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `разделитель внутри кавычек не разрывает поле`() {
        val rows = CsvReader.parse(csv("~Перевод; спасибо~;~200~"), ';')
        assertThat(rows[0]).containsExactly("Перевод; спасибо", "200").inOrder()
    }

    @Test
    fun `удвоенные кавычки превращаются в одну`() {
        // CSV: "ООО ""РОГА""";"x"  → поле1 = ООО "РОГА"
        val rows = CsvReader.parse(csv("~ООО ~~РОГА~~~;~x~"), ';')
        assertThat(rows[0][0]).isEqualTo(csv("ООО ~РОГА~"))
    }

    @Test
    fun `перевод строки внутри кавычек сохраняется`() {
        val rows = CsvReader.parse(csv("~строка1\nстрока2~;~b~"), ';')
        assertThat(rows).hasSize(1)
        assertThat(rows[0][0]).isEqualTo("строка1\nстрока2")
    }

    @Test
    fun `CRLF и пустые строки`() {
        val rows = CsvReader.parse("a;b\r\n\r\n1;2\r\n", ';')
        assertThat(rows).hasSize(2)
        assertThat(rows[1]).containsExactly("1", "2").inOrder()
    }

    @Test
    fun `пустые поля сохраняются`() {
        val rows = CsvReader.parse("a;;c", ';')
        assertThat(rows[0]).containsExactly("a", "", "c").inOrder()
    }

    @Test
    fun `последняя строка без перевода строки`() {
        val rows = CsvReader.parse("a;b\n1;2", ';')
        assertThat(rows).hasSize(2)
    }
}
