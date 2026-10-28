package com.pcpowercard.data

data class Device(
    val mac: String,
    val sn: String,
    val name: String
) {
    companion object {
        fun safeMac(mac: String): String =
            mac.lowercase().let { if (it.length >= 17) it else "unknown" }

        /**
         * Normalize MAC: accept aa:bb:cc:dd:ee:ff, aa-bb-cc-dd-ee-ff, or 12 hex digits;
         * return lowercase with '-' separators (e.g. 18-fe-34-9e-99-38).
         */
        fun normalizeMac(mac: String): String {
            val hex = mac.lowercase().replace(":", "").replace("-", "")
            if (hex.length != 12 || !hex.all { it in "0123456789abcdef" }) return mac.lowercase()
            return (0 until 6).map { i -> hex.substring(i * 2, i * 2 + 2) }.joinToString("-")
        }

        /**
         * Build ESP8266 MQTT topic prefix:
         *   pc/pc-proc-card-<sn>-<mac>
         * SN is kept as saved; MAC must be in aa-bb-cc-dd-ee-ff lowercase form.
         */
        fun topicPrefix(sn: String, mac: String): String =
            "pc/pc-proc-card-" + sn + "-" + normalizeMac(mac)
    }
}
