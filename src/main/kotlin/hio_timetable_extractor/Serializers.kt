package de.mbehrmann.hio_timetable_extractor

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

val GERMAN_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu")
val JSON_SERIALIZER: Json = Json {
    serializersModule = SerializersModule {
        contextual(LocalDateSerializer)
        contextual(LocalTimeSerializer)
    }
}

@OptIn(ExperimentalSerializationApi::class)
val JSON_PASCAL_CASE_SERIALIZER: Json = Json {
    serializersModule = SerializersModule {
        contextual(LocalDateTimeSerializer)
    }
    namingStrategy = PascalCaseNamingStrategy
    prettyPrint = true
}

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        this::class.qualifiedName!!,
        PrimitiveKind.STRING
    )

    override fun serialize(encoder: Encoder, value: LocalDate) {
        encoder.encodeString(GERMAN_DATE_FORMATTER.format(value))
    }

    override fun deserialize(decoder: Decoder): LocalDate {
        return LocalDate.parse(decoder.decodeString(), GERMAN_DATE_FORMATTER)
    }
}

object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        this::class.qualifiedName!!,
        PrimitiveKind.STRING
    )

    override fun serialize(encoder: Encoder, value: LocalTime) {
        encoder.encodeString(
            "${value.hour.toString().padStart(2, '0')}:${
                value.minute.toString().padStart(2, '0')
            }"
        )
    }

    override fun deserialize(decoder: Decoder): LocalTime {
        return LocalTime.parse(decoder.decodeString())
    }
}

object LocalDateTimeSerializer : KSerializer<LocalDateTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        this::class.qualifiedName!!,
        PrimitiveKind.STRING
    )

    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        encoder.encodeString(DateTimeFormatter.ISO_DATE_TIME.format(value))
    }

    override fun deserialize(decoder: Decoder): LocalDateTime {
        return LocalDateTime.parse(decoder.decodeString(), DateTimeFormatter.ISO_DATE_TIME)
    }
}

@OptIn(ExperimentalSerializationApi::class)
object PascalCaseNamingStrategy : JsonNamingStrategy {
    override fun serialNameForJson(
        descriptor: SerialDescriptor,
        elementIndex: Int,
        serialName: String
    ): String = serialName[0].uppercaseChar() + serialName.substring(1)

    override fun toString(): String = this::class.qualifiedName!!
}