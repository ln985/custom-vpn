package com.wzydqq.icu.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wzydqq.icu.databinding.ActivityLocationPickerBinding
import com.wzydqq.icu.location.LocationStore
import com.wzydqq.icu.location.PresetLocations
import com.wzydqq.icu.location.SelectedLocation

class LocationPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocationPickerBinding

    private var selectedProvince: String = ""
    private var selectedCity: String = ""
    private var selectedDistrict: PresetLocations.District? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocationPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinners()
        setupButtons()
    }

    private fun setupSpinners() {
        // 省份 Spinner
        val provinces = mutableListOf("请选择省份")
        provinces.addAll(PresetLocations.provinceNames())
        val provinceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, provinces)
        binding.spinnerProvince.adapter = provinceAdapter

        binding.spinnerProvince.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedProvince = if (position > 0) provinces[position] else ""
                updateCitySpinner()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        // 城市 Spinner
        binding.spinnerCity.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val cities = if (selectedProvince.isNotEmpty()) {
                    listOf("请选择城市") + PresetLocations.cityNames(selectedProvince)
                } else listOf("请先选择省份")
                selectedCity = if (position > 0 && position < cities.size) cities[position] else ""
                updateDistrictSpinner()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        // 区县 Spinner
        binding.spinnerDistrict.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val districts = if (selectedProvince.isNotEmpty() && selectedCity.isNotEmpty()) {
                    PresetLocations.districtNames(selectedProvince, selectedCity)
                } else emptyList()
                selectedDistrict = if (position > 0 && position <= districts.size) districts[position - 1] else null
                updatePreview()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
    }

    private fun updateCitySpinner() {
        val cities = if (selectedProvince.isNotEmpty()) {
            listOf("请选择城市") + PresetLocations.cityNames(selectedProvince)
        } else {
            listOf("请先选择省份")
        }
        binding.spinnerCity.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cities)
        selectedCity = ""
        selectedDistrict = null
        updateDistrictSpinner()
    }

    private fun updateDistrictSpinner() {
        val districts = if (selectedProvince.isNotEmpty() && selectedCity.isNotEmpty()) {
            listOf("请选择区县") + PresetLocations.districtNames(selectedProvince, selectedCity).map { it.name }
        } else {
            listOf("请先选择城市")
        }
        binding.spinnerDistrict.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, districts)
        selectedDistrict = null
        updatePreview()
    }

    private fun updatePreview() {
        val district = selectedDistrict
        if (district != null) {
            binding.tvPreview.text = buildString {
                append("📍 预览\n\n")
                append("省份: $selectedProvince\n")
                append("城市: $selectedCity\n")
                append("区县: ${district.name}\n")
                append("坐标: ${district.lat}, ${district.lng}\n")
                append("区划代码: ${district.adcode}")
            }
            binding.btnSave.isEnabled = true
        } else {
            binding.tvPreview.text = "请依次选择省 → 市 → 区"
            binding.btnSave.isEnabled = false
        }
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            val district = selectedDistrict ?: return@setOnClickListener
            val location = SelectedLocation(
                provinceName = selectedProvince,
                cityName = selectedCity,
                districtName = district.name,
                adcode = district.adcode,
                lat = district.lat,
                lng = district.lng
            )
            LocationStore.save(this, location)
            Toast.makeText(this, "位置已保存: ${selectedProvince} ${selectedCity} ${district.name}", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }
    }
}
