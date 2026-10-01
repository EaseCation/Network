plugins {
    `java-library`
    id("ecbuild.java-conventions")
}

dependencies {
    testImplementation(libs.jupiter.engine)
    testImplementation(libs.mockito.core)
    api(project(":Network:common"))
    api(libs.netty.handler)
    api(libs.expiringmap)
    implementation(libs.fastutil)
    annotationProcessor(libs.lombok)
    compileOnly(libs.lombok)
    compileOnly(libs.javax.annotations)
}

group = "com.nukkitx.network"
description = "raknet"
