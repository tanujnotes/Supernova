package com.supernova.launcher.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// How a setting's enum is written and read, in the store and in a backup
// alike: as its name. Reading is lenient, as each enum's fromName always
// was: any case, and a name this build doesn't know (a backup from a newer
// version, a look since removed) is the fallback instead of an error. So a
// LauncherPrefs field can be the enum itself without a backup ever failing
// to restore over one value.
open class SettingEnumSerializer<E : Enum<E>>(
    serialName: String,
    private val values: List<E>,
    private val fallback: E
) : KSerializer<E> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): E = parse(decoder.decodeString())

    fun parse(name: String): E = values.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback
}

// How the device is locked by the Lock screen action.
@kotlinx.serialization.Serializable(with = LockMethod.Names::class)
enum class LockMethod {
    ACCESSIBILITY,
    DEVICE_ADMIN;

    object Names : SettingEnumSerializer<LockMethod>("LockMethod", entries, ACCESSIBILITY)
}

// Whether search opens over the whole screen or in a window.
@kotlinx.serialization.Serializable(with = SearchWindowMode.Names::class)
enum class SearchWindowMode {
    IMMERSIVE,
    WINDOW;

    object Names : SettingEnumSerializer<SearchWindowMode>("SearchWindowMode", entries, IMMERSIVE)
}
