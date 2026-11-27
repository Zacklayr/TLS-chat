plugins {
    id("com.android.application")
}

android {
    namespace = "kz.zacklayr.tlschat.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "kz.zacklayr.tlschat.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
}
