package com.aro.code

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 * Hilt generates the dependency graph from this root.
 */
@HiltAndroidApp
class AroCodeApplication : Application()
