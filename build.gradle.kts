allprojects {
    plugins.apply("java-library")
    val javaLanguageVersion = JavaLanguageVersion.of(21)
    the<JavaPluginExtension>().apply {
        toolchain {
            languageVersion = javaLanguageVersion
        }
    }
}
