package com.handykit.app.core.db

import androidx.room.TypeConverter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Room 类型转换器。日期存 ISO 字符串，避免时区歧义。 */
class Converters {
    private val fmt = DateTimeFormatter.ISO_LOCAL_DATE

    @TypeConverter
    fun dateToString(d: LocalDate?): String? = d?.format(fmt)

    @TypeConverter
    fun stringToDate(s: String?): LocalDate? =
        if (s.isNullOrBlank()) null else runCatching { LocalDate.parse(s, fmt) }.getOrNull()
}
