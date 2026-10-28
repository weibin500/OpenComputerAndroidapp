package com.pcpowercard.data

data class DeviceStatus(
    val power: String = "OFF",
    val wifi: String = "DOWN",
    val mqtt: String = "DOWN",
    val temp: Float? = null,
    val online: Boolean = false,
    val time: String = ""
)
