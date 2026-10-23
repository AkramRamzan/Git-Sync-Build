package com.aro.code.core.di

import com.aro.code.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Logging) { level = LogLevel.HEADERS }
        engine {
            connectTimeout = 15_000
            socketTimeout  = 30_000
        }
    }

    @Provides
    @Named("github_client_id")
    fun provideGitHubClientId(): String = BuildConfig.GITHUB_CLIENT_ID

    @Provides
    @Named("google_client_id")
    fun provideGoogleClientId(): String = BuildConfig.GOOGLE_CLIENT_ID
}
