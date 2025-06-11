plugins {
    `java-library`
}

dependencies {
    api(project(":Network:common"))
    api(libs.netty.handler)
    api(libs.expiringmap)
    annotationProcessor(libs.lombok)
    compileOnly(libs.lombok)
    compileOnly(libs.spotbugs.annotations)
    compileOnly(libs.javax.annotations)
}

group = "com.nukkitx.network"
description = "raknet"
