package app.zoocall.core.transport

import app.zoocall.core.model.CallKind
import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.Presence
import app.zoocall.protocol.v1.CallKind as ProtoCallKind
import app.zoocall.protocol.v1.DeviceClass as ProtoDeviceClass
import app.zoocall.protocol.v1.Presence as ProtoPresence

fun DeviceClass.toProto(): ProtoDeviceClass = when (this) {
    DeviceClass.Phone -> ProtoDeviceClass.DEVICE_CLASS_PHONE
    DeviceClass.Tablet -> ProtoDeviceClass.DEVICE_CLASS_TABLET
    DeviceClass.Desktop -> ProtoDeviceClass.DEVICE_CLASS_DESKTOP
}

fun ProtoDeviceClass.toModel(): DeviceClass = when (this) {
    ProtoDeviceClass.DEVICE_CLASS_TABLET -> DeviceClass.Tablet
    ProtoDeviceClass.DEVICE_CLASS_DESKTOP -> DeviceClass.Desktop
    else -> DeviceClass.Phone
}

fun Presence.toProto(): ProtoPresence = when (this) {
    Presence.Available -> ProtoPresence.PRESENCE_AVAILABLE
    Presence.Busy -> ProtoPresence.PRESENCE_BUSY
    Presence.DoNotDisturb -> ProtoPresence.PRESENCE_DND
    Presence.Away -> ProtoPresence.PRESENCE_AWAY
}

fun ProtoPresence.toModel(): Presence = when (this) {
    ProtoPresence.PRESENCE_BUSY -> Presence.Busy
    ProtoPresence.PRESENCE_DND -> Presence.DoNotDisturb
    ProtoPresence.PRESENCE_AWAY -> Presence.Away
    else -> Presence.Available
}

fun CallKind.toProto(): ProtoCallKind = when (this) {
    CallKind.Audio -> ProtoCallKind.CALL_KIND_AUDIO
    CallKind.Video -> ProtoCallKind.CALL_KIND_VIDEO
}

fun ProtoCallKind.toModel(): CallKind = if (this == ProtoCallKind.CALL_KIND_VIDEO) CallKind.Video else CallKind.Audio
