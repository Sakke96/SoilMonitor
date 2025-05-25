package com.example.soilmonitor

import retrofit2.Call
import retrofit2.http.GET

interface SoilApi {
    @get:GET("log")
    val logs: Call<List<SoilEntry>>
}
