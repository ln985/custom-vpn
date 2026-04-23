package com.wzydqq.icu.location

data class SelectedLocation(
    val provinceName: String,
    val cityName: String,
    val districtName: String,
    val adcode: Int,
    val lat: Double,
    val lng: Double
) {
    fun toLocationString(): String = "$lat,$lng"

    companion object {
        val EMPTY = SelectedLocation("", "", "", 0, 0.0, 0.0)
    }
}
