package com.aro.code.auth

import android.app.Activity
import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint

/** Receives the arocode://oauth redirect from GitHub browser flow. */
@AndroidEntryPoint
class OAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The deep-link URI is passed back via LocalBroadcast or SharedFlow
        // handled by AuthViewModel; finish immediately.
        finish()
    }
}
