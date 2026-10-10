package me.kavishdevar.librepods.data

internal data class CachedAirPodsInformation(val owner: String, val instance: AirPodsInstance)

internal fun ownedAirPodsModel(model: String, owner: String, selected: String): AirPodsBase? {
    val identity = batteryHistoryIdentity(selected) ?: return null
    if (batteryHistoryIdentity(owner) != identity) return null
    return AirPodsModels.getModelByModelNumber(model)
}

/** All fields come from one preference snapshot, including the selected peer and information owner. */
internal fun cachedAirPodsInformation(values: Map<String, *>): CachedAirPodsInformation? {
    fun string(key: String) = values[key] as? String ?: ""
    val owner = batteryHistoryIdentity(string("airpods_model_address")) ?: return null
    val number = string("airpods_model_number")
    val model = ownedAirPodsModel(number, owner, string("mac_address")) ?: return null
    return CachedAirPodsInformation(owner, AirPodsInstance(
        name = string("name").ifEmpty { "AirPods" }, model = model, actualModelNumber = number,
        serialNumber = string("airpods_serial_number"),
        leftSerialNumber = string("airpods_left_serial_number"),
        rightSerialNumber = string("airpods_right_serial_number"),
        version1 = string("airpods_version1"), version2 = string("airpods_version2"),
        version3 = string("airpods_version3")
    ))
}
