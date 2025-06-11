plugins {
    `java-library`
}

dependencies {
    api(libs.netty.buffer)
    api(libs.netty.epoll)
    api(libs.netty.kqueue)
    annotationProcessor(libs.lombok)
    compileOnly(libs.lombok)
    compileOnly(libs.spotbugs.annotations)
    compileOnly(libs.javax.annotations)
}

group = "com.nukkitx.network"
description = "common"
