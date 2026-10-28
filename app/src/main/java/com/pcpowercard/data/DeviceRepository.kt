package com.pcpowercard.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DeviceRepository(context: Context) {
    private val prefs = context.getSharedPreferences("devices_v1_prefs", Context.MODE_PRIVATE)

    fun loadDevices(): MutableList<Device> {
        val raw = prefs.getString("devices_v1", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                Device(
                    mac = obj.getString("mac"),
                    sn = obj.getString("sn"),
                    name = obj.optString("name", "PC-" + obj.getString("sn"))
                )
            }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(devices: List<Device>) {
        val arr = JSONArray()
        devices.forEach { d ->
            val obj = JSONObject()
            obj.put("mac", d.mac)
            obj.put("sn", d.sn)
            obj.put("name", d.name)
            arr.put(obj)
        }
        prefs.edit().putString("devices_v1", arr.toString()).apply()
    }

    fun addIfNotExists(device: Device): Boolean {
        val list = loadDevices()
        val exists = list.any { it.mac == device.mac }
        if (!exists) {
            list.add(device)
            save(list)
            return true
        }
        return false
    }

    fun deleteDevice(mac: String): Boolean {
        val list = loadDevices()
        val before = list.size
        val filtered = list.filter { it.mac != mac }
        if (filtered.size < before) {
            save(filtered)
            return true
        }
        return false
    }
}
