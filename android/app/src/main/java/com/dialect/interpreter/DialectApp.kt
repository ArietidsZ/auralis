package com.dialect.interpreter

import android.app.Application
import com.dialect.interpreter.inference.OnnxModelManager

class DialectApp : Application() {

    lateinit var modelManager: OnnxModelManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        modelManager = OnnxModelManager(this)
    }

    companion object {
        lateinit var instance: DialectApp
            private set
    }
}
