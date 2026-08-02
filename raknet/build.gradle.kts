plugins {
    `java-library`
}

dependencies {
    api(project(":common"))
    api(libs.netty.handler)
    api(libs.expiringmap)
    implementation(libs.fastutil)
    annotationProcessor(libs.lombok)
    compileOnly(libs.lombok)
    compileOnly(libs.javax.annotations)
}

group = "com.nukkitx.network"
description = "raknet"
