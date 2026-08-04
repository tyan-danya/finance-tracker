package com.dtyan.spendtracker.importer

import com.google.common.truth.Truth.assertThat
import com.dtyan.spendtracker.data.DefaultCategories
import org.junit.Test

class BankCategoryMapperTest {

    @Test
    fun `MCC супермаркета даёт Продукты`() {
        val s = BankCategoryMapper.suggest("что угодно", 5411)
        assertThat(s).isNotNull()
        assertThat(s!!.category).isEqualTo("Продукты")
        assertThat(s.subcategory).isEqualTo("Супермаркет")
    }

    @Test
    fun `MCC заправки даёт Транспорт Бензин`() {
        val s = BankCategoryMapper.suggest(null, 5541)
        assertThat(s!!.category).isEqualTo("Транспорт")
        assertThat(s.subcategory).isEqualTo("Бензин")
    }

    @Test
    fun `неизвестный MCC откатывается на категорию банка`() {
        // 3990 в таблице MCC нет — используется текстовая категория «Такси».
        val s = BankCategoryMapper.suggest("Такси", 3990)
        assertThat(s!!.category).isEqualTo("Транспорт")
        assertThat(s.subcategory).isEqualTo("Такси")
    }

    @Test
    fun `категория банка без MCC`() {
        val s = BankCategoryMapper.suggest("Фастфуд", null)
        assertThat(s!!.category).isEqualTo("Кафе и рестораны")
        assertThat(s.subcategory).isEqualTo("Фастфуд")
    }

    @Test
    fun `регистр категории не важен`() {
        assertThat(BankCategoryMapper.suggest("СУПЕРМАРКЕТЫ", null)).isNotNull()
    }

    @Test
    fun `ничего не сопоставилось - null`() {
        assertThat(BankCategoryMapper.suggest("Нечто неведомое", null)).isNull()
        assertThat(BankCategoryMapper.suggest(null, null)).isNull()
        assertThat(BankCategoryMapper.suggest("", 0)).isNull()
    }

    @Test
    fun `все предложенные категории существуют в дефолтной таксономии`() {
        val categories = DefaultCategories.tree.associate { seed ->
            seed.name to seed.subcategories.toSet()
        }
        // Собираем все Suggestion, до которых можно дотянуться публичным API.
        val samplesMcc = listOf(5411, 5541, 4121, 5812, 5814, 5912, 8011, 5691, 5816, 4816,
            5977, 7230, 7997, 4900, 5200, 3000, 3500, 5995, 5945, 5921)
        val samplesCat = listOf("супермаркеты", "фастфуд", "такси", "связь", "цифровые товары",
            "аптеки", "красота", "жкх", "путешествия", "маркетплейсы", "различные услуги",
            "образование", "спорт", "дети", "питомцы")
        val suggestions = buildList {
            samplesMcc.forEach { BankCategoryMapper.suggest(null, it)?.let(::add) }
            samplesCat.forEach { BankCategoryMapper.suggest(it, null)?.let(::add) }
        }
        assertThat(suggestions).isNotEmpty()
        suggestions.forEach { s ->
            assertThat(categories.keys).contains(s.category)
            if (s.subcategory != null) {
                assertThat(categories[s.category]).contains(s.subcategory)
            }
        }
    }
}
