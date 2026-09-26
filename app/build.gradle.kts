import java.security.KeyStore
import java.security.MessageDigest

// Huella SHA-256 del certificado de la clave de subida a Play. Un paquete de publicación sin firmar o con otra
// clave no se genera: el build falla (ver checkPublishedKey).
val publishedCertSha256 = "655FFA802C0B16B3CF208FE118984DC67E448271972EFFBC1C9082D3AF81C372"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.imagesorter"
    compileSdk = 36

    // La clave de subida a Play (Play App Signing firma con la suya lo que se instala). Play rechaza lo firmado con
    // otra, y la de debug nunca la acepta. Vive fuera del repo: imagesorter.keystore, .keyAlias, .keystorePassword y
    // .keyPassword van en el gradle.properties del Gradle home.
    val publishedKeystore = providers.gradleProperty("imagesorter.keystore").orNull?.let { file(it) }
    val publishedAlias = providers.gradleProperty("imagesorter.keyAlias").orNull
    val publishedPassword = providers.gradleProperty("imagesorter.keystorePassword").orNull

    defaultConfig {
        applicationId = "io.github.nispeter.peakselect"
        minSdk = 29
        targetSdk = 36
        versionCode = 8
        versionName = "1.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (publishedKeystore?.exists() == true) {
            create("published") {
                storeFile = publishedKeystore
                storePassword = publishedPassword
                keyAlias = publishedAlias
                keyPassword = providers.gradleProperty("imagesorter.keyPassword").orNull
                // v2, igual que todas las versiones publicadas (Android 7+). v3 solo sirve para rotar la clave, y con él
                // AGP deja de poner v2.
                enableV2Signing = true
                enableV3Signing = false
            }
        }
    }

    buildTypes {
        release {
            // Sin minificar: el APK publicado se comporta igual al probado, sin reglas de R8 que revisar.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("published")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

val checkPublishedKey by tasks.registering {
    // "Generar APK firmado" de Android Studio inyecta otra clave y saltaría esta comprobación.
    val injected = providers.gradleProperty("android.injected.signing.store.file")
    doLast {
        if (injected.isPresent) throw GradleException("No se publica con una clave inyectada (${injected.get()}): usa bundleRelease")
        val keystore = android.signingConfigs.findByName("published")?.storeFile
            ?: throw GradleException("Falta la clave de subida (imagesorter.keystore en el gradle.properties del Gradle home)")
        val password = (android.signingConfigs.getByName("published").storePassword ?: "").toCharArray()
        val alias = android.signingConfigs.getByName("published").keyAlias
        val cert = KeyStore.getInstance(keystore, password).getCertificate(alias)
            ?: throw GradleException("La clave de publicación no tiene el alias $alias")
        val sha = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02X".format(it) }
        if (sha != publishedCertSha256) {
            throw GradleException("La clave de publicación no es la de subida a Play ($sha): Play rechazaría el paquete")
        }
    }
}
tasks.matching { it.name == "packageRelease" || it.name == "signReleaseBundle" }.configureEach { dependsOn(checkPublishedKey) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
