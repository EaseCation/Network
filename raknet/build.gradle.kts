plugins {
    `java-library`
}

dependencies {
    api(project(":common"))
    api(libs.netty.handler)
    api(libs.expiringmap)
    annotationProcessor(libs.lombok)
    compileOnly(libs.lombok)
    compileOnly(libs.javax.annotations)
}

group = "com.nukkitx.network"
description = "raknet"
