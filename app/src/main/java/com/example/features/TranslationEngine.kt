package com.example.features

import android.util.Log
import com.example.data.local.PreferencesRepository
import com.example.data.model.TranslationDirection
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

class TranslationEngine(
    private val prefsRepo: PreferencesRepository
) {
    private val geminiTranslator by lazy {
        Firebase.ai.generativeModel("gemini-3.5-flash-lite")
    }

    private val enToEsOffline by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.SPANISH)
                .build()
        )
    }

    private val esToEnOffline by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.SPANISH)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        )
    }

    suspend fun translate(
        text: String,
        direction: TranslationDirection,
        isOnline: Boolean
    ): Result<String> {
        val clean = text.trim()
        if (clean.isEmpty() || direction == TranslationDirection.OFF) {
            return Result.success(text)
        }

        if (isOnline) {
            try {
                val instruction = when (direction) {
                    TranslationDirection.EN_TO_ES ->
                        "Translate the following message naturally into Spanish. Return ONLY the translated text with no quotes, notes, or markdown:\n$clean"
                    TranslationDirection.ES_TO_EN ->
                        "Translate the following message naturally into English. Return ONLY the translated text with no quotes, notes, or markdown:\n$clean"
                    TranslationDirection.OFF -> return Result.success(text)
                }
                val response = withTimeout(30_000L) {
                    geminiTranslator.generateContent(instruction)
                }
                val translated = response.text?.trim()
                if (!translated.isNullOrEmpty()) {
                    return Result.success(translated)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cloud Gemini translation unavailable, falling back to offline ML Kit: ${e.message}")
            }
        }

        // Offline / fallback on-device ML Kit Translation
        return try {
            val client = when (direction) {
                TranslationDirection.EN_TO_ES -> enToEsOffline
                TranslationDirection.ES_TO_EN -> esToEnOffline
                TranslationDirection.OFF -> return Result.success(text)
            }
            val result = withTimeout(15_000L) {
                client.translate(clean).await()
            }
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Offline translation failed", e)
            Result.failure(
                IllegalStateException(
                    "Translation unavailable offline until the EN/ES pack is downloaded in Settings."
                )
            )
        }
    }

    suspend fun downloadOfflinePacks(): Result<Unit> {
        return try {
            val conditions = DownloadConditions.Builder().build()
            withTimeout(60_000L) {
                enToEsOffline.downloadModelIfNeeded(conditions).await()
                esToEnOffline.downloadModelIfNeeded(conditions).await()
            }
            prefsRepo.setOfflineEsModelReady(true)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download offline EN/ES translation models", e)
            Result.failure(e)
        }
    }

    private companion object {
        const val TAG = "TranslationEngine"
    }
}
