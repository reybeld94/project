package com.reybel.ellentv.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory


object ApiClient {

    const val BASE_URL = "http://10.0.0.188:8000/"


    private val http = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
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
