package com.aura.music.data.network

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit

/**
 * Factory for creating and configuring the AURA API client.
 * Manages HTTP client lifecycle and Retrofit configuration.
 */
object AuraHttpClientFactory {

    private const val AURA_API_BASE_URL = "http://212.90.121.80:8000/"
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 10L

    /**
     * Create and configure the OkHttp client with interceptors and timeouts.
     */
    fun createOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Create Moshi instance with Kotlin support for JSON serialization.
     */
    fun createMoshi(): Moshi = Moshi.Builder()
        .add(BestMatchAdapter.FACTORY)
        .add(KotlinJsonAdapterFactory())
        .build()

    /**
     * Create and configure Retrofit instance for AURA API.
     */
    fun createRetrofit(
        okHttpClient: OkHttpClient = createOkHttpClient(),
        moshi: Moshi = createMoshi()
    ): Retrofit = Retrofit.Builder()
        .baseUrl(AURA_API_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    /**
     * Create AuraApiService from Retrofit instance.
     */
    fun createAuraApiService(retrofit: Retrofit = createRetrofit()): AuraApiService =
        retrofit.create(AuraApiService::class.java)
}
