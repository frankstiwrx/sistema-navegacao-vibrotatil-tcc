// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
}

buildscript {
    repositories {
        google() // Repositório necessário para o Android
        mavenCentral() // Repositório geral
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.0.2")  // Certifique-se de que a versão está correta
    }
}
