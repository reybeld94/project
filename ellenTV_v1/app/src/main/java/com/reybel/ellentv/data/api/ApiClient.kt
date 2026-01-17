package com.reybel.ellentv.data.api

import com.reybel.ellentv.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory


object ApiClient {

    private const val DEFAULT_BASE_URL = "http://10.0.0.188:8000/"
    val BASE_URL: String = BuildConfig.API_BASE_URL
        .ifBlank { DEFAULT_BASE_URL }
        .let { base ->
            if (base.endsWith("/")) base else "$base/"
        }

    private val httpLoggingLevel = if (BuildConfig.DEBUG) {
        HttpLoggingInterceptor.Level.BODY
    } else {
        HttpLoggingInterceptor.Level.NONE
    }

    private val http = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = httpLoggingLevel
        })
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(http)
        // String crudo si lo necesitas
        .addConverterFactory(ScalarsConverterFactory.create())
        // JSON a data classes
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
}
